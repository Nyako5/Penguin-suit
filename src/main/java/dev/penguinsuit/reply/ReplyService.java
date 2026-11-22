package dev.penguinsuit.reply;

import com.google.gson.*;
import com.huhobot.penguin.PenguinServerMod;
import com.huhobot.penguin.config.PenguinConfig;
import com.huhobot.penguin.filter.TextFilter;
import com.huhobot.penguin.qq.QQClient;
import dev.errnick.chatremastered.network.packet.*;
import dev.errnick.chatremastered.server.ChatRemasteredState;
import dev.errnick.chatremastered.server.tgbridge.*;
import dev.errnick.chatremastered.tcp.ImageTcpServer;
import dev.penguinsuit.PenguinSuit;
import dev.penguinsuit.bridge.*;
import dev.penguinsuit.config.BridgeConfig;
import dev.penguinsuit.mention.*;
import dev.penguinsuit.mixin.QQClientAccess;
import dev.penguinsuit.reverse.*;
import java.io.*;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import kotlin.Unit;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.ChatVisiblity;

/** Server-only reply bridge for the pinned CR protocol. All identity decisions run on the server. */
public final class ReplyService implements AutoCloseable {
    public record Quote(String sender,String text,String image) {public static final Quote EMPTY=new Quote("","","");}
    public record Plan(ReplyLedger.Entry entry,ReplyLedger.Entry target,MentionMessage source,MentionMessage.Rendered rendered,
                       PenguinConfig config,QQClient client,boolean forward){}
    public record Inbound(ReplyLedger.Entry entry,Quote quote,MentionMessage.Rendered rendered,boolean reply){}
    private static final ThreadLocal<Plan> GAME=new ThreadLocal<>();
    private final MinecraftServer server;
    private final MentionService mentions;
    private final ReplyLedger ledger;
    private final ScheduledExecutorService maintenance;
    private final Map<String,Plan> imagePlans=new ConcurrentHashMap<>();
    private volatile BridgeConfig config;
    private volatile boolean active=true;
    public ReplyService(MinecraftServer server,MentionService mentions,BridgeConfig config,Path file)throws IOException{
        this.server=server;this.mentions=mentions;this.config=config;
        ledger=new ReplyLedger(file,config.replyMaxEntries,config.replyMaxBytes,config.replyRetentionMinutes*60000L);
        maintenance=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"penguin-reply-storage");t.setDaemon(true);return t;});
        maintenance.scheduleWithFixedDelay(()->{try{ledger.prune(System.currentTimeMillis());ledger.flush();
            imagePlans.entrySet().removeIf(e->System.currentTimeMillis()-e.getValue().entry().created()>600000);
        }catch(Exception e){PenguinSuit.LOG.error("[replies] index save failed: {}",e.getClass().getSimpleName());}},2,2,TimeUnit.SECONDS);
    }
    public boolean enabled(){return active&&config.enabled&&config.repliesEnabled;}
    public QqReplyData captureIncoming(Map<?,?> event){return QqReplyData.parse(event,dev.penguinsuit.config.BridgeLog.replies(config));}
    public ReplyLedger ledger(){return ledger;}
    public ReplyLedger.Scope scope(){var s=mentions.scope();return new ReplyLedger.Scope(s.app(),s.group());}
    public boolean handlesGame(ServerPlayer player){
        if(!enabled()||player==null||!mentions.publicChannel(player))return false;
        try{scope();return true;}catch(MemberDirectory.Invalid e){return false;}
    }
    public static boolean customPublication(){return GAME.get()!=null;}
    public String status(){return "replies "+(enabled()?"enabled":"disabled")+", records="+ledger.size()+", indexBytes="+ledger.bytes();}
    public void reconfigure(BridgeConfig next){config=next;ledger.configure(next.replyMaxEntries,next.replyMaxBytes,next.replyRetentionMinutes*60000L);}
    private static MentionMessage.Rendered literal(String text){return new MentionMessage.Rendered(List.of(new MentionMessage.Part(text,null)),Set.of(),Set.of(),Map.of());}
    private List<ServerPlayer> recipients(){return server.getPlayerList().getPlayers().stream()
            .filter(p->p.getChatVisibility()==ChatVisiblity.FULL&&!TgMuteCache.isMuted(p.getUUID())).toList();}
    private static boolean replyCapable(ServerPlayer p){return ChatRemasteredState.hasModInstalled(p.getUUID())&&ServerPlayNetworking.canSend(p,ReplyChatPacket.TYPE);}
    private boolean forwards(String text,PenguinConfig cfg){return enabled()&&config.gameToQqEnabled&&config.forwarding.gameToQq&&config.forwarding.gameText&&cfg.getChatPostChat()
            &&!config.blockedGroups.contains(scope().group())&&text.startsWith(cfg.getChatStartWith());}
    private ReplyLedger.Author gameAuthor(ServerPlayer p){return new ReplyLedger.Author("MC",p.getUUID(),"",p.getGameProfile().name());}
    private void debug(String action,ReplyLedger.Entry entry,String detail){if(dev.penguinsuit.config.BridgeLog.replies(config))
        PenguinSuit.LOG.info("[message-index] {} message={} replyTo={} {}",action,entry==null?"-":entry.id(),entry==null||entry.replyTo()==null?"-":entry.replyTo(),detail);}
    private static String refTag(String ref){
        if(ref==null||ref.isBlank())return "missing";
        String kind=ref.startsWith("REFIDX_")?"REFIDX":ref.startsWith("TMP_")?"TMP":"other";
        return kind+"/"+UUID.nameUUIDFromBytes(ref.getBytes(StandardCharsets.UTF_8)).toString().substring(0,8);
    }
    public void reject(ServerPlayer player,Exception failure){
        String reason=failure.getMessage()==null?failure.getClass().getSimpleName():failure.getMessage();
        PenguinSuit.LOG.warn("[replies] MC rejected player={} reason={}",player.getUUID(),MemberDirectory.safeName(reason));
        String message=failure instanceof ReplyLedger.Invalid?"发送失败，无法确定原消息":"发送失败，"+MemberDirectory.safeName(reason);
        player.sendSystemMessage(Component.literal("[Penguin suit] "+message).withStyle(ChatFormatting.RED));
    }
    private MemberDirectory.Target target(ReplyLedger.Entry e){
        var d=mentions.directory();var s=mentions.scope();String code=e.author().player()!=null?d.playerCode(e.author().player()):d.qqCode(s,e.author().openid());
        if(code==null)return null;try{return d.resolve(s,code);}catch(MemberDirectory.Invalid invalid){return null;}
    }
    private boolean sameAuthor(ReplyLedger.Entry target,UUID player,String openid){
        if(player!=null&&player.equals(target.author().player()))return true;
        if(openid!=null&&!openid.isBlank()&&openid.equals(target.author().openid()))return true;
        var t=target(target);return t!=null&&(player!=null&&player.equals(t.player())||openid!=null&&!openid.isBlank()&&openid.equals(t.openid()));
    }
    /** Only NEW body mentions participate; quoted @ never enters this renderer. */
    private MentionMessage.Rendered autoMention(MentionMessage.Rendered body,ReplyLedger.Entry quoted,UUID player,String openid){
        if(!mentions.mentionsEnabled()||(player==null&&!config.forwarding.qqMentions)||quoted==null||sameAuthor(quoted,player,openid))return body;
        var t=target(quoted);
        if(t!=null&&t.fake())return body;
        if(t!=null&&body.parts().stream().anyMatch(p->p.target()!=null&&p.target().identity().equals(t.identity())))return body;
        if(t==null&&quoted.author().origin().equals("QQ")&&mentions.directory().qqExcluded(mentions.scope(),quoted.author().openid()))return body;
        if(t==null&&quoted.author().player()!=null&&mentions.directory().excluded(quoted.author().player(),quoted.author().name()))return body;
        String label=t==null?quoted.author().name():t.gameLabel();
        List<MentionMessage.Part> parts=new ArrayList<>();parts.add(new MentionMessage.Part("@"+MemberDirectory.safeName(label),t));
        if(!body.plain().isBlank())parts.add(new MentionMessage.Part(" ",null));parts.addAll(body.parts());
        Set<UUID> sounds=new HashSet<>(body.notifications());if(t!=null&&t.online()&&t.player()!=null)sounds.add(t.player());
        Map<String,String> associations=new HashMap<>(body.associations());if(t!=null)associations.put("reply/"+t.identity(),t.association());
        return new MentionMessage.Rendered(List.copyOf(parts),Set.copyOf(sounds),body.filtered(),Map.copyOf(associations));
    }
    private Quote quote(ReplyLedger.Entry target){
        if(target==null)return Quote.EMPTY;String image=target.images().isEmpty()?"":target.images().getFirst();
        boolean expired=!image.isEmpty()&&!ImageTcpServer.hasCached(image);
        String text=target.text()+(expired?" [图片已过期]":!image.isEmpty()&&target.text().isBlank()?"[图片]":"");
        text=HiddenReplyId.bounded(text,256);
        return new Quote(HiddenReplyId.bounded(target.display(),64),HiddenReplyId.decorate(text,target.id()),expired?"":image);
    }
    private Component prefix(String template,String name,Component body){
        String t=template.replace("{name}",MemberDirectory.safeName(name));int index=t.indexOf("{message}");
        return index<0?Component.literal(t).append(body):Component.literal(t.substring(0,index)).append(body).append(t.substring(index+9));
    }
    public Component fallback(Inbound in,MessageSnapshot message,String suffix){
        MutableComponent body=Component.empty();if(in.reply())body.append(Component.literal("[回复 "+in.quote().sender()+"："+HiddenReplyId.bounded(in.quote().text(),100)+"] ").withStyle(ChatFormatting.GRAY));
        body.append(QqChatStyle.component(in.rendered())).append(suffix);
        return prefix(PenguinServerMod.INSTANCE.getConfig().getChatFromGroup(),message.name(),body);
    }
    private void sendReply(ServerPlayer player,String sender,Component senderComponent,String body,Quote quote,Component fallback){
        if(replyCapable(player)&&body.length()<=256)ServerPlayNetworking.send(player,new ReplyChatPacket(sender,senderComponent,body,quote.sender(),quote.text(),quote.image()));
        else player.sendSystemMessage(fallback);
    }
    public boolean incomingText(MessageSnapshot message,String filtered,PenguinConfig cfg,Object handler){
        if(!enabled()||!mentions.isScope(message.group()))return false;
        mentions.execute(()->{
            try{
                if(!incomingAllowed(message,cfg,handler))return;
                Inbound in=prepareIncoming(message,filtered,List.of());if(in==null)return;
                String suffix=message.media().isEmpty()?"":" [图片]";List<UUID> delivered=new ArrayList<>();
                for(ServerPlayer p:recipients()){
                    if(in.reply())sendReply(p,in.entry().display(),Component.literal(in.entry().display()),
                            HiddenReplyId.decorate(QqChatStyle.wire(in.rendered())+suffix,in.entry().id()),in.quote(),fallback(in,message,suffix));
                    else{
                        Component body=QqChatStyle.component(in.rendered()).copy().append(suffix);
                        if(replyCapable(p)&&in.entry().text().length()+suffix.length()+HiddenReplyId.LENGTH<=256)body=Component.literal(HiddenReplyId.encode(in.entry().id())).append(body);
                        p.sendSystemMessage(prefix(cfg.getChatFromGroup(),message.name(),body));
                    }
                    delivered.add(p.getUUID());
                }
                incomingPublished(in,delivered);
                PenguinSuit.LOG.info("[{}] QQ->MC <{}> {}",in.reply()?"replies":"chat",in.entry().display(),HiddenReplyId.bounded(in.entry().text()+suffix,512));
            }catch(Exception e){PenguinSuit.LOG.warn("[replies] QQ->MC rejected reason={}",MemberDirectory.safeName(e.getMessage()));}
        });return true;
    }
    private boolean incomingAllowed(MessageSnapshot m,PenguinConfig cfg,Object handler){var p=PenguinServerMod.INSTANCE;
        return enabled()&&p.getServer()==server&&cfg==p.getConfig()&&handler==p.getCommandHandler()&&cfg.getChatPostChat()
                &&dev.penguinsuit.config.RuntimeSettings.incoming(m.group(),m.user(),!m.media().isEmpty())&&p.getState().isFullForwarding(m.group())&&!config.blockedGroups.contains(m.group())&&!config.blockedUsers.contains(m.user());}
    public Inbound prepareIncoming(MessageSnapshot message,String text,List<String> images){
        if(!enabled()||!mentions.isScope(message.group()))return null;mentions.refresh();
        var scope=scope();var prior=ledger.bySource(scope,message.id());if(prior!=null&&prior.published())return null;
        var raw=message.reply()==null?QqReplyData.NONE:message.reply();
        var resolved=ledger.resolveQq(scope,raw,this::quotedBotText,System.currentTimeMillis());
        ReplyLedger.Entry original=resolved.entry();
        var mention=message.mention()==null?null:mentions.revalidate(message.mention());
        var rendered=mention==null?literal(HiddenReplyId.strip(text)):mention.rendered();
        rendered=autoMention(rendered,original,null,message.user());
        if(QqChatStyle.audit(rendered.plain()).isBlank()&&!raw.reply()&&images.isEmpty()&&message.media().isEmpty())return null;
        String display=MediaSenderFormat.format(PenguinServerMod.INSTANCE.getConfig().getChatFromGroup(),message.name());
        var entry=ledger.create(scope,new ReplyLedger.Author("QQ",null,message.user(),message.name()),display,rendered.plain(),
                original==null?null:original.id(),images,message.id(),List.of(raw.currentReference()),System.currentTimeMillis());
        Quote quote=original==null?raw.reply()?unmappedQuote(raw):Quote.EMPTY:quote(original);
        debug("QQ indexed",entry,"qqQuote="+refTag(raw.currentReference())+" targetQuote="+refTag(raw.targetReference()));
        if(raw.reply())debug(original==null?"QQ quote unresolved":"QQ quote resolved",entry,
                "targetQuote="+refTag(raw.targetReference())+" via="+resolved.reason()+" shape="+raw.quoteShape()
                +" authorId="+!raw.quotedOpenid().isBlank()+" authorName="+!raw.quotedSender().isBlank()
                +" sourceId="+!raw.quotedSourceId().isBlank()+" bot="+raw.quotedBot()+" quoteLength="+raw.quotedText().length());
        if(!raw.eventTrace().isBlank())debug("QQ event structure",entry,raw.eventTrace());
        if(raw.unsupportedBody())debug("QQ quote body unsupported",entry,"flattened transcript not replayed");
        return new Inbound(entry,quote,rendered,raw.reply());
    }
    private String quotedBotText(ReplyLedger.Entry entry){
        var cfg=PenguinServerMod.INSTANCE.getConfig();String body=entry.text(),prefix=cfg.getChatStartWith();
        if(!prefix.isEmpty()&&body.startsWith(prefix))body=body.substring(prefix.length());
        return cfg.getChatFromGame().replace("{name}",entry.author().name()).replace("{message}",body);
    }
    private Quote unmappedQuote(QqReplyData raw){
        String name=raw.quotedSender();
        if(name.isBlank()&&!raw.quotedOpenid().isBlank())name=mentions.directory().qqName(mentions.scope(),raw.quotedOpenid());
        // Missing event metadata is not evidence that the reply sender authored the quoted message.
        name=MemberDirectory.safeName(name);
        if(!name.isBlank())name=MediaSenderFormat.format(PenguinServerMod.INSTANCE.getConfig().getChatFromGroup(),name);
        return new Quote(HiddenReplyId.bounded(name.isBlank()?"未知作者":name,64),
                HiddenReplyId.unresolved(raw.quotedText().isBlank()?"原消息不可用":raw.quotedText()),"");
    }
    public void incomingPublished(Inbound in,Collection<UUID> viewers){
        ledger.publish(in.entry().id(),viewers);mentions.notifyOnce("reply/"+in.entry().id(),in.rendered());
        debug("QQ->MC published",in.entry(),"recipients="+viewers.size());
    }
    /** Surround native PlayerList broadcast, preserving the signature and its acknowledgement chain. */
    public void decorateGame(PlayerChatMessage message,ServerPlayer player,ChatType.Bound bound,Consumer<PlayerChatMessage> publish){
        if(!handlesGame(player)||GAME.get()!=null||!message.filterMask().isEmpty()||mentions.fake(player)){publish.accept(message);return;}
        mentions.observe(player);mentions.refresh();var cfg=PenguinServerMod.INSTANCE.getConfig();
        String text=HiddenReplyId.plain(message.decoratedContent().getString());
        var mention=MentionService.currentPublication();
        MentionMessage source=mention==null?mentions.parseSource(text):mention.source();
        var rendered=mention==null?source.render(mentions.directory()):mention.rendered();
        var entry=ledger.create(scope(),gameAuthor(player),player.getGameProfile().name(),text,null,List.of(),"",List.of(),System.currentTimeMillis());
        Plan plan=new Plan(entry,null,source,rendered,cfg,PenguinServerMod.INSTANCE.getQqClient(),forwards(source.original(),cfg));
        Component body=message.decoratedContent();if(text.length()+HiddenReplyId.LENGTH<=256)body=Component.literal(HiddenReplyId.encode(entry.id())).append(body);
        GAME.set(plan);try{publish.accept(message.withUnsignedContent(body));}finally{GAME.remove();}
    }
    /** Called after the actual native broadcast, not just after an attempted publication. */
    public void gamePublished(){
        Plan plan=GAME.get();if(plan==null)return;ledger.publish(plan.entry().id(),recipients().stream().map(ServerPlayer::getUUID).toList());
        debug("MC published",plan.entry(),"bodyLength="+plan.entry().text().length());
        if(plan.forward()){
            if(MentionService.currentPublication()!=null)sendText(plan);
            else {
                AtomicBoolean once=new AtomicBoolean();
                mentions.timeout(()->{if(once.compareAndSet(false,true))outboundFailure(plan,"text_audit_timeout");});
                try{TextFilter.INSTANCE.audit(plan.rendered().plain(),plan.config(),filtered->{
                    if(!once.compareAndSet(false,true))return Unit.INSTANCE;
                    mentions.execute(()->{try{var source=mentions.parseSource(HiddenReplyId.plain(filtered));
                        sendText(new Plan(plan.entry(),null,source,source.render(mentions.directory()),plan.config(),plan.client(),true));}
                        catch(RuntimeException e){outboundFailure(plan,"text_audit_changed");}});return Unit.INSTANCE;
                });}catch(RuntimeException e){if(once.compareAndSet(false,true))outboundFailure(plan,"text_audit_failed");}
            }
        }
    }
    public void receive(ReplyMetaPacket packet,ServerPlayNetworking.Context context){
        mentions.execute(()->{
            ServerPlayer player=context.player();
            try{
                if(!ChatRemasteredState.hasModInstalled(player.getUUID()))return;
                if(!mentions.allowedSender(player)||mentions.fake(player))throw new MemberDirectory.Invalid("当前状态不可发送回复");
                mentions.observe(player);mentions.refresh();
                ReplyLedger.Entry target=ledger.resolve(scope(),player.getUUID(),packet.replyToSender(),packet.replyToText(),packet.replyToImageId(),System.currentTimeMillis());
                String body=mentions.imageCaption(HiddenReplyId.strip(packet.text()));
                if(body.isBlank())throw new MemberDirectory.Invalid("回复正文不能为空");
                var source=mentions.parseSource(body);var rendered=autoMention(source.render(mentions.directory()),target,player.getUUID(),null);
                if(rendered.legacy().length()>256)throw new MemberDirectory.Invalid("回复正文过长，含自动 @ 最多 256 字符");
                var cfg=PenguinServerMod.INSTANCE.getConfig();boolean forward=forwards(source.original(),cfg);
                if(forward&&target.reference().isBlank())throw new MemberDirectory.Invalid("原消息缺少 QQ 引用信息");
                audit(player,rendered,()->{
                    var finalText=autoMention(source.render(mentions.directory()),target,player.getUUID(),null);
                    if(!finalText.equals(rendered)||PenguinServerMod.INSTANCE.getConfig()!=cfg)throw new MemberDirectory.Invalid("发送条件已变化，请重试");
                    var entry=ledger.create(scope(),gameAuthor(player),player.getGameProfile().name(),finalText.plain(),target.id(),List.of(),"",List.of(),System.currentTimeMillis());
                    Plan plan=new Plan(entry,target,source,finalText,cfg,PenguinServerMod.INSTANCE.getQqClient(),forward);
                    Quote quote=quote(target);String sender=player.getGameProfile().name();
                    Component fallback=Component.literal("<"+sender+"> [回复 "+quote.sender()+"："+HiddenReplyId.bounded(quote.text(),100)+"] ").append(MentionService.component(finalText));
                    List<UUID> delivered=new ArrayList<>();
                    ChatRemasteredState.recentModReply.put(player.getUUID(),System.currentTimeMillis());
                    for(ServerPlayer recipient:recipients()){
                        sendReply(recipient,sender,player.getDisplayName(),HiddenReplyId.decorate(finalText.legacy(),entry.id()),quote,fallback);delivered.add(recipient.getUUID());
                    }
                    ledger.publish(entry.id(),delivered);mentions.notifyOnce("reply/"+entry.id(),finalText);debug("MC reply published",entry,"recipients="+delivered.size());
                    PenguinSuit.LOG.info("[replies] MC <{}> replyTo={} {}",sender,target.id(),HiddenReplyId.bounded(finalText.plain(),512));
                    if(forward)sendText(plan);
                    if(TgBridgeCompat.isAvailable())TgBridgeCompat.onReplySent(target.display(),target.text(),finalText.plain(),sender);
                });
            }catch(Exception e){reject(player,e);}
        });
    }
    private void audit(ServerPlayer player,MentionMessage.Rendered rendered,Runnable published){
        if(!mentions.beginReplyAudit(player))throw new MemberDirectory.Invalid("发送过快或回复队列已满");
        AtomicBoolean once=new AtomicBoolean();
        mentions.timeout(()->{if(once.compareAndSet(false,true)){mentions.endReplyAudit();mentions.execute(()->reject(player,new MemberDirectory.Invalid("回复审核超时")));}});
        try{TextFilter.INSTANCE.audit(rendered.plain(),PenguinServerMod.INSTANCE.getConfig(),filtered->{
            if(!once.compareAndSet(false,true))return Unit.INSTANCE;mentions.endReplyAudit();
            mentions.execute(()->{try{rendered.auditText(filtered);if(!enabled()||!mentions.allowedSender(player)||server.getPlayerList().getPlayer(player.getUUID())!=player)throw new MemberDirectory.Invalid("发送条件已变化");published.run();}
                catch(Exception e){reject(player,e);}});return Unit.INSTANCE;
        });}catch(RuntimeException e){if(once.compareAndSet(false,true))mentions.endReplyAudit();throw e;}
    }
    private boolean valid(Plan plan){
        if(!enabled()||!plan.entry().scope().equals(scope())||PenguinServerMod.INSTANCE.getConfig()!=plan.config()||PenguinServerMod.INSTANCE.getQqClient()!=plan.client())return false;
        ServerPlayer p=server.getPlayerList().getPlayer(plan.entry().author().player());
        if(p==null||!dev.penguinsuit.config.RuntimeSettings.outgoing(p.getUUID(),p.getGameProfile().name(),!plan.entry().images().isEmpty())||!mentions.allowedSender(p)||mentions.fake(p)||!config.gameToQqEnabled||config.blockedGroups.contains(scope().group()))return false;
        mentions.refresh();var next=autoMention(plan.source().render(mentions.directory()),plan.target(),p.getUUID(),null);
        return next.equals(plan.rendered());
    }
    private void sendText(Plan plan){
        if(plan.client()==null){outboundFailure(plan,"bot_unavailable");return;}
        boolean queued=((QQClientAccess)(Object)plan.client()).penguinsuit$enqueue(()->{
            try{
                String markdown=server.submit(()->{if(!valid(plan))throw new MemberDirectory.Invalid("发送条件已变化");return markdown(plan,0);}).get(5,TimeUnit.SECONDS);
                JsonObject payload=new JsonObject(),md=new JsonObject();md.addProperty("content",markdown);payload.addProperty("msg_type",2);payload.add("markdown",md);
                reference(payload,plan.target()==null?"":plan.target().reference());
                var sent=new PenguinQqTransport(plan.client(),plan.config().getBotAppId()).post("/v2/groups/"+URLEncoder.encode(plan.entry().scope().group(),StandardCharsets.UTF_8)+"/messages",payload);
                var receipt=QqMediaApi.SendReceipt.from(sent);ledger.receipt(plan.entry().id(),receipt.id(),receipt.reference());
                debug("MC->QQ accepted",plan.entry(),"qqQuote="+refTag(receipt.reference()));
            }catch(Exception e){if(e instanceof InterruptedException)Thread.currentThread().interrupt();outboundFailure(plan,e.getClass().getSimpleName());}
            return Unit.INSTANCE;
        });if(!queued)outboundFailure(plan,"queue_full");
    }
    public static void reference(JsonObject payload,String ref){if(ref!=null&&!ref.isBlank()){JsonObject r=new JsonObject();r.addProperty("message_id",ref);payload.add("message_reference",r);}}
    private String markdown(Plan plan,int index){
        String body=index==0?plan.rendered().markdown():"";String prefix=QqMarkdown.literalText(plan.config().getChatStartWith());
        if(!prefix.isEmpty()&&body.startsWith(prefix))body=body.substring(prefix.length());
        return MentionService.formatMarkdown(plan.config().getChatFromGame().replace("{name}",plan.entry().author().name()),body);
    }
    private void outboundFailure(Plan plan,String reason){
        debug("MC->QQ failed",plan.entry(),"reason="+MemberDirectory.safeName(reason));
        mentions.execute(()->{ServerPlayer p=server.getPlayerList().getPlayer(plan.entry().author().player());if(p!=null)p.sendSystemMessage(Component.literal("[Penguin suit] 游戏消息已发布，但 QQ 转发未确认成功，请查看服务端日志。"));});
    }
    public Plan imagePlan(String id){return imagePlans.get(id);}
    public boolean validImage(String id){Plan p=imagePlans.get(id);return p==null||valid(p);}
    public String imageMarkdown(String id,int index){Plan p=imagePlans.get(id);return p==null?null:markdown(p,index);}
    public String imageReference(String id){Plan p=imagePlans.get(id);return p==null||p.target()==null?"":p.target().reference();}
    public void imageReceipt(String id,QqMediaApi.SendReceipt receipt)throws IOException{Plan p=imagePlans.get(id);if(p!=null){ledger.receipt(p.entry().id(),receipt.id(),receipt.reference(),id);debug("MC image->QQ accepted",p.entry(),"qqQuote="+refTag(receipt.reference()));}}
    public void imageAlbumReceipt(List<String> ids,QqMediaApi.SendReceipt receipt)throws IOException{
        if(ids.isEmpty())return;Plan first=imagePlans.get(ids.getFirst());if(first==null)return;
        for(String id:ids){Plan p=imagePlans.get(id);if(p==null||!p.entry().id().equals(first.entry().id()))throw new ReplyLedger.Invalid("reply_album_plan_mismatch");}
        ledger.albumReceipt(first.entry().id(),receipt.id(),receipt.reference(),ids);
        debug("MC image album->QQ accepted",first.entry(),"images="+ids.size()+" qqQuote="+refTag(receipt.reference()));
    }
    /** Called after complete-album validation, before any native upload receiver publishes a member. */
    public void images(ServerPlayer player,List<ImageUploadedPacket> packets,Consumer<List<ImageUploadedPacket>> publish){
        if(!handlesGame(player)){publish.accept(packets);return;}
        try{
            mentions.observe(player);mentions.refresh();if(mentions.fake(player))throw new MemberDirectory.Invalid("假人不可发送跨端图片");
            ImageUploadedPacket head=packets.getFirst();
            List<ImageUploadedPacket> quoted=packets.stream().filter(p->!p.replyToSender().isBlank()||!p.replyToText().isBlank()||!p.replyToImageId().isBlank()).toList();
            ImageUploadedPacket reference=quoted.isEmpty()?null:quoted.getFirst();
            if(reference!=null&&quoted.stream().anyMatch(p->!p.replyToSender().equals(reference.replyToSender())||!p.replyToText().equals(reference.replyToText())||!p.replyToImageId().equals(reference.replyToImageId())))throw new MemberDirectory.Invalid("同组图片引用不一致");
            ReplyLedger.Entry target=reference==null?null:ledger.resolve(scope(),player.getUUID(),reference.replyToSender(),reference.replyToText(),reference.replyToImageId(),System.currentTimeMillis());
            String caption=packets.stream().map(ImageUploadedPacket::caption).filter(s->!s.isBlank()).findFirst().orElse("");
            String body=mentions.imageCaption(HiddenReplyId.strip(caption));
            var source=mentions.parseSource(body);var rendered=autoMention(source.render(mentions.directory()),target,player.getUUID(),null);
            var cfg=PenguinServerMod.INSTANCE.getConfig();boolean forward=enabled()&&config.gameToQqEnabled&&config.forwarding.gameToQq&&config.forwarding.gameImages&&cfg.getChatPostChat()&&!config.blockedGroups.contains(scope().group())&&body.startsWith(cfg.getChatStartWith());
            if(forward&&target!=null&&target.reference().isBlank())throw new MemberDirectory.Invalid("原消息缺少 QQ 引用信息");
            audit(player,rendered,()->{
                if(PenguinServerMod.INSTANCE.getConfig()!=cfg||!ImageMentionGate.nativeAllowed(player))throw new MemberDirectory.Invalid("发送条件已变化");
                var current=autoMention(source.render(mentions.directory()),target,player.getUUID(),null);
                if(!current.equals(rendered))throw new MemberDirectory.Invalid("绑定关系已变化，请重试");
                var entry=ledger.create(scope(),gameAuthor(player),player.getGameProfile().name(),current.plain(),target==null?null:target.id(),
                        packets.stream().map(ImageUploadedPacket::imageId).toList(),"",List.of(),System.currentTimeMillis());
                Plan plan=new Plan(entry,target,source,current,cfg,PenguinServerMod.INSTANCE.getQqClient(),forward);
                Quote q=target==null?Quote.EMPTY:quote(target);List<ImageUploadedPacket> changed=new ArrayList<>();
                for(int i=0;i<packets.size();i++){
                    ImageUploadedPacket p=packets.get(i);imagePlans.put(p.imageId(),plan);
                    String text=i==0?HiddenReplyId.decorate(current.legacy(),entry.id()):"";
                    changed.add(new ImageUploadedPacket(p.imageId(),player.getGameProfile().name(),text,p.width(),p.height(),q.sender(),q.text(),q.image(),p.groupId(),p.groupIndex(),p.groupCount()));
                }
                publish.accept(List.copyOf(changed));ledger.publish(entry.id(),recipients().stream().map(ServerPlayer::getUUID).toList());
                mentions.notifyOnce("reply/"+entry.id(),current);debug("MC image published",entry,"images="+packets.size());
            });
        }catch(Exception e){reject(player,e);}
    }
    @Override public void close(){active=false;maintenance.shutdown();try{maintenance.awaitTermination(5,TimeUnit.SECONDS);ledger.flush();}
        catch(InterruptedException e){Thread.currentThread().interrupt();}catch(IOException e){PenguinSuit.LOG.error("[replies] final index save failed",e);}imagePlans.clear();}
}
