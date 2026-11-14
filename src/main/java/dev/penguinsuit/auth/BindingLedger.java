package dev.penguinsuit.auth;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.penguinsuit.config.BridgeConfig;
import dev.penguinsuit.mention.MemberDirectory.Scope;
import java.io.IOException;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.function.LongSupplier;

/** One authoritative record per UUID and scoped QQ identity. Pending secrets never reach disk. */
public final class BindingLedger {
    public enum Phase { PREPARED, COMMITTED, CONFLICT, REMOVING }
    public record Binding(UUID player, Scope scope, String member, String gameName, String qqName,
                          String request, long confirmed, Phase phase, String previousMirror) {
        Binding phase(Phase p) { return new Binding(player,scope,member,gameName,qqName,request,confirmed,p,previousMirror); }
        public String key() { return scope.key(member); }
    }
    public interface Mirror {
        Map<String,String> snapshot(Scope scope) throws IOException;
        default boolean attached(Scope scope){return true;}
        /** Compare and set, including durable readback. Never replace another relation on conflict. */
        void change(Scope scope, String member, String expected, String next) throws IOException;
    }
    @FunctionalInterface public interface Writer { void write(Path file,String json) throws IOException; }
    public static final class Rejected extends IllegalArgumentException { public Rejected(String text){super(text);} }
    public record Claim(UUID player, String qqName, String member) {}
    private record Pending(UUID player, Scope scope, String gameName, String pairingHash, long expires,
                           String request, String member, String qqName) {}
    private record Removal(String bindingKey, String request, String hash, long expires) {}
    private record Window(long since, int attempts) {}
    private static final class State { int schema=1; List<Binding> bindings=new ArrayList<>(); }
    private static final Gson JSON=new GsonBuilder().setPrettyPrinting().create();
    public static final long TTL=300_000;
    private static final char[] ALPHABET="ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();
    private final Path file;
    private final Mirror mirror;
    private final LongSupplier clock;
    private final Writer writer;
    private final SecureRandom random=new SecureRandom();
    private Map<String,Binding> bindings=new LinkedHashMap<>();
    private final Map<UUID,Pending> pending=new HashMap<>();
    private final Map<String,Removal> removals=new HashMap<>();
    private final Map<String,Window> rates=new HashMap<>();
    private boolean failed;
    private dev.penguinsuit.config.FeatureOptions.Authentication options=new dev.penguinsuit.config.FeatureOptions.Authentication();
    public synchronized void configure(dev.penguinsuit.config.FeatureOptions.Authentication next){options=next;}

