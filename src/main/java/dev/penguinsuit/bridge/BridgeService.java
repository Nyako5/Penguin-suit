package dev.penguinsuit.bridge;

import com.huhobot.penguin.PenguinServerMod;
import com.huhobot.penguin.config.PenguinConfig;
import dev.errnicraft.chatremastered.tcp.ImageTcpServer;
import dev.penguinsuit.PenguinSuit;
import dev.penguinsuit.config.BridgeConfig;
import dev.penguinsuit.media.*;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;

public final class BridgeService implements AutoCloseable {
    private final MinecraftServer server;
    private final ThreadPoolExecutor workers;
    private final ScheduledExecutorService maintenance;
    private final OwnedMediaIndex owned;
    private final Admission admission = new Admission();
    private final MediaFetcher fetcher = new MediaFetcher();
    private final MediaInspector inspector = new MediaInspector();
    private final AtomicLong accepted = new AtomicLong(), duplicate = new AtomicLong(), failed = new AtomicLong();
    private volatile BridgeConfig config;
    private volatile boolean active = true;
    private volatile long nextCleanup;

    public BridgeService(MinecraftServer server, BridgeConfig config, Path stateFile) throws IOException {
        this.server = server; this.config = config;
        owned = new OwnedMediaIndex(stateFile, new VerifiedDiskCache(server.getServerDirectory().resolve("srvcashe"), new OwnedMediaIndex.Cache() {
            public void store(String id, byte[] bytes) { ImageTcpServer.storeBytes(id, bytes); }
            public void delete(String id) { ImageTcpServer.deleteImage(id); }
        }), System::currentTimeMillis, this::mediaRemoved);
        workers = new ThreadPoolExecutor(config.workerThreads, config.workerThreads, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(config.queueCapacity), factory("penguin-media-worker"), new ThreadPoolExecutor.AbortPolicy());
        maintenance = Executors.newSingleThreadScheduledExecutor(factory("penguin-media-cache"));
        maintenance.scheduleWithFixedDelay(() -> {
            if (!active) return;
            try {
                long now=System.currentTimeMillis();if(now<nextCleanup)return;nextCleanup=now+this.config.media.cacheCleanupSeconds*1000L;
                owned.expire(this.config, now);
            } catch (Exception e) { PenguinSuit.LOG.warn("Bridge cache maintenance failed: {}", e.getClass().getSimpleName()); }
        }, 5, 1, TimeUnit.SECONDS);
    }
    private static ThreadFactory factory(String name) {
        return task -> { Thread thread = new Thread(task, name); thread.setDaemon(true); return thread; };
    }

    public boolean accept(MessageSnapshot message, String filtered, PenguinConfig sourceConfig, Object sourceHandler) {
        BridgeConfig cfg = config;
        if (!active || !cfg.enabled || !cfg.forwarding.qqImages || !dev.penguinsuit.config.RuntimeSettings.incoming(message.group(),message.user(),!message.media().isEmpty()) || cfg.blockedGroups.contains(message.group()) || cfg.blockedUsers.contains(message.user())) return false;
        ChatBackend.Limits limits = ChatBackend.limits();
        if (limits == null || limits.maxImages() < 1 || limits.maxBytes() < 1) return false;
        var emojiImages=cfg.forwarding.qqImages&&cfg.qqEmojiImagesEnabled&&!cfg.media.emojiMode.equals("TEXT")?message.emojis().images(filtered):List.<java.net.URI>of();
        var resolution = AttachmentResolver.resolve(message.media(),emojiImages, Math.min(cfg.maxImagesPerMessage, limits.maxImages()));
        if (resolution.images().isEmpty()) return false;
        if (!authorized(message, sourceConfig, sourceHandler)) return false;
        // This is the existing Penguin formatter, with the filtered content and frozen attachment metadata.
        String fallback = PenguinServerMod.INSTANCE.formatGroupMessage(message.name(), message.filteredMessage(filtered));
        String caption = bounded(AttachmentResolver.cleanCaption(filtered), 8192);
        String sender = MediaSenderFormat.format(sourceConfig.getChatFromGroup(), message.name());
        String key = message.id().isBlank() ? UUID.randomUUID().toString() : message.key();
        long now=queueClock();
        var result = admission.submit(key, message.user(), message.group(), now, cfg, slot -> {
            workers.execute(() -> {
                boolean preparing=false;
                try {
                    while(active){
                        long remaining=slot-queueClock();
                        if(remaining<=0)break;
                        TimeUnit.MILLISECONDS.sleep(Math.min(250,remaining));
                    }
                    if(!active)return;
                    preparing=true;
                    process(message,fallback,sender,caption,sourceConfig,sourceHandler,cfg,limits,resolution,
                            ()->admission.complete(key,queueClock()));
                } catch(InterruptedException e){Thread.currentThread().interrupt();}
                finally{if(!preparing)admission.complete(key,queueClock());}
            });
            if(slot>now&&dev.penguinsuit.config.BridgeLog.detailed("media"))PenguinSuit.LOG.info("[media] QQ->MC queued sender=<{}> delayMs={} queuedTasks={}",
                    MediaDeliveryLog.safeSender(sender),slot-now,workers.getQueue().size());
        });
        if (result == Admission.Result.ACCEPTED) accepted.incrementAndGet();
        if (result == Admission.Result.DUPLICATE) duplicate.incrementAndGet();
        if (result == Admission.Result.PASS)PenguinSuit.LOG.warn("[media] QQ->MC text-fallback sender=<{}> reason=queue_full_or_stopping pending={}",
                MediaDeliveryLog.safeSender(sender),admission.pending());
        return result != Admission.Result.PASS;
    }
    private static long queueClock(){return TimeUnit.NANOSECONDS.toMillis(System.nanoTime());}

