package dev.penguinsuit.mention;

import com.google.gson.JsonObject;
import com.huhobot.penguin.PenguinServerMod;
import com.huhobot.penguin.config.PenguinConfig;
import com.huhobot.penguin.filter.TextFilter;
import com.huhobot.penguin.qq.*;
import dev.errnicraft.chatremastered.server.*;
import dev.errnicraft.chatremastered.server.groups.*;
import dev.errnicraft.chatremastered.server.moderation.ModerationActions;
import dev.penguinsuit.PenguinSuit;
import dev.penguinsuit.bridge.*;
import dev.penguinsuit.config.BridgeConfig;
import dev.penguinsuit.mixin.QQClientAccess;
import dev.penguinsuit.reverse.*;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import kotlin.Unit;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.*;
import net.minecraft.network.protocol.game.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.*;

public final class MentionService implements AutoCloseable {
    public record Publication(UUID sender,String senderName,MentionMessage source,MentionMessage.Rendered rendered,
                              PenguinConfig config,QQClient client,String id,boolean forward) {}
    public record Incoming(MentionMessage source,MentionMessage.Rendered rendered,String auditText,String key) {}
    private static final ThreadLocal<Publication> PUBLICATION=new ThreadLocal<>();
    private final MinecraftServer server;
    private final MemberDirectory directory;
    private final ImageMentionGate images;
    private final dev.penguinsuit.auth.AuthenticationService authentication;
    private final ScheduledExecutorService storage;
    private volatile BridgeConfig config;
    private volatile boolean active=true;
    private final Map<UUID,List<String>> supplied=new HashMap<>();
    private final Map<String,Long> feedback=new ConcurrentHashMap<>(), delivered=new ConcurrentHashMap<>();
    private final Map<UUID,Long> sendTimes=new HashMap<>();
    private final AtomicInteger pending=new AtomicInteger();
    private long nextRefresh, lastRevision=-1, lastEvent, nextDirectoryCleanup;
    private volatile boolean connected;
    private volatile String selfIdentity="READY not observed";
    public MentionService(MinecraftServer server,BridgeConfig config,Path file) throws IOException {
        this.server=server;this.config=config;directory=new MemberDirectory(file,config.fakePlayerFilter);images=new ImageMentionGate(this);
        directory.configuredExclusions(config.qqBotExcludedMembers);
        authentication=new dev.penguinsuit.auth.AuthenticationService(server,this,config,file.resolveSibling("bindings.json"));
        storage=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"penguin-member-storage");t.setDaemon(true);return t;});
        storage.scheduleWithFixedDelay(()->{try{directory.flush();}catch(IOException e){PenguinSuit.LOG.error("Member directory save failed: {}",e.getClass().getSimpleName());}},2,2,TimeUnit.SECONDS);
        refresh();
    }
    private Set<String> protectedDirectoryKeys(){
        Set<String> keys=new HashSet<>();for(var b:authentication.ledger().records()){keys.add("mc/"+b.player());keys.add(b.key());}keys.addAll(authentication.ledger().pendingIdentityKeys());return keys;
    }
    public int pruneDirectory()throws IOException{return directory.pruneInactive(config.directory.inactiveRetentionDays,System.currentTimeMillis(),protectedDirectoryKeys());}
    public boolean forgetMember(String key)throws IOException{refresh();boolean removed=directory.forget(key,protectedDirectoryKeys());lastRevision=-1;return removed;}
    public MemberDirectory directory(){return directory;}
    public dev.penguinsuit.auth.AuthenticationService authentication(){return authentication;}
    public ImageMentionGate images(){return images;}
    public boolean mentionsEnabled(){return enabled()&&config.mentions.enabled;}
    public MentionMessage parseSource(String text){return mentionsEnabled()?MentionMessage.parse(text,scope()):new MentionMessage(text,List.of(),scope());}
    public String imageCaption(String text){return mentionsEnabled()?AtSyntax.imageCaption(text,directory,scope()):text;}
    public List<String> candidates(boolean command){
        if(!mentionsEnabled()||!(command?config.mentions.commandCompletions:config.mentions.chatCompletions))return List.of();
        return directory.candidates(scope(),config.mentions.sort,config.mentions.boundAliases);
    }
    public boolean enabled(){return config.enabled&&active;}
    public int maxImages(){return Math.min(config.maxImagesPerMessage,ChatRemasteredState.cachedConfig==null?config.maxImagesPerMessage:ChatRemasteredState.cachedConfig.maxPhotosPerMessage());}
    public void timeout(Runnable task){storage.schedule(task,config.mentions.auditTimeoutSeconds,TimeUnit.SECONDS);}
    boolean beginImageAudit(ServerPlayer player){return reserve(player);}
    void endImageAudit(){pending.decrementAndGet();}
    public boolean beginReplyAudit(ServerPlayer player){return reserve(player);}
    public void endReplyAudit(){pending.decrementAndGet();}
    public void execute(Runnable task){if(active){if(server.isSameThread())task.run();else server.execute(task);}}
    public MemberDirectory.Scope scope(){
        var cfg=PenguinServerMod.INSTANCE.getConfig();
        List<String> groups=cfg.getBotGroups().stream().filter(MemberDirectory::validId).distinct().toList();
        if(groups.size()!=1||!MemberDirectory.validId(cfg.getBotAppId()))throw new MemberDirectory.Invalid("提及功能需要配置一个 QQ 群及有效 AppID");
        return new MemberDirectory.Scope(cfg.getBotAppId(),groups.getFirst());
    }
    public boolean isScope(String group){try{return scope().group().equals(group);}catch(MemberDirectory.Invalid e){return false;}}
    public void observe(ServerPlayer player){directory.observePlayer(player.getUUID(),player.getGameProfile().name(),System.currentTimeMillis());}
    public void refresh(){
        if(!active)return;
        var players=List.copyOf(server.getPlayerList().getPlayers());players.forEach(this::observe);
        directory.online(players.stream().map(ServerPlayer::getUUID).toList());
        try {
            var scope=scope();var state=PenguinServerMod.INSTANCE.getState();
            for(String id:directory.pendingDeletes(scope)){
                authentication.departed(scope,id);
                synchronized(state){state.unbindOpenid(scope.group(),id);}
                directory.deletionCompleted(scope,id);
            }
            if(config.authenticationEnabled){
                // Never leave a previously published verified mapping active after reconciliation fails.
                try{directory.syncVerifiedBindings(scope,authentication.refresh(scope));directory.unverifiedNames(scope,PenguinBindings.snapshot(state,scope.group()));}
                catch(Exception e){directory.syncVerifiedBindings(scope,Map.of());throw e;}
            }else directory.syncBindings(scope,PenguinBindings.snapshot(state,scope.group()));
        }catch(MemberDirectory.Invalid ignored){authentication.invalidateScope();}catch(Exception e){PenguinSuit.LOG.warn("Member binding synchronization failed: {}",e.getClass().getSimpleName());}
    }
    public void tick(){
        long now=System.currentTimeMillis();if(!active||now<nextRefresh)return;nextRefresh=now+config.mentions.refreshMillis;refresh();images.tick();
        if(now>=nextDirectoryCleanup){nextDirectoryCleanup=now+config.directory.cleanupIntervalSeconds*1000L;try{pruneDirectory();}catch(IOException e){PenguinSuit.LOG.warn("Member history cleanup failed: {}",e.getClass().getSimpleName());}}
        List<String> candidates;try{candidates=candidates(false);}catch(MemberDirectory.Invalid e){candidates=List.of();}
        long revision=directory.revision();
        Set<UUID> online=new HashSet<>();
        for(ServerPlayer player:server.getPlayerList().getPlayers()){
            online.add(player.getUUID());List<String> before=supplied.getOrDefault(player.getUUID(),List.of());
            if(revision!=lastRevision||!supplied.containsKey(player.getUUID())){
                List<String> next=candidates;
                sendCompletions(player,ClientboundCustomChatCompletionsPacket.Action.REMOVE,before.stream().filter(s->!next.contains(s)).toList());
                sendCompletions(player,ClientboundCustomChatCompletionsPacket.Action.ADD,next.stream().filter(s->!before.contains(s)).toList());
                supplied.put(player.getUUID(),next);
            }
        }
        supplied.keySet().retainAll(online);sendTimes.keySet().retainAll(online);lastRevision=revision;
        feedback.entrySet().removeIf(e->now-e.getValue()>600000);delivered.entrySet().removeIf(e->now-e.getValue()>600000);
    }
    private static void sendCompletions(ServerPlayer player,ClientboundCustomChatCompletionsPacket.Action action,List<String> entries){
        for(int i=0;i<entries.size();i+=128)player.connection.send(new ClientboundCustomChatCompletionsPacket(action,entries.subList(i,Math.min(entries.size(),i+128))));
    }
    public boolean fake(ServerPlayer p){observe(p);return config.enabled&&directory.excluded(p.getUUID(),p.getGameProfile().name());}
    public void reconfigure(BridgeConfig next){config=next;directory.reconfigure(next.fakePlayerFilter);directory.configuredExclusions(next.qqBotExcludedMembers);authentication.reconfigure(next);lastRevision=-1;nextRefresh=0;nextDirectoryCleanup=0;refresh();}
    public String status(){return directory.status()+", QQ events="+(connected?"connected":"not observed/disconnected")+", lastEvent="+lastEvent+", selfBot="+selfIdentity;}
    public static boolean customPublication(){return PUBLICATION.get()!=null;}
    public static Publication currentPublication(){return PUBLICATION.get();}
    public static Component component(MentionMessage.Rendered r){
        MutableComponent out=Component.empty();for(var part:r.parts()){
            MutableComponent piece=Component.literal(part.text());
            if(part.target()!=null&&part.target().online())piece.withStyle(style -> {var color=dev.penguinsuit.config.RuntimeSettings.color();return color==null?style:style.withColor(color);});out.append(piece);
        }return out;
    }
    public void failure(ServerPlayer p,String reason){p.sendSystemMessage(Component.literal(dev.penguinsuit.config.RuntimeSettings.text(config.messages.mentionFailure,"reason",MemberDirectory.safeName(reason))).withStyle(ChatFormatting.RED));}
    public boolean publicChannel(ServerPlayer p){return !(GroupNetwork.isGroupChannel(p.getUUID())&&ChatGroupManager.groupOf(p.getUUID())!=null);}
    public boolean allowedSender(ServerPlayer p){return active&&p!=null&&!ModerationActions.isEffectivelyMuted(p.getUUID())&&publicChannel(p);}
    public void handleGame(PlayerChatMessage message,ServerPlayer player,ChatType.Bound bound,Consumer<PlayerChatMessage> publish){
        if(PUBLICATION.get()!=null||!mentionsEnabled()||!MentionMessage.hasExplicit(message.decoratedContent().getString())){publish.accept(message);return;}
        observe(player);refresh();
        try{
            if(!allowedSender(player))throw new MemberDirectory.Invalid("当前禁言或私聊频道不可发送跨端提及");
            if(!message.filterMask().isEmpty())throw new MemberDirectory.Invalid("消息包含被过滤的内容");
            var source=MentionMessage.parse(message.decoratedContent().getString(),scope());var rendered=source.render(directory);
            if(!reserve(player))throw new MemberDirectory.Invalid("发送过快或提及队列已满");
            var cfg=PenguinServerMod.INSTANCE.getConfig();var client=PenguinServerMod.INSTANCE.getQqClient();
            String id=UUID.randomUUID().toString();AtomicBoolean completed=new AtomicBoolean();
            storage.schedule(()->{if(completed.compareAndSet(false,true)){pending.decrementAndGet();server.execute(()->failure(player,"消息审核超时"));}},config.mentions.auditTimeoutSeconds,TimeUnit.SECONDS);
            try{TextFilter.INSTANCE.audit(rendered.plain(),cfg,filtered->{
                if(!completed.compareAndSet(false,true))return Unit.INSTANCE;pending.decrementAndGet();
                execute(()->{try{
                    if(!allowedSender(player)||PenguinServerMod.INSTANCE.getConfig()!=cfg)throw new MemberDirectory.Invalid("发送条件已变化");
                    rendered.auditText(filtered);refresh();var finalText=source.render(directory);
                    boolean forward=dev.penguinsuit.config.RuntimeSettings.outgoing(player.getUUID(),player.getGameProfile().name(),false)&&config.gameToQqEnabled&&cfg.getChatPostChat()&&source.original().startsWith(cfg.getChatStartWith());
                    Publication p=new Publication(player.getUUID(),player.getGameProfile().name(),source,finalText,cfg,client,id,forward);
                    PUBLICATION.set(p);try{publish.accept(message.withUnsignedContent(component(finalText)));}finally{PUBLICATION.remove();}
                }catch(MemberDirectory.Invalid e){failure(player,e.getMessage());}});return Unit.INSTANCE;
            });}catch(RuntimeException e){if(completed.compareAndSet(false,true))pending.decrementAndGet();throw e;}
        }catch(MemberDirectory.Invalid e){failure(player,e.getMessage());}
        catch(RuntimeException e){PenguinSuit.LOG.error("Mention admission failed: {}",e.getClass().getSimpleName());failure(player,"提及处理出错，请查看服务器日志");}
    }
    private boolean reserve(ServerPlayer player){
        long now=System.currentTimeMillis();long previous=sendTimes.getOrDefault(player.getUUID(),0L);
        if(now-previous<Math.max(250,config.minSenderIntervalMillis)||pending.get()>=config.queueCapacity)return false;
        sendTimes.put(player.getUUID(),now);pending.incrementAndGet();return true;
    }
    /** Called only after the native PlayerList actually completes its broadcast. */
    public void published(){
        Publication p=PUBLICATION.get();if(p==null)return;
        notifyOnce("mc/"+p.id(),p.rendered());logFiltered("MC->QQ",p.id(),p.rendered());
        if(p.forward()&&!dev.penguinsuit.reply.ReplyService.customPublication())sendText(p);
    }
    public void notifyOnce(String key,MentionMessage.Rendered rendered){
        if(!mentionsEnabled()||!config.mentions.soundEnabled||delivered.size()>8192)return;
        if(delivered.putIfAbsent(key,System.currentTimeMillis())!=null)return;
        for(UUID uuid:rendered.notifications()){ServerPlayer target=server.getPlayerList().getPlayer(uuid);if(target==null)continue;
            target.connection.send(new ClientboundSoundPacket(Holder.direct(SoundEvent.createVariableRangeEvent(net.minecraft.resources.Identifier.parse(config.mentions.sound))),SoundSource.PLAYERS,
                    target.getX(),target.getY(),target.getZ(),config.mentions.volume,config.mentions.pitch,System.nanoTime()));
        }
    }
    public void logFiltered(String direction,String id,MentionMessage.Rendered r){if(!r.filtered().isEmpty()&&dev.penguinsuit.config.BridgeLog.detailed("mentions"))
        PenguinSuit.LOG.info("[mentions] {} message={} removedTargets={}",direction,MemberDirectory.safeName(id),r.filtered().size());}
    private boolean outgoingAllowed(Publication p){
        if(!active||!config.enabled||!config.gameToQqEnabled||PenguinServerMod.INSTANCE.getConfig()!=p.config()||PenguinServerMod.INSTANCE.getQqClient()!=p.client())return false;
        if(config.blockedGroups.contains(p.source().scope().group()))return false;
        ServerPlayer player=server.getPlayerList().getPlayer(p.sender());return allowedSender(player)&&dev.penguinsuit.config.RuntimeSettings.outgoing(player.getUUID(),player.getGameProfile().name(),false);
    }
    private void sendText(Publication p){
        if(p.client()==null)return;
        boolean queued=((QQClientAccess)(Object)p.client()).penguinsuit$enqueue(()->{
            try{
                String text=server.submit(()->{
                    refresh();if(!outgoingAllowed(p))throw new MemberDirectory.Invalid("转发条件已变化");
                    var r=p.source().render(directory);
                    // A published MC message is immutable; never silently retarget a later QQ request.
                    if(!r.equals(p.rendered()))throw new MemberDirectory.Invalid("提及目标状态已变化，QQ 未发送");
                    String template=p.config().getChatFromGame().replace("{name}",p.senderName());
                    String markdown=r.markdown();String prefix=p.config().getChatStartWith();
                    if(!prefix.isEmpty()&&markdown.startsWith(QqMarkdown.literalText(prefix)))markdown=markdown.substring(QqMarkdown.literalText(prefix).length());
                    return formatMarkdown(template,markdown);
                }).get(5,TimeUnit.SECONDS);
                JsonObject md=new JsonObject();md.addProperty("content",text);JsonObject payload=new JsonObject();payload.addProperty("msg_type",2);payload.add("markdown",md);
                var response=new PenguinQqTransport(p.client(),p.config().getBotAppId()).post("/v2/groups/"+URLEncoder.encode(p.source().scope().group(),StandardCharsets.UTF_8)+"/messages",payload);
                if(response.status()<200||response.status()>=300||(response.body().has("code")&&response.body().get("code").getAsInt()!=0)
                        ||!response.body().has("id")||response.body().get("id").getAsString().isBlank())throw new IOException("QQ rejected or did not confirm mention message");
                PenguinSuit.LOG.info("[mentions] MC->QQ message={} accepted",p.id());
            }catch(Exception e){
                if(e instanceof InterruptedException)Thread.currentThread().interrupt();
                PenguinSuit.LOG.warn("[mentions] MC->QQ message={} failed: {}",p.id(),e.getClass().getSimpleName());
                server.execute(()->{var player=server.getPlayerList().getPlayer(p.sender());if(player!=null)player.sendSystemMessage(Component.literal("[Penguin suit] 游戏消息已发布，但 QQ 转发未确认成功，请查看日志。"));});
            }return Unit.INSTANCE;
        });
        if(!queued){var player=server.getPlayerList().getPlayer(p.sender());if(player!=null)player.sendSystemMessage(Component.literal("[Penguin suit] 游戏消息已发布，但 QQ 队列已满。"));}
    }
    public static String formatMarkdown(String template,String body){
        int index=template.indexOf("{message}");if(index<0)return QqMarkdown.literalText(template)+body;
        return QqMarkdown.literalText(template.substring(0,index))+body+QqMarkdown.literalText(template.substring(index+9));
    }
    public void observeQq(GroupMessage message,Object raw,Object author){
        if(!active||!isScope(message.getGroupId()))return;
        long time=timestamp(message.getTimestamp());var scope=scope();
        directory.observeQq(scope,message.getUserId(),message.getUsername(),time,true);
        if(author instanceof Map<?,?> user)learnIdentity(scope,user,false,"author");
        if(raw instanceof List<?> list)for(Object item:list.stream().limit(128).toList())if(item instanceof Map<?,?> user){
            String id=QqIdentity.memberId(user);
            directory.observeQq(scope,id,string(user,"username"),time,false);
            learnIdentity(scope,user,false,"mention");
        }
        connected=true;lastEvent=System.currentTimeMillis();
        execute(this::refresh);
    }
    private void learnIdentity(MemberDirectory.Scope scope,Map<?,?> user,boolean self,String reason){
        for(String id:directory.observeQqIdentity(scope,user,self)){
            var entry=directory.qqEntry(scope,id);
            String name=entry.name().isBlank()?MemberDirectory.safeName(string(user,"username")):entry.name();
            PenguinSuit.LOG.info("[mentions] QQ bot excluded source={} name={} code={} identity={}",reason,name,entry.code(),fingerprint(scope,id));
        }
    }
    private static String fingerprint(MemberDirectory.Scope scope,String id){
        return UUID.nameUUIDFromBytes(scope.key(id).getBytes(StandardCharsets.UTF_8)).toString().substring(0,8);
    }
    public String manualBot(String selection,boolean exclude)throws IOException{
        var s=scope();var result=directory.selectQqAdmin(s,selection);
        config.setBotExcluded(s.key(result.id()),exclude);directory.configuredExclusions(config.qqBotExcludedMembers);directory.flush();refresh();nextRefresh=0;tick();
        PenguinSuit.LOG.info("[mentions] QQ manual exclusion={} name={} code={} identity={}",exclude,result.name(),result.code(),fingerprint(s,result.id()));
        return (exclude?"已排除：":"已取消手动排除：")+result.name()+" #"+result.code()
                +(!exclude&&result.automatic()?"（自动识别的机器人仍被过滤）":"")+"；配置项："+s.key(result.id());
    }
    /** Upstream drops bot-authored messages before calling its listener; still learn their identity. */
    public void observeQqAuthor(Map<?,?> event){
        if(!active||!isScope(string(event,"group_openid")))return;
        if(event.get("author") instanceof Map<?,?> author)learnIdentity(scope(),author,false,"author-before-dispatch");
    }
    public void dispatch(Map<?,?> event){
        String type=string(event,"t");
        if(type.equals("READY")||type.equals("RESUMED")){
            connected=true;lastEvent=System.currentTimeMillis();
            // READY's user is the connected bot itself, even when its bot flag is omitted.
            if(type.equals("READY")){
                Map<?,?> d=event.get("d") instanceof Map<?,?> m?m:Map.of();
                Map<?,?> user=d.get("user") instanceof Map<?,?> m?m:Map.of();
                selfIdentity=QqIdentity.aliases(user).isEmpty()?"missing user identifiers":"identifiers received";
                try{
                    var s=scope();learnIdentity(s,user,true,"READY");
                    if(dev.penguinsuit.config.BridgeLog.detailed("mentions"))PenguinSuit.LOG.info("[mentions] QQ READY selfBot={} userFields={} excludedKnownMembers={}",
                            selfIdentity,user.keySet().stream().map(String::valueOf).map(MemberDirectory::safeName).sorted().limit(16).toList(),directory.excludedQqCount(s));
                }catch(MemberDirectory.Invalid e){selfIdentity="scope not configured";PenguinSuit.LOG.warn("[mentions] QQ READY identity skipped: single group/AppID not configured");}
            }
            return;
        }
        if(!type.equals("GROUP_MEMBER_ADD")&&!type.equals("GROUP_MEMBER_REMOVE"))return;
        if(!(event.get("d")instanceof Map<?,?> d))return;String group=string(d,"group_openid");if(!isScope(group))return;
        String id=string(d,"member_openid");Map<?,?> user=d.get("user")instanceof Map<?,?> m?m:Map.of();if(id.isBlank())id=string(user,"id");
        if(!MemberDirectory.validId(id))return;String target=id;long time=timestamp(d.get("timestamp"));
        // Membership without a timestamp uses receive time; never infer membership from silence.
        connected=true;lastEvent=System.currentTimeMillis();
        execute(()->{try{
            var s=scope();String eventId=string(event,"id");if(eventId.isEmpty())eventId=string(d,"event_id");
            if(!directory.acceptEvent(s,eventId,System.currentTimeMillis()))return;
            if(type.equals("GROUP_MEMBER_ADD")){
                directory.observeQq(s,target,string(user,"username"),time,true);
                Map<Object,Object> identity=new HashMap<>(user);identity.put("member_openid",target);
                if(QqIdentity.bot(d))identity.put("bot",true);
                learnIdentity(s,identity,false,"member-add");
            }
            else if(directory.removeQq(s,target,time)){authentication.departed(s,target);var state=PenguinServerMod.INSTANCE.getState();synchronized(state){state.unbindOpenid(group,target);}directory.deletionCompleted(s,target);}
            refresh();
        }catch(Exception e){PenguinSuit.LOG.error("Member event update failed: {}",e.getClass().getSimpleName());}});
    }
    public void disconnected(){connected=false;}
    public void bindingChanged(String group,String openid){if(isScope(group)){directory.bindingChanged(scope(),openid);authentication.externalChange(scope(),openid);}}
    public Incoming incoming(GroupMessage message,String raw){
        if(!active||!mentionsEnabled()||!config.forwarding.qqMentions||!isScope(message.getGroupId()))return null;
        refresh();
        var source=MentionMessage.fromQq(raw,scope(),directory);if(!source.hasMentions())return null;
        var r=source.render(directory);return new Incoming(source,r,r.plain(),message.getGroupId()+"/"+message.getId());
    }
    public void incomingFailure(MessageSnapshot message,String reason){
        if(feedback.size()>4096||feedback.putIfAbsent(message.key(),System.currentTimeMillis())!=null)return;
        PenguinSuit.LOG.warn("[mentions] QQ->MC message={} rejected: {}",MemberDirectory.safeName(message.id()),MemberDirectory.safeName(reason));
        if(config.blockedGroups.contains(message.group())||config.blockedUsers.contains(message.user()))return;
        var client=PenguinServerMod.INSTANCE.getQqClient();if(client!=null)client.sendGroupMessage(message.group(),dev.penguinsuit.config.RuntimeSettings.text(config.messages.incomingFailure,"reason",MemberDirectory.safeName(reason)),message.id());
    }
    public Incoming revalidate(Incoming incoming){
        if(incoming==null)return null;refresh();var r=incoming.source().render(directory);
        return new Incoming(incoming.source(),r,incoming.auditText(),incoming.key());
    }
    public Component incomingComponent(MessageSnapshot message, Incoming incoming, String suffix){
        String template=PenguinServerMod.INSTANCE.getConfig().getChatFromGroup().replace("{name}",MemberDirectory.safeName(message.name()));
        int index=template.indexOf("{message}");
        if(index<0)return Component.literal(template).append(component(incoming.rendered())).append(suffix);
        return Component.literal(template.substring(0,index)).append(component(incoming.rendered()))
                .append(template.substring(index+9)).append(suffix);
    }
    public void incomingText(MessageSnapshot message, PenguinConfig sourceConfig, Object sourceHandler){
        execute(()->{try{
            var penguin=PenguinServerMod.INSTANCE;
            if(!active||!config.enabled||!dev.penguinsuit.config.RuntimeSettings.incoming(message.group(),message.user(),!message.media().isEmpty())||sourceConfig!=penguin.getConfig()||sourceHandler!=penguin.getCommandHandler()
                    ||!sourceConfig.getChatPostChat()||!penguin.getState().isFullForwarding(message.group())
                    ||config.blockedGroups.contains(message.group())||config.blockedUsers.contains(message.user()))return;
            Incoming current=revalidate(message.mention());
            if(current==null)return;String suffix=message.media().isEmpty()?"":" [图片]";
            // An excluded-only QQ mention must not leave an empty <sender> line in game.
            if(!current.rendered().plain().isBlank()||!suffix.isEmpty())
                server.getPlayerList().broadcastSystemMessage(incomingComponent(message,current,suffix),false);
            incomingDelivered(current);
        }catch(MemberDirectory.Invalid e){incomingFailure(message,e.getMessage());}});
    }
    public void incomingDelivered(Incoming incoming){if(incoming!=null){notifyOnce("qq/"+incoming.key(),incoming.rendered());logFiltered("QQ->MC",incoming.key(),incoming.rendered());}}
    public static String string(Map<?,?> map,String key){return map.get(key) instanceof String s?s:"";}
    public static long timestamp(Object value){
        if(value instanceof Number n)return n.longValue()<100000000000L?n.longValue()*1000:n.longValue();
        if(value instanceof String s)try{return Instant.parse(s).toEpochMilli();}catch(Exception ignored){try{return OffsetDateTime.parse(s).toInstant().toEpochMilli();}catch(Exception ignored2){}}
        return System.currentTimeMillis();
    }
    @Override public void close(){active=false;authentication.close();storage.shutdown();try{storage.awaitTermination(5,TimeUnit.SECONDS);directory.flush();}
        catch(InterruptedException e){Thread.currentThread().interrupt();}catch(IOException e){PenguinSuit.LOG.error("Final member directory save failed",e);}}
}
