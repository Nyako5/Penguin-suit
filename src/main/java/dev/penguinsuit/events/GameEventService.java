package dev.penguinsuit.events;

import com.google.gson.JsonObject;
import com.huhobot.penguin.PenguinServerMod;
import com.huhobot.penguin.config.PenguinConfig;
import com.huhobot.penguin.qq.QQClient;
import dev.penguinsuit.PenguinSuit;
import dev.penguinsuit.config.BridgeConfig;
import dev.penguinsuit.mixin.QQClientAccess;
import dev.penguinsuit.reverse.OutboundPolicy;
import dev.penguinsuit.reverse.PenguinQqTransport;
import kotlin.Unit;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.scores.Team;
import java.nio.file.Path;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.*;

/** Server-originated notifications, independent of signed chat, media and reply state. */
public final class GameEventService implements AutoCloseable {
    private final MinecraftServer server;
    private final EventLanguage language;
    private volatile BridgeConfig config;
    private volatile boolean active = true;
    private final java.util.concurrent.ScheduledThreadPoolExecutor dispatch=new java.util.concurrent.ScheduledThreadPoolExecutor(1,r->{var t=new Thread(r,"penguin-game-event-dispatch");t.setDaemon(true);return t;});
    private long nextDispatch;

    private final java.util.concurrent.ConcurrentHashMap<String, Long> pending = new java.util.concurrent.ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong(), sent = new AtomicLong(), failed = new AtomicLong(), skipped = new AtomicLong();
    private final String session = java.util.UUID.randomUUID().toString().substring(0, 8);