    private void process(MessageSnapshot message, String fallback, String sender, String caption, PenguinConfig sourceConfig,
                         Object sourceHandler, BridgeConfig cfg, ChatBackend.Limits limits, AttachmentResolver.Resolution resolution,Runnable completed) {
        boolean publicationQueued=false;
        try {
        var result = MediaPreparation.prepare(resolution.images(), resolution.omitted(),
                () -> authorized(message, sourceConfig, sourceHandler), uri -> {
                byte[] data = message.emojis().faces().stream().anyMatch(f->uri.equals(f.source()))
                        ?dev.penguinsuit.emoji.QqEmojiMedia.fetch(uri,fetcher,cfg,Math.min(cfg.maxDownloadBytes,limits.maxBytes()),limits.gifEnabled()&&cfg.media.emojiMode.equals("ANIMATED"),limits.gifDimension())
                        :fetcher.fetch(uri, cfg, Math.min(cfg.maxDownloadBytes, limits.maxBytes()));
                data=GifMode.apply(data,cfg.media.incomingGifMode,cfg,Math.min(cfg.maxDownloadBytes,limits.maxBytes()));
                var info = inspector.inspect(data, cfg, limits.gifEnabled(), limits.gifDimension());
                if (!active || Thread.currentThread().isInterrupted()) throw new MediaException("cancelled");
                String id = owned.store(data, cfg);
                return new ChatBackend.Prepared(id, info.width(), info.height());
            }, this::cleanup, e -> {
                failed.incrementAndGet();
                // Never print URLs, QQ IDs, signed query strings or exception messages from HTTP libraries.
                String reason = e instanceof MediaException ? e.getMessage() : e.getClass().getSimpleName();
                PenguinSuit.LOG.warn("QQ media could not be prepared: {}", reason);
                if (e instanceof LinkageError) PenguinSuit.LOG.warn(
                        "Image runtime unavailable; preserving text fallback. On headless Linux, restart Java with -Djava.awt.headless=true before -jar.");
            });
        if (result.cancelled()) return;
        List<ChatBackend.Prepared> ready = result.images();
        int emojiStart=AttachmentResolver.resolve(message.media(),Math.min(cfg.maxImagesPerMessage,limits.maxImages())).images().size();
        Set<Integer> successfulEmojis=new HashSet<>();
        for(int i:result.sourceIndices())if(i>=emojiStart)successfulEmojis.add(i-emojiStart);
        int failureCount = result.failures();
        try { server.execute(() -> {
            try { deliver(message, fallback, sender, caption, sourceConfig, sourceHandler, ready, failureCount,successfulEmojis); }
            finally { try{owned.deliveryComplete(ready.stream().map(ChatBackend.Prepared::id).toList(), cfg.cacheDeliveryGraceSeconds);}finally{completed.run();} }
        }); publicationQueued=true; }
        catch (RejectedExecutionException e) { cleanup(ready); }
        }finally{if(!publicationQueued)completed.run();}
    }

