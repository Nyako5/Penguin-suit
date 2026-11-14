package dev.penguinsuit.reverse;

import com.huhobot.penguin.PenguinServerMod;
import com.huhobot.penguin.config.PenguinConfig;
import com.huhobot.penguin.filter.TextFilter;
import com.huhobot.penguin.qq.QQClient;
import dev.errnicraft.chatremastered.server.ChatRemasteredState;
import dev.errnicraft.chatremastered.server.LuckPermsBridge;
import dev.errnicraft.chatremastered.server.groups.ChatGroupManager;
import dev.errnicraft.chatremastered.server.groups.GroupNetwork;
import dev.errnicraft.chatremastered.server.moderation.ModerationActions;
import dev.errnicraft.chatremastered.tcp.ImageTcpServer;
import dev.penguinsuit.PenguinSuit;
import dev.penguinsuit.bridge.MediaSenderFormat;
import dev.penguinsuit.bridge.MediaDeliveryLog;
import dev.penguinsuit.config.BridgeConfig;
import dev.penguinsuit.media.MediaInspector;
import dev.penguinsuit.mixin.QQClientAccess;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import kotlin.Unit;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;

/** Game -> QQ only. Does not own/delete native cache files and never handles incoming pmb_ images. */
public final class ReverseBridge implements AutoCloseable {
    private record Context(PenguinConfig config, QQClient client, BridgeConfig bridgeConfig) {}
    private final MinecraftServer server;
    private final OutboundAlbums<Context> albums = new OutboundAlbums<>();
    private final OutboundBudget budget = new OutboundBudget();
    private final Set<Work> live = ConcurrentHashMap.newKeySet();
    private final QqMaterialLedger materials;
    private final ScheduledExecutorService worker;
    private final AtomicLong accepted = new AtomicLong(), sent = new AtomicLong(), failed = new AtomicLong(), textFallbacks = new AtomicLong();
    private volatile BridgeConfig config;
    private volatile boolean active = true;
    private volatile long rateLimitedUntil;

