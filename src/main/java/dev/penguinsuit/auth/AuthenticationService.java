package dev.penguinsuit.auth;

import com.huhobot.penguin.PenguinServerMod;
import com.huhobot.penguin.qq.GroupMessage;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import dev.penguinsuit.PenguinSuit;
import dev.penguinsuit.config.BridgeConfig;
import dev.penguinsuit.mention.*;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.commands.*;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;

/** Server-thread adapter. All identity inputs come from authenticated connections/events. */
public final class AuthenticationService {
    private final MinecraftServer server;
    private final MentionService mentions;
    private final BindingLedger ledger;
    private BridgeConfig config;
    private MemberDirectory.Scope lastScope;
    private final Map<UUID,GroupMessage> receipts=new HashMap<>();
    private final Set<String> reportedConflicts=new HashSet<>();
    private final AtomicInteger queued=new AtomicInteger();
    private boolean reconciling;
    public AuthenticationService(MinecraftServer server,MentionService mentions,BridgeConfig config,Path file)throws IOException{
        this.server=server;this.mentions=mentions;this.config=config;
        ledger=new BindingLedger(file,new BindingLedger.Mirror(){
            public boolean attached(MemberDirectory.Scope scope){try{return scope.equals(mentions.scope());}catch(MemberDirectory.Invalid e){return false;}}
            public Map<String,String> snapshot(MemberDirectory.Scope scope){return PenguinBindings.snapshot(PenguinServerMod.INSTANCE.getState(),scope.group());}
            public void change(MemberDirectory.Scope scope,String id,String before,String next)throws IOException{
                if(!scope.equals(mentions.scope()))throw new IOException("Authentication scope changed");
                PenguinBindings.change(PenguinServerMod.INSTANCE.getState(),scope.group(),id,before,next);
            }
        });
        ledger.configure(config.authentication);
    }
    public BindingLedger ledger(){return ledger;}
    public void reconfigure(BridgeConfig next){config=next;ledger.configure(next.authentication);ledger.cancelAll();receipts.clear();}
    public void close(){ledger.cancelAll();receipts.clear();}
    public void invalidateScope(){ledger.cancelAll();receipts.clear();lastScope=null;}
    public void disconnect(UUID uuid){ledger.cancel(uuid);receipts.remove(uuid);}
    private boolean eligible(UUID uuid){var p=server.getPlayerList().getPlayer(uuid);return p!=null&&!mentions.directory().excluded(uuid,p.getGameProfile().name());}
    private boolean eligible(MemberDirectory.Scope scope,String id){return scope.equals(mentions.scope())&&!mentions.directory().qqExcluded(scope,id);}
    public Map<String,UUID> refresh(MemberDirectory.Scope scope)throws IOException{
        if(reconciling)return ledger.verified(scope);
        reconciling=true;
        try{
            if(!Objects.equals(lastScope,scope)){ledger.cancelAll();receipts.clear();lastScope=scope;}
            ledger.retainEligible(this::eligible,this::eligible);
            receipts.keySet().removeIf(id->!eligible(id));
            Map<UUID,String> names=new HashMap<>();
            for(var record:ledger.records()){
                String name=mentions.directory().playerName(record.player());if(name!=null)names.put(record.player(),name);
            }
            ledger.reconcile(scope,names);
            for(var b:ledger.records())if(b.phase()==BindingLedger.Phase.CONFLICT&&reportedConflicts.add(b.request()))
                PenguinSuit.LOG.warn("[binding] suspended conflict uuid={} binding={}; use qqbindadmin status/unlink",b.player(),b.request());
            return ledger.verified(scope);
        }finally{reconciling=false;}
    }
    public void departed(MemberDirectory.Scope scope,String id)throws IOException {ledger.cancelMember(scope,id);ledger.delete(ledger.member(scope,id));}
    public void externalChange(MemberDirectory.Scope scope,String id){
        try{ledger.externalChange(scope,id);}catch(IOException e){error(e);}
    }
    private void requireGame(ServerPlayer player){
        if(!config.enabled||!config.authenticationEnabled)throw new BindingLedger.Rejected("双向认证当前未启用");
        if(!eligible(player.getUUID()))throw new BindingLedger.Rejected("假人或已离线账号不能参与认证");
        mentions.refresh();
    }
    private static String status(BindingLedger.Binding b){return b==null?"尚未双向认证。游戏内 /qqbind start 开始。":"绑定状态："+b.phase()+"，游戏："+b.gameName()+"，QQ："+MemberDirectory.safeName(b.qqName());}
    private void tell(UUID id,String text){var p=server.getPlayerList().getPlayer(id);if(p!=null)p.sendSystemMessage(Component.literal(dev.penguinsuit.config.RuntimeSettings.text(config.messages.authentication,"message",text)));}
    private void tellPairing(UUID id,String code){
        var p=server.getPlayerList().getPlayer(id);if(p==null)return;
        String command="/绑定认证 "+code,button="[复制认领命令]";
        String text=dev.penguinsuit.config.RuntimeSettings.text(config.messages.authentication,"message",
                "请在指定 QQ 群发送 "+command+"（"+config.authentication.codeTtlSeconds+" 秒内有效）。点击配对码可复制；"+button+"。随后回到游戏核对认领者并确认。");
        var result=Component.empty();int cursor=0;
        var matcher=java.util.regex.Pattern.compile(java.util.regex.Pattern.quote(code)+"|"+java.util.regex.Pattern.quote(button)).matcher(text);
        while(matcher.find()){
            result.append(Component.literal(text.substring(cursor,matcher.start())));
            boolean codeOnly=matcher.group().equals(code);
            result.append(Component.literal(matcher.group()).withStyle(style->style
                    .withColor(net.minecraft.ChatFormatting.AQUA).withUnderlined(true)
                    .withClickEvent(new ClickEvent.CopyToClipboard(codeOnly?code:command))
                    .withHoverEvent(new HoverEvent.ShowText(Component.literal(codeOnly?"点击复制配对码":"点击复制完整认领命令，到 QQ 群粘贴发送")))));
            cursor=matcher.end();
        }
        result.append(Component.literal(text.substring(cursor)));p.sendSystemMessage(result);
    }
    private void audit(String action,BindingLedger.Binding b){if(b==null)return;if(dev.penguinsuit.config.BridgeLog.detailed("authentication"))PenguinSuit.LOG.info("[binding] {} uuid={} record={} phase={}",action,b.player(),b.request(),b.phase());else PenguinSuit.LOG.info("[binding] {} phase={}",action,b.phase());}
    private void reply(GroupMessage message,String text){
        var client=PenguinServerMod.INSTANCE.getQqClient();
        if(client!=null&&mentions.isScope(message.getGroupId()))client.sendGroupMessage(message.getGroupId(),dev.penguinsuit.config.RuntimeSettings.text(config.messages.authentication,"message",text),message.getId());
    }
    private void completed(BindingLedger.Binding b,String text){
        tell(b.player(),text);var source=receipts.remove(b.player());
        if(source!=null)reply(source,text);audit(text,b);mentions.refresh();
    }
    public int game(ServerPlayer player,String action,String argument){
        try{
            requireGame(player);UUID uuid=player.getUUID();var scope=mentions.scope();
            switch(action){
                case "start"->{
                    String code=ledger.start(uuid,player.getGameProfile().name(),scope);receipts.remove(uuid);
                    tellPairing(uuid,code);
                }
                case "status"->tell(uuid,status(ledger.player(uuid)));
                case "cancel"->{ledger.cancel(uuid);var receipt=receipts.remove(uuid);if(receipt!=null)reply(receipt,"认证申请已取消。");tell(uuid,"已取消未完成的认证/解绑申请。");}
                case "confirm"->{
                    BindingLedger.Binding b=ledger.confirm(uuid,scope);
                    if(b.phase()!=BindingLedger.Phase.COMMITTED)throw new BindingLedger.Rejected("绑定冲突，未启用关联，请联系管理员");
                    completed(b,"双向认证成功："+b.gameName()+" ↔ "+MemberDirectory.safeName(b.qqName()));
                }
                case "unbind"->{String code=ledger.beginRemoval("mc/"+uuid,ledger.player(uuid));tell(uuid,"解除自己的绑定请在 "+config.authentication.codeTtlSeconds+" 秒内执行 /qqbind unbind-confirm "+code);}
                case "unbind-confirm"->{
                    var b=ledger.confirmRemoval("mc/"+uuid,argument);completed(b,"已解除双向绑定（不改变白名单）。");
                    var client=PenguinServerMod.INSTANCE.getQqClient();if(client!=null&&b.scope().equals(scope))client.sendGroupMessage(scope.group(),"[绑定] "+b.gameName()+" 已解除与 "+MemberDirectory.safeName(b.qqName())+" 的关联。",null);
                }
                default->throw new BindingLedger.Rejected("未知认证操作");
            }
            return 1;
        }catch(BindingLedger.Rejected|MemberDirectory.Invalid e){tell(player.getUUID(),"操作失败："+e.getMessage());return 0;}
        catch(Exception e){error(e);tell(player.getUUID(),"操作未完成，请查看 /qqbind status 并联系管理员；不要据此认为绑定已成功。");return 0;}
    }
    public static boolean isCommand(String normalized){
        String name=normalized.strip().split("\\s+",2)[0];
        return Set.of("/绑定认证","/绑定状态","/绑定解绑","/绑定解绑确认","/取消认证").contains(name);
    }
    /** Called after upstream group restriction and gateway duplicate/bot checks, before native command logging. */
    public boolean incoming(GroupMessage message,String normalized,Object source){
        if(!isCommand(normalized))return false;
        if(source!=PenguinServerMod.INSTANCE.getCommandHandler()||!mentions.isScope(message.getGroupId()))return true;
        if(queued.incrementAndGet()>64){queued.decrementAndGet();return true;}
        var scope=mentions.scope();
        mentions.execute(()->{
            try{
                if(!scope.equals(mentions.scope())||source!=PenguinServerMod.INSTANCE.getCommandHandler())return;
                if(!config.enabled||!config.authenticationEnabled){reply(message,"双向认证未启用。");return;}
                if(!MemberDirectory.validId(message.getUserId())||!eligible(scope,message.getUserId())
                        ||config.blockedGroups.contains(message.getGroupId())||config.blockedUsers.contains(message.getUserId()))return;
                mentions.refresh();String[] parts=normalized.strip().split("\\s+",2);String argument=parts.length==2?parts[1].strip():"";
                String actor="qq/"+scope.key(message.getUserId());
                switch(parts[0]){
                    case "/绑定认证"->{
                        if(!argument.matches("[A-Za-z2-9]{12}"))throw new BindingLedger.Rejected("请先在游戏输入 /qqbind start，再发送 /绑定认证 配对码");
                        var claim=ledger.claim(scope,message.getUserId(),MemberDirectory.safeName(message.getUsername()),argument);
                        if(!eligible(claim.player())){ledger.cancel(claim.player());throw new BindingLedger.Rejected("申请玩家已离线或被过滤");}
                        receipts.put(claim.player(),message);
                        String code=mentions.directory().qqCode(scope,claim.member());
                        tell(claim.player(),"QQ「"+claim.qqName()+"」#"+code+" 已认领。确认是本人 QQ 后执行 /qqbind confirm 完成绑定；对象不对请 /qqbind start 重新生成配对码，或 /qqbind cancel 取消。");
                        reply(message,"认领成功，等待游戏账号本人最终确认。");
                    }
                    case "/绑定状态"->reply(message,status(ledger.member(scope,message.getUserId())));
                    case "/绑定解绑"->{String code=ledger.beginRemoval(actor,ledger.member(scope,message.getUserId()));reply(message,"仅解除当前发言 QQ 的绑定；"+config.authentication.codeTtlSeconds+" 秒内由同一 QQ 发送 /绑定解绑确认 "+code);}
                    case "/绑定解绑确认"->{var b=ledger.confirmRemoval(actor,argument);completed(b,"已解除双向绑定（不改变白名单）。");reply(message,"已解除双向绑定。");}
                    case "/取消认证"->{ledger.cancelMember(scope,message.getUserId());reply(message,"已取消当前 QQ 的未完成申请。");}
                    default->throw new AssertionError();
                }
            }catch(BindingLedger.Rejected|MemberDirectory.Invalid e){reply(message,"操作失败："+e.getMessage());}
            catch(Exception e){error(e);reply(message,"操作未完成，请查看绑定状态并联系管理员。");}
            finally{queued.decrementAndGet();}
        });return true;
    }
    private static void error(Exception e){PenguinSuit.LOG.error("[binding] operation failed: {} (credentials omitted)",e.getClass().getSimpleName());}
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher){
        var root=Commands.literal("qqbind").requires(s->s.getPlayer()!=null).executes(c->run(c.getSource(),"status",""));
        for(String action:List.of("start","status","cancel","unbind","confirm"))root.then(Commands.literal(action).executes(c->run(c.getSource(),action,"")));
        root.then(Commands.literal("unbind-confirm").then(Commands.argument("code",StringArgumentType.word()).executes(c->run(c.getSource(),"unbind-confirm",StringArgumentType.getString(c,"code")))));
        dispatcher.register(root);
        dispatcher.register(Commands.literal("qqbindadmin").requires(s->s.permissions().hasPermission(Permissions.COMMANDS_ADMIN))
            .then(Commands.literal("status").executes(c->{var a=current();if(a==null)return 0;for(var b:a.ledger.records())c.getSource().sendSuccess(()->Component.literal(b.player()+" "+status(b)+" record="+b.request()),false);return 1;}))
            .then(Commands.literal("unlink").then(Commands.argument("uuid",StringArgumentType.word()).then(Commands.literal("confirm").executes(c->{
                var a=current();if(a==null)return 0;
                try{UUID id=UUID.fromString(StringArgumentType.getString(c,"uuid"));var b=a.ledger.player(id);a.ledger.delete(b);a.completedIfPresent(b,"管理员已解除绑定。");
                    PenguinSuit.LOG.info("[binding] admin-unlink actor={} uuid={}",c.getSource().getTextName(),id);c.getSource().sendSuccess(()->Component.literal("认证记录已清理；不改变白名单。"),false);return 1;
                }catch(Exception e){c.getSource().sendFailure(Component.literal("清理失败，请查看认证状态与日志。"));error(e);return 0;}
            }))))
            .then(Commands.literal("legacy-unbind").then(Commands.argument("openid",StringArgumentType.word()).then(Commands.literal("confirm").executes(c->{
                var a=current();if(a==null)return 0;
                try{String id=StringArgumentType.getString(c,"openid");if(!MemberDirectory.validId(id))throw new IllegalArgumentException();var scope=a.mentions.scope();
                    if(a.ledger.member(scope,id)!=null)throw new BindingLedger.Rejected("请先通过 unlink 清理认证记录");
                    var state=PenguinServerMod.INSTANCE.getState();String before=PenguinBindings.snapshot(state,scope.group()).get(id);
                    PenguinBindings.change(state,scope.group(),id,before,null);a.mentions.refresh();
                    PenguinSuit.LOG.info("[binding] legacy-unbind actor={} scope={}",c.getSource().getTextName(),scope.key(id));
                    c.getSource().sendSuccess(()->Component.literal("旧名字绑定已解除；白名单保留。"),false);return 1;
                }catch(Exception e){c.getSource().sendFailure(Component.literal("清理失败：有认证记录时先使用 unlink，详情检查日志。"));error(e);return 0;}
            })))));
    }
    private void completedIfPresent(BindingLedger.Binding b,String text){if(b!=null)completed(b,text);}
    private static AuthenticationService current(){var m=PenguinSuit.mentions();return m==null?null:m.authentication();}
    private static int run(CommandSourceStack source,String action,String code)throws com.mojang.brigadier.exceptions.CommandSyntaxException{
        var a=current();if(a==null){source.sendFailure(Component.literal("认证服务未运行"));return 0;}return a.game(source.getPlayerOrException(),action,code);
    }
}