    public BindingLedger(Path file,Mirror mirror)throws IOException { this(file,mirror,System::currentTimeMillis,BridgeConfig::atomicWrite); }
    public BindingLedger(Path file,Mirror mirror,LongSupplier clock,Writer writer)throws IOException {
        this.file=file;this.mirror=mirror;this.clock=clock;this.writer=writer;
        if(Files.exists(file))try{
            State state=JSON.fromJson(Files.readString(file),State.class);
            if(state==null||state.schema!=1||state.bindings==null)throw new IOException("Invalid binding ledger schema");
            Set<UUID> players=new HashSet<>();
            for(Binding b:state.bindings){
                if(b==null||b.player()==null||b.scope()==null||!valid(b.scope().app())||!valid(b.scope().group())||!valid(b.member())
                        ||b.gameName()==null||!b.gameName().matches("[A-Za-z0-9_]{1,16}")||b.qqName()==null||b.phase()==null||b.request()==null
                        ||!players.add(b.player())||bindings.putIfAbsent(b.key(),b)!=null)throw new IOException("Invalid or duplicate binding record");
            }
        }catch(RuntimeException e){throw new IOException("Binding ledger unreadable; refusing automatic identity rollback",e);}
    }
    private static boolean valid(String s){return dev.penguinsuit.mention.MemberDirectory.validId(s);}
    private void healthy(){if(failed)throw new Rejected("认证账本保存失败，已暂停认证；请修复磁盘后重启服务器");}
    public synchronized void expire(){
        long now=clock.getAsLong();pending.values().removeIf(p->now>=p.expires());removals.values().removeIf(r->now>=r.expires());
        rates.values().removeIf(w->now-w.since()>=options.rateWindowSeconds*1000L);
    }
    private void rate(String key){
        expire();Window w=rates.get(key);
        if(w==null){if(rates.size()>=4096)throw new Rejected("认证请求过多，请稍后重试");w=new Window(clock.getAsLong(),0);}
        if(w.attempts()>=options.maxAttempts)throw new Rejected("认证尝试过于频繁，请 "+options.rateWindowSeconds+" 秒后重试");
        rates.put(key,new Window(w.since(),w.attempts()+1));
    }
    public synchronized String start(UUID uuid,String name,Scope scope){
        healthy();rate("mc/"+uuid);
        if(player(uuid)!=null)throw new Rejected("已有绑定或待恢复的记录，请先查看 /qqbind status 并解绑");
        if(!pending.containsKey(uuid)&&pending.size()>=options.maxPending)throw new Rejected("待认证人数已满，请稍后重试");
        String code=code();pending.put(uuid,new Pending(uuid,scope,name,hash(code),clock.getAsLong()+options.codeTtlSeconds*1000L,UUID.randomUUID().toString(),null,null));
        return code;
    }
    public synchronized Claim claim(Scope scope,String member,String name,String code){
        healthy();rate("qq/"+scope.key(member));
        if(!valid(member))throw new Rejected("无效 QQ 身份");
        if(bindings.containsKey(scope.key(member)))throw new Rejected("此 QQ 已有绑定或冲突记录，请先解绑");
        Pending p=pending.values().stream().filter(x->x.scope().equals(scope)&&x.pairingHash().equals(hash(code))).findFirst().orElse(null);
        if(p==null)throw new Rejected("配对码无效或已过期");
        if(p.member()!=null)throw new Rejected("此申请已被认领，请在游戏内确认或取消；认领对象不能替换");
        if(pending.values().stream().anyMatch(x->scope.equals(x.scope())&&member.equals(x.member())))throw new Rejected("此 QQ 已有待确认申请");
        pending.put(p.player(),new Pending(p.player(),scope,p.gameName(),p.pairingHash(),p.expires(),p.request(),member,name));
        return new Claim(p.player(),name,member);
    }
    public synchronized Binding confirm(UUID player,Scope scope)throws IOException {
        healthy();rate("mc/"+player);Pending p=pending.get(player);
        if(p==null||!p.scope().equals(scope))throw new Rejected("没有当前玩家的有效认证申请，申请可能已过期或取消，请 /qqbind start 重新开始");
        if(p.member()==null)throw new Rejected("申请尚未被 QQ 认领，请先在指定群完成 /绑定认证 配对码，再回到游戏确认");
        if(player(player)!=null||bindings.containsKey(scope.key(p.member())))throw new Rejected("绑定冲突，请先解除旧绑定");
        Map<String,String> snapshot=mirror.snapshot(scope);String previous=snapshot.get(p.member());
        if(previous!=null&&!previous.equals(p.gameName()))throw new Rejected("Penguin 中此 QQ 已绑定其他名字，请管理员先清理旧关系");
        if(snapshot.entrySet().stream().anyMatch(e->!e.getKey().equals(p.member())&&e.getValue().equalsIgnoreCase(p.gameName())))
            throw new Rejected("Penguin 中此游戏名字已关联其他 QQ，请管理员先清理旧关系");
        Binding b=new Binding(player,scope,p.member(),p.gameName(),p.qqName(),p.request(),clock.getAsLong(),Phase.PREPARED,previous);
        put(b);pending.remove(player);finish(b);return bindings.get(b.key());
    }
    private void finish(Binding b)throws IOException {
        if(b.phase()==Phase.REMOVING&&!mirror.attached(b.scope())){remove(b.key());return;}
        Map<String,String> snapshot=mirror.snapshot(b.scope());String current=snapshot.get(b.member());
        if(b.phase()==Phase.REMOVING){
            // An external replacement is not ours to delete.
            if(current==null||Objects.equals(current,b.gameName()))mirror.change(b.scope(),b.member(),current,null);
            remove(b.key());return;
        }
        boolean duplicate=snapshot.entrySet().stream().anyMatch(e->!e.getKey().equals(b.member())&&e.getValue().equalsIgnoreCase(b.gameName()));
        if(duplicate||(!Objects.equals(current,b.previousMirror())&&!Objects.equals(current,b.gameName()))){put(b.phase(Phase.CONFLICT));return;}
        // Even an already equal in-memory value requires durable readback/rewrite after a failed native save.
        mirror.change(b.scope(),b.member(),current,b.gameName());put(b.phase(Phase.COMMITTED));
    }
    public synchronized void reconcile(Scope scope,Map<UUID,String> names)throws IOException {
        healthy();expire();
        for(Binding b:List.copyOf(bindings.values()))if(b.scope().equals(scope)){
            if(b.phase()==Phase.PREPARED||b.phase()==Phase.REMOVING){finish(b);continue;}
            if(b.phase()!=Phase.COMMITTED)continue;
            Map<String,String> snapshot=mirror.snapshot(scope);String current=snapshot.get(b.member());
            if(!Objects.equals(current,b.gameName())||snapshot.entrySet().stream().anyMatch(e->!e.getKey().equals(b.member())&&e.getValue().equalsIgnoreCase(b.gameName()))){put(b.phase(Phase.CONFLICT));continue;}
            String renamed=names.get(b.player());
            if(renamed!=null&&!renamed.equals(b.gameName())){
                if(snapshot.entrySet().stream().anyMatch(e->!e.getKey().equals(b.member())&&e.getValue().equalsIgnoreCase(renamed))){put(b.phase(Phase.CONFLICT));continue;}
                Binding rename=new Binding(b.player(),scope,b.member(),renamed,b.qqName(),b.request(),b.confirmed(),Phase.PREPARED,b.gameName());
                put(rename);finish(rename);
            }
        }
    }
    public synchronized Binding player(UUID uuid){return bindings.values().stream().filter(b->b.player().equals(uuid)).findFirst().orElse(null);}
    public synchronized Binding member(Scope scope,String id){return bindings.get(scope.key(id));}
    public synchronized Map<String,UUID> verified(Scope scope){
        if(failed)return Map.of();Map<String,UUID> result=new HashMap<>();
        bindings.values().stream().filter(b->b.scope().equals(scope)&&b.phase()==Phase.COMMITTED).forEach(b->result.put(b.member(),b.player()));return Map.copyOf(result);
    }
    public synchronized Set<String> pendingIdentityKeys(){Set<String> keys=new HashSet<>();for(var p:pending.values()){keys.add("mc/"+p.player());if(p.member()!=null)keys.add(p.scope().key(p.member()));}return Set.copyOf(keys);}
    public synchronized List<Binding> records(){return List.copyOf(bindings.values());}
    public synchronized void externalChange(Scope scope,String member)throws IOException{
        Binding b=bindings.get(scope.key(member));
        if(b!=null&&b.phase()!=Phase.REMOVING&&b.phase()!=Phase.CONFLICT)put(b.phase(Phase.CONFLICT));
    }
    public synchronized String beginRemoval(String actor,Binding b){
        healthy();rate(actor);if(b==null)throw new Rejected("当前账号没有认证绑定");
        String code=code();removals.put(actor,new Removal(b.key(),b.request(),hash(code),clock.getAsLong()+options.codeTtlSeconds*1000L));return code;
    }
    public synchronized Binding confirmRemoval(String actor,String code)throws IOException{
        healthy();rate(actor);Removal r=removals.get(actor);
        if(r==null||!r.hash().equals(hash(code)))throw new Rejected("解绑确认码无效或已过期");
        Binding b=bindings.get(r.bindingKey());
        if(b==null||!b.request().equals(r.request()))throw new Rejected("绑定已变化，请重新申请解绑");
        removals.remove(actor);delete(b);return b;
    }
    public synchronized void delete(Binding b)throws IOException {
        healthy();if(b==null)return;
        cancel(b.player());cancelMember(b.scope(),b.member());put(b.phase(Phase.REMOVING));finish(b.phase(Phase.REMOVING));
    }
    public synchronized void cancel(UUID uuid){pending.remove(uuid);removals.remove("mc/"+uuid);}
    public synchronized void cancelMember(Scope scope,String id){pending.values().removeIf(p->p.scope().equals(scope)&&id.equals(p.member()));removals.remove("qq/"+scope.key(id));}
    public synchronized void cancelAll(){pending.clear();removals.clear();}
    public synchronized void retainEligible(java.util.function.Predicate<UUID> game,java.util.function.BiPredicate<Scope,String> qq){
        expire();pending.values().removeIf(p->!game.test(p.player())||p.member()!=null&&!qq.test(p.scope(),p.member()));
    }
    private void put(Binding b)throws IOException {Map<String,Binding> next=new LinkedHashMap<>(bindings);next.put(b.key(),b);save(next);}
    private void remove(String key)throws IOException {Map<String,Binding> next=new LinkedHashMap<>(bindings);next.remove(key);save(next);}
    private void save(Map<String,Binding> next)throws IOException {
        State state=new State();state.bindings=new ArrayList<>(next.values());
        try{writer.write(file,JSON.toJson(state));bindings=next;}
        catch(IOException e){failed=true;pending.clear();removals.clear();throw e;}
    }
    private String code(){StringBuilder out=new StringBuilder();for(int i=0;i<12;i++)out.append(ALPHABET[random.nextInt(ALPHABET.length)]);return out.toString();}
    private static String hash(String code){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(code.strip().toUpperCase(Locale.ROOT).getBytes(java.nio.charset.StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException e){throw new AssertionError(e);}}
}