    public ReverseBridge(MinecraftServer server, BridgeConfig config, java.nio.file.Path materialJournal) throws java.io.IOException {
        this.server = server; this.config = config;
        this.materials = new QqMaterialLedger(materialJournal);
        worker = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "penguin-media-outbound"); t.setDaemon(true); return t;
        });
        worker.scheduleWithFixedDelay(this::tick, 250, 250, TimeUnit.MILLISECONDS);
    }
    public void accepted(String id, ChatRemasteredState.PendingBroadcast p) {
        if (!active || p == null || id == null || id.startsWith("pmb_")) return;
        // Read native acceptance metadata at the hook; other integrations may remove their pending maps.
        var image = new OutboundAlbums.Image(id, p.senderUuid(), p.sender(), p.caption() == null ? "" : p.caption(),
                p.groupId() == null ? "" : p.groupId(), p.groupIndex(), p.groupCount());
        try { server.execute(() -> acceptOnServer(image)); }
        catch (RejectedExecutionException ignored) {}
    }
    private void acceptOnServer(OutboundAlbums.Image image) {
        try {
            var penguin = PenguinServerMod.INSTANCE;
            var context = new Context(penguin.getConfig(), penguin.getQqClient(), config);
            if (!allowedOnServer(context, image, null)) return;
            var result = albums.offer(image, context, System.currentTimeMillis(), config.gameToQqQueueCapacity, config.maxImagesPerMessage);
            if (result == OutboundAlbums.Admission.ACCEPTED) accepted.incrementAndGet();
            else if (result != OutboundAlbums.Admission.DUPLICATE) fail(image, "admission_" + result.name().toLowerCase(Locale.ROOT));
        } catch (RuntimeException | LinkageError e) { fail(image, e.getClass().getSimpleName()); }
    }
    private boolean allowedOnServer(Context context, OutboundAlbums.Image image, String target) {
        var mentions = PenguinSuit.mentions();
        if (mentions != null) mentions.refresh();
        if (mentions != null && !mentions.images().valid(image.id())) return false;
        var replies=PenguinSuit.replies();
        if(replies!=null&&replies.imagePlan(image.id())!=null&&(!replies.imagePlan(image.id()).forward()||!replies.validImage(image.id())))return false;
        var cfg = config; var penguin = PenguinServerMod.INSTANCE;
        if (!active || cfg != context.bridgeConfig() || !cfg.enabled || !cfg.gameToQqEnabled || penguin.getServer() != server
                || ChatRemasteredState.currentServer != server || penguin.getConfig() != context.config()
                || penguin.getQqClient() != context.client() || !context.config().getChatPostChat()
                || context.config().getBotAppId().isBlank() || context.config().getBotSecret().isBlank()) return false;
        var targets = OutboundPolicy.targets(context.config().getBotGroups(), cfg.blockedGroups);
        if (targets.isEmpty() || (target != null && !targets.contains(target))) return false;
        var player = server.getPlayerList().getPlayer(image.player());
        return player != null && dev.penguinsuit.config.RuntimeSettings.outgoing(player.getUUID(),player.getGameProfile().name(),true) && ChatRemasteredState.hasModInstalled(image.player())
                && !ChatRemasteredState.isPhotoBanned(image.player()) && !ModerationActions.isEffectivelyMuted(image.player())
                && LuckPermsBridge.check(image.player(), "chatremastered.photo.send", true)
                && !(GroupNetwork.isGroupChannel(image.player()) && ChatGroupManager.groupOf(image.player()) != null)
                && image.player().equals(ChatRemasteredState.imageOwners.get(image.id()));
    }
    private boolean allowed(Context context, OutboundAlbums.Image image, String group) {
        if (!active) return false;
        try { return server.submit(() -> allowedOnServer(context, image, group)).get(5, TimeUnit.SECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
        catch (Exception e) { return false; }
    }
    private boolean allowedAlbum(Context context,List<OutboundAlbums.Image> images,String group) {
        if(!active) return false;
        try { return server.submit(()->images.stream().allMatch(image->allowedOnServer(context,image,group))).get(5,TimeUnit.SECONDS); }
        catch(InterruptedException e) { Thread.currentThread().interrupt(); return false; }
        catch(Exception e) { return false; }
    }
    private void tick() {
        if (!active) return;
        try {
            for (Work work : List.copyOf(live)) work.expireWaiting();
            var result = albums.poll(ImageTcpServer::hasCached, System.currentTimeMillis(), config.gameToQqTimeoutSeconds * 1000L);
            for (var batch : result.expired()) fail(batch.images().getFirst(), "upload_timeout");
            for (var batch : result.ready()) prepare(batch);
        } catch (RuntimeException | LinkageError e) {
            PenguinSuit.LOG.warn("[Penguin suit] MC->QQ worker failed: {}", e.getClass().getSimpleName());
        }
    }
    private void prepare(OutboundAlbums.Batch<Context> batch) {
        var first = batch.images().getFirst(); var cfg = config;
        Work created = null;
        if (!allowed(batch.context(), first, null)) return;
        var mentions = PenguinSuit.mentions();
        var mentionPlan = mentions == null ? null : mentions.images().plan(first.id());
        var replies=PenguinSuit.replies();var replyPlan=replies==null?null:replies.imagePlan(first.id());
        String caption = OutboundPolicy.caption(replyPlan!=null?replyPlan.source().original():mentionPlan == null ? first.caption() : mentionPlan.message().original(), batch.context().config().getChatStartWith());
        if (caption == null) return;
        if (System.currentTimeMillis() < rateLimitedUntil) { fail(first, "qq_rate_limited"); return; }
        List<byte[]> data = new ArrayList<>(); long total = 0;
        OutboundBudget.Lease lease = null;
        try {
            for (var image : batch.images()) {
                if (!allowed(batch.context(), image, null)) return;
                byte[] bytes = ImageTcpServer.getCachedBytes(image.id());
                if (bytes == null || bytes.length > cfg.gameToQqMaxBytes) throw new QqMediaApi.Failure("image_size_or_missing", 0, 0);
                if(cfg.media.outgoingGifMode.equals("STATIC")){var limits=new BridgeConfig();limits.maxDownloadBytes=cfg.gameToQqMaxBytes;limits.maxDimension=cfg.maxDimension;limits.maxPixels=cfg.maxPixels;limits.maxGifFrames=cfg.maxGifFrames;limits.maxGifDecodedPixels=cfg.maxGifDecodedPixels;
                    bytes=dev.penguinsuit.media.GifMode.apply(bytes,"STATIC",limits,cfg.gameToQqMaxBytes);}
                total += bytes.length;
                if (total > cfg.gameToQqMaxPendingBytes) throw new QqMediaApi.Failure("album_size", 0, 0);
                data.add(bytes);
            }
            lease = budget.reserve(total, cfg.gameToQqMaxPendingBytes, cfg.gameToQqQueueCapacity);
            if (lease == null) throw new QqMediaApi.Failure("pending_limit", 0, 0);
            // Decode validation is off the server and shared bot queue; original PNG/JPEG/GIF bytes are preserved.
            var inspectCfg = new BridgeConfig();
            inspectCfg.maxDownloadBytes = cfg.gameToQqMaxBytes; inspectCfg.maxDimension = cfg.maxDimension;
            inspectCfg.maxPixels = cfg.maxPixels; inspectCfg.maxGifFrames = cfg.maxGifFrames;
            inspectCfg.maxGifDecodedPixels = cfg.maxGifDecodedPixels;
            var inspector = new MediaInspector();
            List<MediaInspector.Info> dimensions = new ArrayList<>();
            for (byte[] bytes : data) dimensions.add(inspector.inspect(bytes, inspectCfg, true, cfg.maxDimension));
            var work = new Work(batch, List.copyOf(data), List.copyOf(dimensions), lease);
            created = work;
            live.add(work);
            lease = null;
            if(replyPlan!=null)work.enqueue(replyPlan.rendered().plain());
            else if (mentionPlan != null) work.enqueue(mentionPlan.published().plain());
            else TextFilter.INSTANCE.audit(caption, batch.context().config(), filtered -> {
                work.enqueue(filtered); return Unit.INSTANCE;
            });
        } catch (Exception | LinkageError e) {
            if (created != null) created.close();
            if (lease != null) lease.close();
            fail(first, reason(e));
        }
    }
    private final class Work implements AutoCloseable {
        final OutboundAlbums.Batch<Context> batch;
        final AtomicReference<List<byte[]>> data;
        final List<MediaInspector.Info> dimensions;
        final OutboundBudget.Lease lease;
        final AtomicBoolean queued = new AtomicBoolean();
        final AtomicInteger phase = new AtomicInteger(); // 0 waiting, 1 running, 2 closed
        final long created = System.currentTimeMillis();
        Work(OutboundAlbums.Batch<Context> batch, List<byte[]> data, List<MediaInspector.Info> dimensions, OutboundBudget.Lease lease) {
            this.batch = batch; this.data = new AtomicReference<>(data); this.dimensions = dimensions; this.lease = lease;
        }
        void enqueue(String filtered) {
            if (!queued.compareAndSet(false, true)) return;
            if (!active || data.get() == null) { close(); return; }
            try {
                boolean accepted = ((QQClientAccess)(Object) batch.context().client()).penguinsuit$enqueue(() -> {
                    send(filtered); return Unit.INSTANCE;
                });
                if (!accepted) { fail(batch.images().getFirst(), "penguin_queue_full"); close(); }
            } catch (RuntimeException | LinkageError e) { fail(batch.images().getFirst(), reason(e)); close(); }
        }
        void send(String filtered) {
            if (!phase.compareAndSet(0, 1)) return;
            var first = batch.images().getFirst(); int delivered = 0, failures = 0, skipped = 0, textOnly = 0, messages = 0;
            try {
                List<byte[]> bytes = data.get();
                if (bytes == null || !active) return;
                if (System.currentTimeMillis() - created > config.gameToQqTimeoutSeconds * 1000L) {
                    fail(first, "queue_timeout"); return;
                }
                var context = batch.context();
                var targets = OutboundPolicy.targets(context.config().getBotGroups(), config.blockedGroups);
                var api = new QqMediaApi(new PenguinQqTransport(context.client(), context.config().getBotAppId()),
                        materials, context.config().getBotAppId(), warning ->
                        PenguinSuit.LOG.warn("[Penguin suit] MC->QQ material cleanup: {}", warning),
                        System::currentTimeMillis,config.gameToQqMaterialMaxImages,
                        config.gameToQqMaterialTtlMinutes*60000L,config.gameToQqMaterialGraceSeconds*1000L);
                boolean stop = false;
                for (String group : targets) {
                    if (stop || !canSend(group)) { skipped+=batch.images().size(); continue; }
                    try {
                        String text=OutboundPolicy.message(context.config().getChatFromGame(),first.sender(),filtered,0);
                        var mentions=PenguinSuit.mentions();
                        String mentionBody=mentions==null?null:mentions.images().markdown(first.id(),group);
                        var replies=PenguinSuit.replies();
                        String markdown=replies==null?null:replies.imageMarkdown(first.id(),0);
                        if(markdown==null&&mentionBody!=null) {
                            String prefix=QqMarkdown.literalText(context.config().getChatStartWith());
                            if(!prefix.isEmpty()&&mentionBody.startsWith(prefix)) mentionBody=mentionBody.substring(prefix.length());
                            markdown=dev.penguinsuit.mention.MentionService.formatMarkdown(context.config().getChatFromGame().replace("{name}",first.sender()),mentionBody);
                        }
                        List<QqMediaApi.Image> images=new ArrayList<>();int placeholders=0;
                        for(int i=0;i<batch.images().size();i++) {
                            var info=dimensions.get(i);
                            if(info.gif()&&context.bridgeConfig().media.outgoingGifMode.equals("TEXT")) {images.add(QqMediaApi.Image.gifPlaceholder());placeholders++;}
                            else images.add(new QqMediaApi.Image(bytes.get(i),info.width(),info.height()));
                        }
                        // One native group upload becomes one QQ message, including its caption and mentions.
                        var receipt=markdown==null
                                ?api.sendAlbum(group,text,images,()->canSend(group))
                                :api.sendAlbumMarkdown(group,markdown,images,()->canSend(group),replies==null?"":replies.imageReference(first.id()));
                        delivered+=images.size();textOnly+=placeholders;messages++;
                        sent.addAndGet(images.size()-placeholders);textFallbacks.addAndGet(placeholders);
                        if(replies!=null) try { replies.imageAlbumReceipt(batch.images().stream().map(OutboundAlbums.Image::id).toList(),receipt); }
                        catch(java.io.IOException | RuntimeException e) {
                            // The message is already accepted. Do not retry or report an upload failure.
                            PenguinSuit.LOG.warn("[Penguin suit] MC->QQ album sent but reply receipt save failed: {}",reason(e));
                            notifyPlayer(first,"QQ 图片组已发送，但回复索引保存失败，请联系管理员查看日志。");
                        }
                    } catch(Exception | LinkageError e) {
                        failures+=batch.images().size();failed.addAndGet(batch.images().size());
                        PenguinSuit.LOG.warn("[Penguin suit] MC->QQ album failed: images={} reason={}",batch.images().size(),reason(e));
                        if(e instanceof QqMediaApi.Failure f&&(f.status==429||f.code==100017||f.code==40034100)) {
                            rateLimitedUntil=System.currentTimeMillis()+60000;stop=true;
                        }
                        if(e instanceof InterruptedException) {Thread.currentThread().interrupt();stop=true;}
                    }
                    if(!stop&&targets.size()>1) {
                        try {Thread.sleep(500);}catch(InterruptedException e){Thread.currentThread().interrupt();stop=true;}
                    }
                }
                String title = MediaDeliveryLog.safeSender(MediaSenderFormat.format(context.config().getChatFromGame(), first.sender()));
                if(dev.penguinsuit.config.BridgeLog.detailed("media"))PenguinSuit.LOG.info("[Penguin suit] MC->QQ <{}> [图片×{}] targetGroups={} sentMessages={} sentImages={} textFallbacks={} failedImages={} skippedImages={}",
                        title, batch.images().size(), targets.size(), messages, delivered-textOnly, textOnly, failures, skipped);
                else PenguinSuit.LOG.info("[media] MC->QQ <{}> messages={} sent={} failed={} skipped={}",title,messages,delivered,failures,skipped);
                if (failures > 0 || skipped > 0) notifyPlayer(first, "QQ 图片转发未全部完成，请联系管理员查看服务端日志。");
            } catch (RuntimeException | LinkageError e) {
                // An escaping task exception would terminate Penguin's shared send thread.
                fail(first, reason(e));
            } finally { close(); }
        }
        boolean canSend(String group) {
            return active&&System.currentTimeMillis()>=rateLimitedUntil
                    &&System.currentTimeMillis()-created<=batch.context().bridgeConfig().gameToQqTimeoutSeconds*1000L
                    &&allowedAlbum(batch.context(),batch.images(),group);
        }
        void expireWaiting() {
            boolean stale = batch.context().bridgeConfig() != config;
            if ((stale || System.currentTimeMillis() - created > config.gameToQqTimeoutSeconds * 1000L)
                    && phase.compareAndSet(0, 2)) {
                if (!stale) fail(batch.images().getFirst(), "queue_timeout");
                close();
            }
        }
        @Override public void close() { phase.set(2); data.set(null); lease.close(); live.remove(this); }
    }
    private static String reason(Throwable e) {
        return e instanceof QqMediaApi.Failure f ? f.getMessage() : e.getClass().getSimpleName();
    }
    private void fail(OutboundAlbums.Image image, String reason) {
        failed.incrementAndGet();
        PenguinSuit.LOG.warn("[Penguin suit] MC->QQ image not forwarded: {}", reason);
        notifyPlayer(image, "图片已留在游戏中，但本次未能转发到 QQ；请联系管理员查看日志。");
    }
    private void notifyPlayer(OutboundAlbums.Image image, String text) {
        if (!active) return;
        try { server.execute(() -> {
            var player = server.getPlayerList().getPlayer(image.player());
            if (active && player != null) player.sendSystemMessage(Component.literal(dev.penguinsuit.config.RuntimeSettings.text(config.messages.outgoingFailure,"message",text)));
        }); } catch (RejectedExecutionException ignored) {}
    }
    public void reconfigure(BridgeConfig config) {
        this.config = config; albums.clear();
        for (Work work : List.copyOf(live)) work.expireWaiting();
    }
    public String status() { return "MC->QQ " + (config.enabled&&config.gameToQqEnabled&&config.forwarding.gameToQq ? "enabled" : "disabled")
            + ", imagesEnabled="+config.forwarding.gameImages+", textFallbacks="+textFallbacks.get()+", acceptedImages=" + accepted.get() + ", sentImages=" + sent.get() + ", failedImages=" + failed.get()
            + ", pendingAlbums=" + albums.size() + ", queuedJobs=" + budget.jobs() + ", heldBytes=" + budget.bytes()
            + ", pendingMaterials=" + materials.size(); }
    @Override public void close() {
        active = false; worker.shutdownNow(); albums.clear();
        for (Work work : List.copyOf(live)) work.close();
    }
}
