package dev.penguinsuit.mention;

import com.google.gson.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** All identity decisions are synchronized; disk IO is coalesced by the service. */
public final class MemberDirectory {
    public record Scope(String app, String group) {
        public Scope { Objects.requireNonNull(app); Objects.requireNonNull(group); }
        public String key(String openid) { return app + "/" + group + "/" + openid; }
    }
    public record Target(String identity, UUID player, String openid, String gameName, String qqName,
                         boolean online, boolean fake, String association) {
        public String gameLabel() { return online && player != null ? gameName : openid != null ? qqName : gameName; }
        public String qqLabel() { return openid != null ? qqName : gameName; }
    }
    public static final class Invalid extends IllegalArgumentException {
        public Invalid(String message) { super(message); }
    }
    static final class Player { String id, name, code; long updated; }
    static final class Member { String app, group, id, name = "", code; long updated; Set<String> aliases=new LinkedHashSet<>(); }
    static final class Binding { String member, player, sourceName; }
    static final class Tombstone { long time; boolean pending; }
    static final class Code { String key; boolean active = true; }
    static final class State {
        int schema = 1; long nextCode = 36;
        Map<String, Player> players = new LinkedHashMap<>();
        Map<String, Member> members = new LinkedHashMap<>();
        Map<String, Binding> bindings = new LinkedHashMap<>();
        Map<String, Tombstone> deleted = new LinkedHashMap<>();
        Map<String, Code> codes = new LinkedHashMap<>();
        Map<String,Long> events = new LinkedHashMap<>();
        Set<String> qqBots = new LinkedHashSet<>();
        Set<String> qqBotAliases = new LinkedHashSet<>();
        Set<String> qqManualExcluded = new LinkedHashSet<>();
    }
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    private final Path file;
    private State data;
    private FakePlayerFilter filter;
    private Set<UUID> online = Set.of();
    private final Set<String> conflicts = new HashSet<>();
    private final Map<String, String> pendingNames = new HashMap<>();
    private boolean dirty;
    private long revision;
    private long reservedThrough;
    private long labelsRevision = -1;
    private Scope labelsScope;
    private Map<String,List<String>> labels = Map.of();