    private boolean authorized(MessageSnapshot message, PenguinConfig sourceConfig, Object sourceHandler) {
        var penguin = PenguinServerMod.INSTANCE;
        var cfg = penguin.getConfig();
        return active && dev.penguinsuit.config.RuntimeSettings.incoming(message.group(),message.user(),!message.media().isEmpty()) && penguin.getServer() == server && cfg == sourceConfig && penguin.getCommandHandler() == sourceHandler
                && cfg.getChatPostChat() && (cfg.getBotGroups().isEmpty() || cfg.getBotGroups().contains(message.group()))
                && penguin.getState().isFullForwarding(message.group());
    }

    private void deliver(MessageSnapshot message, String fallback, String sender, String caption, PenguinConfig sourceConfig,
                         Object sourceHandler, List<ChatBackend.Prepared> ready, int failures,Set<Integer> successfulEmojis) {
        if (!active) return;
        if (!authorized(message, sourceConfig, sourceHandler)) {
            try { maintenance.execute(() -> cleanup(ready)); } catch (RejectedExecutionException ignored) {}
            return;
        }
        if(!config.forwarding.qqImages){
            cleanup(ready);
            PenguinSuit.deliverIncomingText(message, caption, sourceConfig, sourceHandler);
            return;
        }
        String groupId = ready.size() > 1 ? ChatBackend.newGroupId() : "";
        dev.penguinsuit.mention.MentionService.Incoming mention = message.mention();
        var mentionService = PenguinSuit.mentions();
        if (mention != null) {
            try {
                if (mentionService == null) throw new dev.penguinsuit.mention.MemberDirectory.Invalid("提及服务已关闭");
                mention = mentionService.revalidate(mention);
                fallback = mentionService.incomingComponent(message,mention,message.mediaFallbackSuffix()).getString();
                caption = AttachmentResolver.cleanCaption(mention.rendered().legacy());
            } catch (dev.penguinsuit.mention.MemberDirectory.Invalid e) {
                if (mentionService != null) mentionService.incomingFailure(message,e.getMessage());
                cleanup(ready); return;
            }
        }
        final var renderedMention = mention;
        String mediaCaption=message.emojis().caption(mention==null?null:mention.rendered(),caption,successfulEmojis);
        var replies=PenguinSuit.replies();
        boolean replyEnabled=replies!=null&&replies.enabled()&&mentionService!=null&&mentionService.isScope(message.group());
        final dev.penguinsuit.reply.ReplyService.Inbound inbound;
        try{inbound=replyEnabled?replies.prepareIncoming(message,caption,ready.stream().map(ChatBackend.Prepared::id).toList()):null;}
        catch(dev.penguinsuit.mention.MemberDirectory.Invalid|dev.penguinsuit.reply.ReplyLedger.Invalid e){
            if(mentionService!=null)mentionService.incomingFailure(message,e.getMessage());cleanup(ready);return;
        }
        if(replyEnabled&&inbound==null){cleanup(ready);return;}
        if(inbound!=null){
            String body=inbound.rendered().legacy();
            String original=mention==null?dev.penguinsuit.reply.HiddenReplyId.plain(caption):mention.rendered().legacy();
            if(!mediaCaption.equals(caption)&&!original.isEmpty()&&body.endsWith(original))body=body.substring(0,body.length()-original.length())
                    +(mention==null?dev.penguinsuit.reply.HiddenReplyId.plain(mediaCaption):mediaCaption);
            caption=dev.penguinsuit.reply.HiddenReplyId.decorate(body,inbound.entry().id());
        }else caption=mediaCaption;
        final String fallbackLine=fallback;
        List<UUID> replyViewers=new ArrayList<>();
        List<DeliveryRouter.Recipient> recipients = new ArrayList<>();
        for (var player : List.copyOf(server.getPlayerList().getPlayers())) recipients.add(new DeliveryRouter.Recipient() {
            public boolean supportsMedia() { return ChatBackend.capable(player); }
            public void text(String text) { String suffix=message.mediaFallbackSuffix()+(text.startsWith(fallbackLine)?text.substring(fallbackLine.length()):"");
                player.sendSystemMessage(inbound!=null?replies.fallback(inbound,message,suffix):renderedMention == null ? Component.literal(text)
                    : mentionService.incomingComponent(message,renderedMention,suffix));if(inbound!=null)replyViewers.add(player.getUUID()); }
            public void image(ChatBackend.Prepared image, String name, String body, String group, int index, int count) {
                if(inbound==null)ChatBackend.image(player, image, name, body, group, index, count);
                else{ChatBackend.image(player,image,name,body,group,index,count,inbound.quote());replyViewers.add(player.getUUID());}
            }
        });
        var summary = DeliveryRouter.deliver(recipients, ready, fallback, sender, caption, groupId, failures, failure ->
                PenguinSuit.LOG.warn("Media delivery failed after {} packet(s): {}",
                        failure.sentImages(), failure.cause().getClass().getSimpleName()));
        MediaDeliveryLog.write(PenguinSuit.LOG, sender, ready.size(), failures, summary);
        if(message.emojis().count()>0)PenguinSuit.LOG.info("[emoji] QQ->MC <{}> faces={} unknown={} imagePackets={} textRecipients={}",
                MediaDeliveryLog.safeSender(sender),message.emojis().count(),message.emojis().unknown(),summary.imagePackets(),summary.textRecipients());
        if(inbound!=null)replies.incomingPublished(inbound,new HashSet<>(replyViewers));
        else if (renderedMention != null && summary.recipients() > summary.failedRecipients()) mentionService.incomingDelivered(renderedMention);
    }
    private void cleanup(List<ChatBackend.Prepared> prepared) {
        for (var image : prepared) {
            try { owned.delete(image.id()); }
            catch (IOException | RuntimeException | LinkageError e) {
                owned.deliveryComplete(List.of(image.id()), 0);
                PenguinSuit.LOG.warn("Could not clean owned media entry; retaining ownership for retry");
            }
        }
    }
    private void mediaRemoved(String id) {
        if (!active) return;
        try { server.execute(() -> notifyDeleted(List.of(id))); }
        catch (RejectedExecutionException ignored) { /* Server is stopping. */ }
    }
    private void notifyDeleted(List<String> ids) {
        if (!active) return;
        for (var player : server.getPlayerList().getPlayers())
            for (String id : ids) {
                try { ChatBackend.deleted(player, id); }
                catch (RuntimeException e) { PenguinSuit.LOG.warn("Media deletion notification failed"); }
            }
    }
    public void delete(String id, java.util.function.Consumer<String> result) {
        try {
            workers.execute(() -> {
                String response;
                try { response = owned.delete(id) ? "已删除桥接图片：" + id : "该 ID 不属于桥接缓存。"; }
                catch (IOException e) { response = "删除失败，请检查缓存索引权限。"; }
                String text = response;
                if (active) server.execute(() -> result.accept(text));
            });
        } catch (RejectedExecutionException e) { result.accept("桥接队列已满或正在关闭。"); }
    }
    public void manageCache(String action,java.util.function.Consumer<String> response){
        try{workers.execute(()->{String result;try{
            if(action.equals("preview")){var all=owned.preview(config,false);var expired=owned.preview(config,true);result="桥接缓存 "+owned.ids().size()+" 张 / "+owned.bytes()+" 字节；可清理 "+all.images()+" 张 / "+all.bytes()+" 字节，其中过期 "+expired.images()+" 张；保护中 "+all.protectedImages()+" 张";}
            else result="已清理 "+(action.equals("expired")?owned.expire(config,System.currentTimeMillis()).size():owned.clearUnprotected())+" 张桥接图片（保护中的图片保留）";
        }catch(Exception e){result="缓存操作失败："+e.getClass().getSimpleName();}String text=result;if(active)server.execute(()->response.accept(text));});}
        catch(RejectedExecutionException e){response.accept("媒体队列已满，请稍后重试");}
    }
    public void reconfigure(BridgeConfig next) throws IOException {
        if (next.workerThreads != config.workerThreads || next.queueCapacity != config.queueCapacity)
            throw new IOException("workerThreads/queueCapacity changes require restart");
        config = next; nextCleanup=0;
    }
    public List<String> ids() { return owned.ids(); }
    public boolean enabled(){return active&&config.enabled;}
    public String status() {
        return "26.2 bridge " + (config.enabled ? "enabled" : "disabled") + ", accepted=" + accepted.get()
                + ", duplicate=" + duplicate.get() + ", failedImages=" + failed.get() + ", queued=" + workers.getQueue().size()
                + ", pending="+admission.pending()+", cache=" + owned.ids().size() + " images/" + owned.bytes() + " bytes";
    }
    @Override public void close() {
        active = false; workers.shutdownNow(); maintenance.shutdownNow(); owned.close();
    }
    private static String bounded(String value, int limit) { return value.length() <= limit ? value : value.substring(0, limit); }
}