    public GameEventService(MinecraftServer server, BridgeConfig config, Path directory) {
        this.server = server; this.config = config; this.language = new EventLanguage(directory); language.overrides(config.events.translations);
        if (needsLanguage(config)) language.start();
    }
    private static boolean needsLanguage(BridgeConfig c) {
        return c.enabled && c.gameToQqEnabled && c.forwarding.gameToQq && c.forwarding.gameEvents && c.gameEventsChinese && (c.advancementEventsEnabled || c.deathEventsEnabled);
    }
    public void reconfigure(BridgeConfig next) { config = next; dispatch.getQueue().clear();int cancelled=pending.size();pending.clear();nextDispatch=0;if(cancelled>0)PenguinSuit.LOG.info("[game-events] reload cancelled {} pending events",cancelled);language.overrides(next.events.translations); if (needsLanguage(next)) language.start(); }
    public EventLanguage language() { return language; }
    private boolean allowed(ServerPlayer player, BridgeConfig snapshot) {
        return active && snapshot.enabled && snapshot.gameToQqEnabled && snapshot.forwarding.gameToQq && snapshot.forwarding.gameEvents && dev.penguinsuit.config.RuntimeSettings.playerAllowed(player.getUUID(),player.getGameProfile().name()) && player.level().getServer() == server
                && !snapshot.fakePlayerFilter.excludes(player.getUUID(), player.getGameProfile().name());
    }
    public void advancement(ServerPlayer player, AdvancementHolder advancement) {
        try {
            var snapshot = config; var display = advancement.value().display().orElse(null);
            if (!snapshot.advancementEventsEnabled || !allowed(player, snapshot) || display == null || !display.shouldAnnounceChat()) return;
            if (snapshot.gameEventsRespectGameRules && !player.level().getGameRules().get(GameRules.SHOW_ADVANCEMENT_MESSAGES)) return;
            if(!advancementAllowed(snapshot,advancement.id().toString(),display.getType().name()))return;
            String type = switch (display.getType()) { case TASK -> "进度"; case GOAL -> "目标"; case CHALLENGE -> "挑战"; };
            String message = EventFormat.render(snapshot.events.advancementFormat(display.getType().name()), Map.of("player", player.getGameProfile().name(),
                    "type", type, "title", language.render(display.getTitle(), snapshot.gameEventsChinese), "id", advancement.id().toString()));
            publish("advancement", message, snapshot);
        } catch (RuntimeException | LinkageError e) { hookFailure("advancement", e); }
    }
    public static boolean advancementAllowed(BridgeConfig c,String id,String type){
        String ns=id.substring(0,id.indexOf(':'));var e=c.events;
        return e.advancementTypes.contains(type)&&(e.allowedAdvancements.isEmpty()||e.allowedAdvancements.contains(id))&&!e.excludedAdvancements.contains(id)
            &&(e.allowedNamespaces.isEmpty()||e.allowedNamespaces.contains(ns))&&!e.excludedNamespaces.contains(ns);
    }
    public void death(ServerPlayer player, Component deathMessage) {
        try {
            var snapshot = config;
            if (!snapshot.deathEventsEnabled || !allowed(player, snapshot)) return;
            if (snapshot.gameEventsRespectGameRules) {
                if (!player.level().getGameRules().get(GameRules.SHOW_DEATH_MESSAGES)) return;
                var team = player.getTeam();
                if (team != null && team.getDeathMessageVisibility() != Team.Visibility.ALWAYS) return;
            }
            publish("death", EventFormat.render(snapshot.deathEventFormat, Map.of("player", player.getGameProfile().name(),
                    "message", language.render(deathMessage, snapshot.gameEventsChinese))), snapshot);
        } catch (RuntimeException | LinkageError e) { hookFailure("death", e); }
    }
    public static void hookFailure(String kind, Throwable error) {
        PenguinSuit.LOG.warn("[game-events] {} hook failed; original game event retained: {}", kind, error.getClass().getSimpleName());
    }
    private void publish(String kind, String text, BridgeConfig snapshot) {
        String id = session + "-" + sequence.incrementAndGet();
        // Nullable fields: Kotlin lateinit getters throw when the bot has not been configured.
        PenguinConfig penguin = PenguinServerMod.config; QQClient client = PenguinServerMod.qqClient;
        if (penguin == null || client == null || penguin.getBotAppId().isBlank()) {
            skipped.incrementAndGet(); PenguinSuit.LOG.warn("[game-events] event={} kind={} skipped: bot unavailable; {}", id, kind, text); return;
        }
        var groups = OutboundPolicy.targets(penguin.getBotGroups(), snapshot.blockedGroups);
        if (groups.isEmpty()) { skipped.incrementAndGet(); PenguinSuit.LOG.info("[game-events] event={} kind={} skipped: no eligible group; {}", id, kind, text); return; }
        int target = 0;
        expirePending();
        for (String group : groups) {
            int ordinal = ++target;
            if (pending.size() >= (snapshot.events.queueCapacity==0?snapshot.gameToQqQueueCapacity:snapshot.events.queueCapacity)) {
                failed.incrementAndGet();
                PenguinSuit.LOG.warn("[game-events] event={} kind={} target={} rejected: event queue full; {}", id, kind, ordinal, text); continue;
            }
            String ticket = id + "/" + ordinal;
            long expires = System.nanoTime() + TimeUnit.SECONDS.toNanos(snapshot.events.timeoutSeconds==0?snapshot.gameToQqTimeoutSeconds:snapshot.events.timeoutSeconds);
            pending.put(ticket, expires);
            boolean queued;
            try {
                queued = enqueue(client,ticket,snapshot,() -> {
                    try {
                        boolean valid = server.submit(() -> active && config == snapshot && reserved(ticket)
                                && snapshot.enabled && snapshot.gameToQqEnabled
                                && PenguinServerMod.config == penguin && PenguinServerMod.qqClient == client
                                && PenguinServerMod.INSTANCE.getServer() == server && penguin.getBotGroups().contains(group)
                                && !snapshot.blockedGroups.contains(group)).get(5, TimeUnit.SECONDS);
                        if (!valid) { skipped.incrementAndGet(); PenguinSuit.LOG.info("[game-events] event={} target={} cancelled: expired or configuration/connection changed", id, ordinal); return Unit.INSTANCE; }
                        JsonObject body = new JsonObject(); body.addProperty("msg_type", 0); body.addProperty("content", text);
                        var response = new PenguinQqTransport(client, penguin.getBotAppId()).post("/v2/groups/" + URLEncoder.encode(group, StandardCharsets.UTF_8) + "/messages", body);
                        if (response.status() < 200 || response.status() >= 300 || (response.body().has("code") && response.body().get("code").getAsInt() != 0)
                                || !response.body().has("id") || response.body().get("id").getAsString().isBlank()) {
                            failed.incrementAndGet();
                            PenguinSuit.LOG.warn("[game-events] event={} kind={} target={} not confirmed: HTTP={} code={}", id, kind, ordinal,
                                    response.status(), response.body().has("code") ? EventFormat.clean(response.body().get("code").toString(), 32) : "none");
                        } else { sent.incrementAndGet(); if(dev.penguinsuit.config.BridgeLog.detailed("events"))PenguinSuit.LOG.info("[game-events] event={} kind={} target={} accepted", id, kind, ordinal); }
                    } catch (Exception e) {
                        if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                        failed.incrementAndGet(); PenguinSuit.LOG.warn("[game-events] event={} kind={} target={} failed: {}", id, kind, ordinal, e.getClass().getSimpleName());
                    } finally { pending.remove(ticket); }
                    return Unit.INSTANCE;
                });
            } catch (RuntimeException e) { pending.remove(ticket); failed.incrementAndGet(); hookFailure(kind, e); continue; }
            if (!queued) { pending.remove(ticket); failed.incrementAndGet(); PenguinSuit.LOG.warn("[game-events] event={} target={} rejected: Penguin queue unavailable; {}", id, ordinal, text); }
            else PenguinSuit.LOG.info("[game-events] event={} kind={} target={} queued; {}", id, kind, ordinal, text);
        }
    }
    private boolean enqueue(QQClient client,String ticket,BridgeConfig snapshot,kotlin.jvm.functions.Function0<Unit> task){
        if(snapshot.events.minIntervalMillis==0)return ((QQClientAccess)(Object)client).penguinsuit$enqueue(task);
        long now=System.nanoTime(),slot=Math.max(now,nextDispatch);
        Long expires=pending.get(ticket);if(expires==null||slot>=expires){PenguinSuit.LOG.warn("[game-events] delayed event rejected: scheduled slot exceeds timeout ticket={}",ticket);return false;}
        nextDispatch=slot+TimeUnit.MILLISECONDS.toNanos(snapshot.events.minIntervalMillis);
        dispatch.schedule(()->{
            if(!active||config!=snapshot||!reserved(ticket)){
                pending.remove(ticket);skipped.incrementAndGet();PenguinSuit.LOG.info("[game-events] delayed event cancelled: expired or configuration changed ticket={}",ticket);return;
            }
            try {
                if(!((QQClientAccess)(Object)client).penguinsuit$enqueue(task))throw new IllegalStateException("queue unavailable");
            }catch(RuntimeException e){pending.remove(ticket);failed.incrementAndGet();PenguinSuit.LOG.warn("[game-events] delayed event rejected ticket={} reason={}",ticket,e.getClass().getSimpleName());}
        },Math.max(0,slot-now),TimeUnit.NANOSECONDS);return true;
    }
    private void expirePending() {
        long now = System.nanoTime();
        pending.forEach((ticket, expires) -> { if (now >= expires && pending.remove(ticket, expires))
            PenguinSuit.LOG.warn("[game-events] ticket={} expired; reservation released", ticket); });
    }
    private boolean reserved(String ticket) {
        Long deadline = pending.get(ticket);
        return deadline != null && System.nanoTime() < deadline;
    }
    public String status() { expirePending(); return "Game events: enabled=" + (active && config.enabled && config.gameToQqEnabled && config.forwarding.gameToQq && config.forwarding.gameEvents)
            + ", advancements=" + config.advancementEventsEnabled + ", deaths=" + config.deathEventsEnabled
            + ", fakeFilter=" + config.fakePlayerFilter.enabled + ", language=" + (config.gameEventsChinese && language.ready() ? "zh_cn" : "server")
            + ", sent=" + sent.get() + ", failed=" + failed.get() + ", skipped=" + skipped.get() + ", pending=" + pending.size(); }
    @Override public void close() { active = false; pending.clear(); dispatch.shutdownNow(); language.close(); }
}