    public MemberDirectory(Path file, FakePlayerFilter filter) throws IOException {
        this.file = file; this.filter = filter;
        if (Files.exists(file)) {
            try { data = read(file); }
            catch (Exception e) {
                Path backup = file.resolveSibling(file.getFileName() + ".bak");
                if (!Files.exists(backup)) throw new IOException("Member directory is corrupt; refusing identity reset", e);
                data = read(backup);
                Files.copy(file, file.resolveSibling(file.getFileName() + ".corrupt-" + System.currentTimeMillis()));
                Files.copy(backup, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } else data = new State();
        Path counter=file.resolveSibling(file.getFileName()+".counter");
        if(Files.exists(counter))try{data.nextCode=Math.max(data.nextCode,Long.parseLong(Files.readString(counter).strip()));}
        catch(RuntimeException e){throw new IOException("Invalid identity allocation counter; refusing code reuse",e);}
        reservedThrough=data.nextCode;
        Path journal=file.resolveSibling(file.getFileName()+".deletions");
        if(Files.exists(journal))try{
            var type=new com.google.gson.reflect.TypeToken<Map<String,Tombstone>>(){}.getType();
            Map<String,Tombstone> entries=JSON.fromJson(Files.readString(journal),type);
            if(entries==null)throw new IOException("Invalid deletion journal");
            for(var e:entries.entrySet()){
                Tombstone previous=data.deleted.get(e.getKey());
                if(previous==null||e.getValue().time>=previous.time)data.deleted.put(e.getKey(),e.getValue());
                Member member=data.members.get(e.getKey());
                if(member!=null&&(e.getValue().pending||member.updated<=e.getValue().time)){
                    retire(member.code);data.members.remove(e.getKey());Binding b=data.bindings.remove(e.getKey());
                    if(b!=null)rotatePlayer(b.player);changed();
                }
            }
        }catch(RuntimeException e){throw new IOException("Invalid member deletion journal",e);}
    }
    private static State read(Path file) throws IOException {
        try {
            State s = JSON.fromJson(Files.readString(file), State.class);
            if (s == null || s.schema != 1 || s.players == null || s.members == null || s.bindings == null
                    || s.deleted == null || s.codes == null || s.events == null || s.qqBots == null
                    || s.qqBotAliases == null || s.qqManualExcluded == null || s.nextCode < 36) throw new IOException("Invalid directory schema");
            for (var e : s.players.entrySet()) {
                UUID.fromString(e.getKey());
                if (!e.getKey().equals(e.getValue().id) || e.getValue().name == null) throw new IOException("Invalid player");
                Code code=s.codes.get(e.getValue().code);
                if(code==null||!code.active||!code.key.equals("mc/"+e.getKey()))throw new IOException("Invalid player selection map");
            }
            for(var e:s.members.entrySet()){
                Member m=e.getValue();Code code=s.codes.get(m.code);
                if(!validId(m.id)||!validId(m.app)||!validId(m.group)||m.name==null||m.aliases==null||!e.getKey().equals(new Scope(m.app,m.group).key(m.id))
                        ||code==null||!code.active||!e.getKey().equals(code.key))throw new IOException("Invalid QQ selection map");
            }
            for(var e:s.codes.entrySet())if(!e.getKey().matches("(?:MC|QQ)[A-Z0-9]+")||e.getValue()==null||e.getValue().key==null)throw new IOException("Invalid selection code");
            for(var e:s.bindings.entrySet())if(!e.getKey().equals(e.getValue().member)||!s.members.containsKey(e.getKey())
                    ||!s.players.containsKey(e.getValue().player)||e.getValue().sourceName==null)throw new IOException("Invalid binding");
            return s;
        } catch (RuntimeException e) { throw new IOException("Invalid directory JSON", e); }
    }
    private void changed() { dirty = true; revision++; }
    private String allocate(String prefix, String key) {
        if(data.nextCode>=reservedThrough){
            long nextReservation=Math.addExact(data.nextCode,256);
            try{atomicWrite(file.resolveSibling(file.getFileName()+".counter"),Long.toString(nextReservation));reservedThrough=nextReservation;}
            catch(IOException e){throw new java.io.UncheckedIOException("Cannot reserve identity codes",e);}
        }
        String value;
        do { value = prefix + Long.toString(data.nextCode++, 36).toUpperCase(Locale.ROOT); } while (data.codes.containsKey(value));
        Code code = new Code(); code.key = key; data.codes.put(value, code); changed(); return value;
    }
    private void retire(String code) { if (code != null && data.codes.containsKey(code)) data.codes.get(code).active = false; }
    private void rotatePlayer(String id) {
        Player p = data.players.get(id); if (p == null) return;
        retire(p.code); p.code = allocate("MC", "mc/" + id);
    }
    private void rotateMember(String key) {
        Member m = data.members.get(key); if (m == null) return;
        retire(m.code); m.code = allocate("QQ", key);
    }
    public synchronized void observePlayer(UUID uuid, String name, long time) {
        if (name == null || !name.matches("[A-Za-z0-9_]{1,16}")) return;
        String id = uuid.toString(); Player p = data.players.get(id);
        if (p == null) { p = new Player(); p.id = id; p.name = name; p.code = allocate("MC", "mc/" + id); data.players.put(id,p); }
        if (time >= p.updated && (!name.equals(p.name) || time - p.updated >= 60000)) {
            boolean wasFake = filter.excludes(uuid, p.name);
            p.name = name; p.updated = time; changed();
            if (wasFake && !filter.excludes(uuid, name)) {
                rotatePlayer(id); for(Binding b:data.bindings.values())if(id.equals(b.player))rotateMember(b.member);
            }
        }
    }
    /** Only authors/join events can re-establish presence after a leave event. */
    public synchronized boolean observeQq(Scope scope, String id, String name, long time, boolean presenceEvidence) {
        if (!validId(id) || !validId(scope.app()) || !validId(scope.group()) || qqExcluded(scope,id)) return false;
        String key = scope.key(id); Tombstone dead = data.deleted.get(key);
        if (dead != null && (dead.pending || time <= dead.time || !presenceEvidence && !data.members.containsKey(key))) return false;
        Member m = data.members.get(key);
        if (m == null) {
            m = new Member(); m.app = scope.app(); m.group = scope.group(); m.id = id;
            m.code = allocate("QQ", key); data.members.put(key, m); changed();
        }
        String safe = safeName(name);
        if (time >= m.updated && (!safe.isBlank() && !safe.equals(m.name) || time > m.updated)) {
            if (!safe.isBlank()) m.name = safe; m.updated = time; changed();
        }
        return true;
    }
    /** Identity evidence only: a human named like a bot remains a human. Retained across restarts. */
    public synchronized void markQqBot(Scope scope,String id) {
        if(validId(id)){
            if(data.qqBots.add(scope.key(id)))changed();
            if(data.qqBotAliases.add(scope.key("openid/"+id)))changed();
        }
    }
    /** Reconcile late READY/bot evidence with earlier observed member identifiers, in either order. */
    public synchronized List<String> observeQqIdentity(Scope scope,Map<?,?> user,boolean self) {
        Set<String> aliases=QqIdentity.aliases(user);String id=QqIdentity.memberId(user);
        Member member=data.members.get(scope.key(id));
        if(member!=null&&member.aliases.addAll(aliases))changed();
        Set<String> before=new HashSet<>(data.qqBots);
        boolean known=self||QqIdentity.bot(user)||aliases.stream().anyMatch(a->data.qqBotAliases.contains(scope.key(a)));
        if(!known)return List.of();
        if(known){
            for(String alias:aliases){
                if(data.qqBotAliases.add(scope.key(alias)))changed();
                if(alias.startsWith("openid/"))markQqBot(scope,alias.substring(7));
            }
        }
        // A member can have been learned before the bot's own identity arrived.
        boolean extended;
        do{
            extended=false;
            for(Member m:data.members.values())if(m.app.equals(scope.app())&&m.group.equals(scope.group())) {
                if(data.qqBots.contains(scope.key(m.id))||m.aliases.stream().anyMatch(a->data.qqBotAliases.contains(scope.key(a)))){
                    markQqBot(scope,m.id);
                    for(String a:m.aliases)if(data.qqBotAliases.add(scope.key(a))){extended=true;changed();}
                }
            }
        }while(extended);
        return data.qqBots.stream().filter(k->k.startsWith(scope.key(""))&&!before.contains(k))
                .map(k->k.substring(scope.key("").length())).toList();
    }
    public record QqEntry(String id,String code,String name,boolean automatic,boolean manual) {}
    public synchronized QqEntry qqEntry(Scope scope,String id){
        Member m=data.members.get(scope.key(id));
        return new QqEntry(id,m==null?"":m.code,m==null?"":m.name,data.qqBots.contains(scope.key(id)),data.qqManualExcluded.contains(scope.key(id)));
    }
    public synchronized List<String> qqAdminCandidates(Scope scope){
        return data.members.values().stream().filter(m->m.app.equals(scope.app())&&m.group.equals(scope.group()))
                .map(m->"@"+safeLabel(m.name)+"#"+m.code).sorted().toList();
    }
    public synchronized QqEntry selectQqAdmin(Scope scope,String selection){
        String value=selection.strip();List<Member> matches;
        if(value.startsWith("@")&&value.contains("#")){
            String code=value.substring(value.lastIndexOf('#')+1);Code c=data.codes.get(code);
            if(c==null||!c.active||!code.startsWith("QQ")||!c.key.startsWith(scope.key("")))throw new Invalid("请选择当前群的 QQ 候选编号");
            Member m=data.members.get(c.key);matches=m==null?List.of():List.of(m);
        }else if(value.startsWith("qq:")){
            Member m=data.members.get(scope.key(value.substring(3)));matches=m==null?List.of():List.of(m);
        }else{
            matches=data.members.values().stream().filter(m->m.app.equals(scope.app())&&m.group.equals(scope.group())&&m.name.equals(value)).toList();
        }
        if(matches.isEmpty())throw new Invalid("未找到已记录的 QQ 成员");
        if(matches.size()!=1)throw new Invalid("昵称重名，请用 Tab 选择带 QQ 编号的对象");
        return qqEntry(scope,matches.getFirst().id);
    }
    public synchronized QqEntry manualQqExclusion(Scope scope,String selection,boolean exclude)throws IOException{
        QqEntry selected=selectQqAdmin(scope,selection);String key=scope.key(selected.id());
        if(exclude?data.qqManualExcluded.add(key):data.qqManualExcluded.remove(key))changed();
        flush();return qqEntry(scope,selected.id());
    }
    public synchronized void configuredExclusions(Collection<String> identities){
        Set<String> next=new LinkedHashSet<>(identities);
        if(!next.equals(data.qqManualExcluded)){
            for(String key:data.qqManualExcluded)if(!next.contains(key))rotateMember(key);
            data.qqManualExcluded=next;changed();
        }
    }
    public synchronized String playerName(UUID uuid){Player p=data.players.get(uuid.toString());return p==null?null:p.name;}
    /** Legacy names may conservatively exclude fake targets, but never establish a UUID association. */
    public synchronized void unverifiedNames(Scope scope,Map<String,String> source){
        pendingNames.keySet().removeIf(k->k.startsWith(scope.key("")));
        source.forEach((id,name)->{if(name!=null)pendingNames.put(scope.key(id),name);});
    }
    /** Authentication supplies UUIDs directly; name reuse must never reassign an account. */
    public synchronized void syncVerifiedBindings(Scope scope,Map<String,UUID> verified){
        Map<String,Binding> next=new HashMap<>();
        verified.forEach((id,uuid)->{
            String key=scope.key(id);Player p=data.players.get(uuid.toString());
            if(p==null||!data.members.containsKey(key)||qqExcluded(scope,id))return;
            Binding b=new Binding();b.member=key;b.player=uuid.toString();b.sourceName=p.name;next.put(key,b);
        });
        Set<String> keys=new HashSet<>(next.keySet());
        data.bindings.keySet().stream().filter(k->k.startsWith(scope.key(""))).forEach(keys::add);
        Set<String> rotate=new HashSet<>();
        for(String key:keys){
            Binding before=data.bindings.get(key),after=next.get(key);
            if(!Objects.equals(before==null?null:before.player,after==null?null:after.player)){
                if(before!=null)rotate.add(before.player);if(after!=null)rotate.add(after.player);rotateMember(key);changed();
            }
        }
        data.bindings.keySet().removeIf(k->k.startsWith(scope.key("")));data.bindings.putAll(next);
        rotate.forEach(this::rotatePlayer);pendingNames.clear();
        if(!conflicts.isEmpty()){conflicts.clear();revision++;}
    }
    public synchronized long excludedQqCount(Scope scope){
        return data.members.values().stream().filter(m->m.app.equals(scope.app())&&m.group.equals(scope.group())&&qqExcluded(scope,m.id)).count();
    }
    public synchronized boolean qqExcluded(Scope scope,String id) {
        return "all".equals(id)||"everyone".equals(id)||"0".equals(id)||data.qqBots.contains(scope.key(id))||data.qqManualExcluded.contains(scope.key(id));
    }
    public static boolean validId(String s) { return s != null && s.matches("[A-Za-z0-9_-]{1,256}"); }
    public static String safeName(String value) {
        if (value == null) return ""; StringBuilder out = new StringBuilder();
        value.codePoints().limit(128).filter(cp -> cp != 167 && !Character.isISOControl(cp) && Character.getType(cp) != Character.FORMAT)
                .forEach(cp -> out.appendCodePoint(cp == '<' ? '＜' : cp == '>' ? '＞' : cp));
        return out.toString().strip();
    }
    public synchronized void online(Collection<UUID> ids) {
        Set<UUID> next = Set.copyOf(ids); if (!next.equals(online)) { online = next; revision++; }
    }
    public synchronized void reconfigure(FakePlayerFilter next) {
        FakePlayerFilter previous = filter; filter = next;
        for (Player p : data.players.values()) if (previous.excludes(UUID.fromString(p.id),p.name) && !next.excludes(UUID.fromString(p.id),p.name)) {
            rotatePlayer(p.id); for (Binding b : data.bindings.values()) if (p.id.equals(b.player)) rotateMember(b.member);
        }
        revision++;
    }
    /** Full upstream binding snapshot prevents duplicate-name first-match association. */
    public synchronized void syncBindings(Scope scope, Map<String,String> source) {
        String prefix = scope.key(""); Map<String, Binding> next = new HashMap<>();
        Set<String> nextConflicts = new HashSet<>();
        Map<String,List<String>> sourceNames = new HashMap<>();
        source.forEach((id,name) -> { if(name!=null&&!qqExcluded(scope,id)) sourceNames.computeIfAbsent(name.toLowerCase(Locale.ROOT),k->new ArrayList<>()).add(id); });
        sourceNames.forEach((name,ids) -> { if(ids.size()>1) {
            ids.forEach(id->nextConflicts.add(scope.key(id)));
            data.players.values().stream().filter(p->p.name.equalsIgnoreCase(name)).forEach(p->nextConflicts.add("mc/"+p.id));
        }});
        pendingNames.keySet().removeIf(k -> k.startsWith(prefix));
        for (var e : source.entrySet()) {
            String key = scope.key(e.getKey());
            if (qqExcluded(scope,e.getKey()) || !data.members.containsKey(key) || e.getValue() == null || (data.deleted.containsKey(key) && data.deleted.get(key).pending)) continue;
            Binding old = data.bindings.get(key); String player = null;
            if (old != null && e.getValue().equals(old.sourceName) && data.players.containsKey(old.player)) player = old.player;
            else {
                var matches = data.players.values().stream().filter(p -> p.name.equalsIgnoreCase(e.getValue())).toList();
                if (matches.size() == 1) player = matches.getFirst().id;
                else if (matches.size() > 1) nextConflicts.add(key);
            }
            if (player == null) { pendingNames.put(key,e.getValue()); continue; }
            Binding b = new Binding(); b.member = key; b.player = player; b.sourceName = e.getValue(); next.put(key,b);
        }
        Map<String,List<Binding>> byPlayer = new HashMap<>();
        next.values().forEach(b -> byPlayer.computeIfAbsent(b.player, k -> new ArrayList<>()).add(b));
        byPlayer.forEach((p, bs) -> { if (bs.size()>1) { nextConflicts.add("mc/"+p); bs.forEach(b -> nextConflicts.add(b.member)); } });
        next.keySet().removeIf(nextConflicts::contains);
        Set<String> keys = new HashSet<>(next.keySet());
        data.bindings.keySet().stream().filter(k -> k.startsWith(prefix)).forEach(keys::add);
        Set<String> playersToRotate = new HashSet<>();
        for (String key : keys) {
            Binding before = data.bindings.get(key), after = next.get(key);
            if (!Objects.equals(before == null ? null : before.sourceName,after == null ? null : after.sourceName)) changed();
            if (!Objects.equals(before == null ? null : before.player, after == null ? null : after.player)) {
                if (before != null) playersToRotate.add(before.player);
                if (after != null) playersToRotate.add(after.player);
                rotateMember(key); changed();
            }
        }
        data.bindings.keySet().removeIf(k -> k.startsWith(prefix)); data.bindings.putAll(next);
        playersToRotate.forEach(this::rotatePlayer);
        if (!conflicts.equals(nextConflicts)) { conflicts.clear(); conflicts.addAll(nextConflicts); revision++; }
    }
    public synchronized boolean removeQq(Scope scope, String id, long time) throws IOException {
        String key = scope.key(id); Member m = data.members.get(key); Tombstone old = data.deleted.get(key);
        if ((old != null && old.time >= time) || (m != null && m.updated > time)) return false;
        Tombstone tombstone = new Tombstone(); tombstone.time = time; tombstone.pending = true;
        data.deleted.put(key,tombstone); changed();
        atomicWrite(file.resolveSibling(file.getFileName()+".deletions"),JSON.toJson(data.deleted));
        flush(); // deletion intent survives a crash before upstream unbind
        if (m != null) { retire(m.code); data.members.remove(key); }
        Binding b = data.bindings.remove(key); if (b != null) rotatePlayer(b.player);
        pendingNames.remove(key); conflicts.remove(key); changed(); flush();return true;
    }
    public synchronized List<String> pendingDeletes(Scope scope) {
        String prefix = scope.key("");
        return data.deleted.entrySet().stream().filter(e -> e.getKey().startsWith(prefix) && e.getValue().pending)
                .map(e -> e.getKey().substring(prefix.length())).toList();
    }
    public synchronized void deletionCompleted(Scope scope, String id) throws IOException {
        String key = scope.key(id); Tombstone t = data.deleted.get(key); if (t == null) return;
        Member m = data.members.remove(key); if (m != null) retire(m.code);
        Binding b = data.bindings.remove(key); if (b != null) rotatePlayer(b.player);
        t.pending = false; changed();
        atomicWrite(file.resolveSibling(file.getFileName()+".deletions"),JSON.toJson(data.deleted));
        flush();
    }
    public synchronized String qqCode(Scope scope, String id) {
        Member m = data.members.get(scope.key(id)); return m == null || qqExcluded(scope,id) ? null : m.code;
    }
    /** Display lookup does not make an excluded member eligible for @ or binding. */
    public synchronized String qqName(Scope scope,String id) {
        Member m=data.members.get(scope.key(id));return m==null?"":m.name;
    }
    public synchronized String playerCode(UUID id) { Player p = data.players.get(id.toString()); return p == null ? null : p.code; }
    public synchronized Target resolve(Scope scope, String code) {
        Code selection = data.codes.get(code);
        if (selection == null) throw new Invalid("未知提及编号：" + code);
        Target target = resolveKey(scope,selection.key);
        // A known excluded identity must remain recognizable from an old candidate.
        if (!selection.active && !target.fake()) throw new Invalid("提及编号已失效，请重新选择：" + code);
        return target;
    }
    private Target resolveKey(Scope scope, String key) {
        if(key.startsWith(scope.key(""))&&qqExcluded(scope,key.substring(scope.key("").length())))
            throw new Invalid("不能提及群机器人或全体成员");
        if (conflicts.contains(key)) throw new Invalid("成员绑定冲突，请联系管理员");
        Player p = null; Member m = null; Binding binding = null;
        if (key.startsWith("mc/")) {
            p = data.players.get(key.substring(3)); if (p == null) throw new Invalid("玩家已失效");
            for (Binding b : data.bindings.values()) if (b.player.equals(p.id) && b.member.startsWith(scope.key(""))) {
                if (binding != null) throw new Invalid("成员绑定冲突"); binding=b; m=data.members.get(b.member);
            }
        } else {
            if (!key.startsWith(scope.key(""))) throw new Invalid("提及不属于当前群");
            m = data.members.get(key); if (m == null) throw new Invalid("成员已离群或编号失效");
            binding = data.bindings.get(key); if (binding != null) p = data.players.get(binding.player);
        }
        if(m!=null&&qqExcluded(scope,m.id))throw new Invalid("不能提及群机器人或全体成员");
        UUID uuid = p == null ? null : UUID.fromString(p.id); boolean isOnline = uuid != null && online.contains(uuid);
        boolean fake = p != null ? filter.excludes(uuid,p.name) : filter.excludes(null,pendingNames.get(key));
        if (!fake && p != null && m == null && !isOnline) throw new Invalid("成员不在线："+p.name);
        String qqName = m == null ? "" : m.name.isBlank() ? "成员#"+m.code : m.name;
        return new Target(p != null ? "mc/"+p.id : key,uuid,m == null ? null : m.id,
                p == null ? "" : p.name,qqName,isOnline,fake,binding == null ? "" : binding.member+"="+binding.player);
    }
    public synchronized List<String> candidates(Scope scope) {
        List<String> result = new ArrayList<>();
        Map<String,List<String>> index=selectionLabels(scope);
        for (Player p : data.players.values()) addCandidate(result,scope,p.code,p.name);
        for (Member m : data.members.values()) if (m.app.equals(scope.app()) && m.group.equals(scope.group()))
            addCandidate(result,scope,m.code,m.name.isBlank()?"昵称待更新":m.name);
        result.replaceAll(value->{
            int separator=value.lastIndexOf('#');String kind=value.substring(separator+1,separator+3);
            return index.getOrDefault(labelKey(kind,value.substring(1,separator)),List.of()).size()==1
                    ?value.substring(0,separator+1)+kind:value;
        });
        result.sort(String.CASE_INSENSITIVE_ORDER); return List.copyOf(result);
    }
    public synchronized List<String> candidates(Scope scope,String sort,String boundAliases) {
        List<String> values=new ArrayList<>(candidates(scope));
        values.removeIf(v->{Target t=resolve(scope,selectionCode(scope,v));return !t.association().isEmpty()&&!boundAliases.equals("BOTH")&&!v.substring(v.lastIndexOf('#')+1).startsWith(boundAliases);});
        Comparator<String> byName=String.CASE_INSENSITIVE_ORDER;
        if(!sort.equals("NAME"))values.sort(Comparator.<String>comparingInt(v->{Target t=resolve(scope,selectionCode(scope,v));return (sort.equals("ONLINE_FIRST")?t.online():!t.association().isEmpty())?0:1;}).thenComparing(byName));
        return List.copyOf(values);
    }
    private static String labelKey(String kind,String label) {
        return kind+"/"+(kind.equals("MC")?label.toLowerCase(Locale.ROOT):label);
    }
    /** Include current known names, even offline/filtered players, so a short alias never guesses between identities. */
    private Map<String,List<String>> selectionLabels(Scope scope) {
        if(labelsRevision==revision&&scope.equals(labelsScope))return labels;
        Map<String,List<String>> next=new HashMap<>();
        for(Player p:data.players.values())indexLabel(next,"MC",p.name,p.code);
        for(Member m:data.members.values())if(m.app.equals(scope.app())&&m.group.equals(scope.group())&&!qqExcluded(scope,m.id))
            indexLabel(next,"QQ",m.name.isBlank()?"昵称待更新":m.name,m.code);
        labels=next;labelsRevision=revision;labelsScope=scope;return labels;
    }
    private void indexLabel(Map<String,List<String>> index,String kind,String label,String code) {
        Code entry=data.codes.get(code);
        if(entry!=null&&entry.active)index.computeIfAbsent(labelKey(kind,safeLabel(label)),ignored->new ArrayList<>()).add(code);
    }
    /** Resolve visible short syntax once; callers retain the returned stable code during asynchronous publication. */
    public synchronized String selectionCode(Scope scope,String token) {
        int separator=token.lastIndexOf('#');
        if(!token.startsWith("@")||separator<2)throw new Invalid("无效提及目标，请用 Tab 重新选择");
        String code=token.substring(separator+1);
        if(code.equals("MC")||code.equals("QQ")) {
            List<String> matches=selectionLabels(scope).getOrDefault(labelKey(code,token.substring(1,separator)),List.of());
            if(matches.isEmpty())throw new Invalid("未找到成员，请用 Tab 重新选择："+safeName(token));
            if(matches.size()!=1)throw new Invalid("成员重名，请用 Tab 选择带编号的对象");
            code=matches.getFirst();
        }
        resolve(scope,code);return code;
    }
    public synchronized List<String> records(){
        List<String> out=new ArrayList<>();
        for(Player p:data.players.values())out.add("mc/"+p.id+" "+p.name+" #"+p.code+" online="+online.contains(UUID.fromString(p.id)));
        for(Member m:data.members.values())out.add(new Scope(m.app,m.group).key(m.id)+" "+safeName(m.name)+" #"+m.code);
        return List.copyOf(out);
    }
    /** Administrative/history removal only; never changes a binding or reuses a code. */
    public synchronized boolean forget(String key,Set<String> protectedKeys)throws IOException{
        boolean removed=forgetRecord(key,protectedKeys);if(removed)flush();return removed;
    }
    private boolean forgetRecord(String key,Set<String> protectedKeys){
        if(protectedKeys.contains(key)||data.bindings.containsKey(key)||pendingNames.containsKey(key))throw new Invalid("成员仍有绑定或待同步关系，请先处理绑定");
        if(key.startsWith("mc/")){
            String id=key.substring(3);UUID uuid;try{uuid=UUID.fromString(id);}catch(RuntimeException e){throw new Invalid("无效 UUID");}
            if(online.contains(uuid)||data.bindings.values().stream().anyMatch(b->b.player.equals(id)))throw new Invalid("在线或已绑定玩家不可删除");
            Player p=data.players.get(id);if(p==null)return false;
            if(pendingNames.values().stream().anyMatch(n->n.equalsIgnoreCase(p.name)))throw new Invalid("玩家仍有待同步的名字绑定");
            data.players.remove(id);retire(p.code);
        }else{Member m=data.members.remove(key);if(m==null)return false;retire(m.code);}
        changed();return true;
    }
    public synchronized int pruneInactive(int days,long now,Set<String> protectedKeys)throws IOException{
        if(days<=0)return 0;long before=now-days*86400000L;List<String> keys=new ArrayList<>();
        for(Player p:data.players.values())if(p.updated<before)keys.add("mc/"+p.id);
        for(Member m:data.members.values())if(m.updated<before)keys.add(new Scope(m.app,m.group).key(m.id));
        int count=0;for(String key:keys)try{if(forgetRecord(key,protectedKeys))count++;}catch(Invalid ignored){}if(count>0)flush();return count;
    }
    private void addCandidate(List<String> out, Scope scope, String code, String label) {
        try { if (!resolve(scope,code).fake()) out.add("@"+safeLabel(label)+"#"+code); } catch (Invalid ignored) {}
    }
    public static String safeLabel(String label) {
        StringBuilder out = new StringBuilder();
        safeName(label).codePoints().limit(48).forEach(cp -> {
            if (Character.isWhitespace(cp) || "@#%\"\\<>".indexOf(cp)>=0) out.append('%').append(Integer.toHexString(cp).toUpperCase(Locale.ROOT)).append('%');
            else out.appendCodePoint(cp);
        });
        return out.isEmpty()?"成员":out.toString();
    }
    public synchronized String select(Scope scope, String name) {
        return select(scope,name,false);
    }
    public synchronized String select(Scope scope, String name, boolean literalName) {
        if (!literalName && name.matches("@[^\\s@#<>]+#(?:MC|QQ)[A-Za-z0-9_]*")) {
            String code=selectionCode(scope,name);return name.substring(0,name.lastIndexOf('#')+1)+code;
        }
        String label = !literalName && name.startsWith("@")?name.substring(1):name; List<String> matches=new ArrayList<>();
        if(!literalName&&name.startsWith("qq:")&&qqExcluded(scope,name.substring(3)))throw new Invalid("不能提及群机器人或全体成员");
        for (Player p:data.players.values()) if (p.name.equalsIgnoreCase(label) || !literalName && name.equals("mc:"+p.id))
            matches.add("@"+safeLabel(p.name)+"#"+p.code);
        for (Member m:data.members.values()) if (m.app.equals(scope.app())&&m.group.equals(scope.group())&&!qqExcluded(scope,m.id)&&(m.name.equals(label)||!literalName && name.equals("qq:"+m.id)))
            matches.add("@"+safeLabel(m.name)+"#"+m.code);
        if (matches.isEmpty()) throw new Invalid("未找到成员："+safeName(name));
        Set<String> identities=new HashSet<>(); for(String s:matches) identities.add(resolve(scope,s.substring(s.lastIndexOf('#')+1)).identity());
        if(identities.size()!=1) throw new Invalid("成员重名，请用 Tab 选择带编号的对象");
        return matches.getFirst();
    }
    public synchronized boolean excluded(UUID id, String name) { return filter.excludes(id,name); }
    public synchronized void bindingChanged(Scope scope,String openid){
        String key=scope.key(openid);Binding old=data.bindings.remove(key);
        if(old!=null)rotatePlayer(old.player);rotateMember(key);changed();
    }
    /** Stable gateway event IDs survive reconnects/restarts; retain only bounded deletion metadata. */
    public synchronized boolean acceptEvent(Scope scope,String eventId,long now){
        if(eventId==null||eventId.isBlank()||eventId.length()>512)return true;
        String key=scope.key("event/"+eventId);if(data.events.containsKey(key))return false;
        data.events.put(key,now);while(data.events.size()>8192)data.events.remove(data.events.keySet().iterator().next());changed();return true;
    }
    public synchronized long revision() { return revision; }
    public synchronized String status() { return "members: MC="+data.players.size()+", QQ="+data.members.size()+", bindings="+data.bindings.size()+", conflicts="+conflicts.size(); }
    public synchronized void flush() throws IOException {
        if (!dirty) return; Files.createDirectories(file.getParent());
        Path tmp=file.resolveSibling(file.getFileName()+".tmp");
        Files.writeString(tmp,JSON.toJson(data));
        if(Files.exists(file)) Files.copy(file,file.resolveSibling(file.getFileName()+".bak"),StandardCopyOption.REPLACE_EXISTING);
        try { Files.move(tmp,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING); }
        catch(AtomicMoveNotSupportedException e) { Files.move(tmp,file,StandardCopyOption.REPLACE_EXISTING); }
        dirty=false;
    }
    private static void atomicWrite(Path target,String text)throws IOException{
        Files.createDirectories(target.getParent());Path tmp=target.resolveSibling(target.getFileName()+".tmp");Files.writeString(tmp,text);
        try{Files.move(tmp,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
        catch(AtomicMoveNotSupportedException e){Files.move(tmp,target,StandardCopyOption.REPLACE_EXISTING);}
    }
}
