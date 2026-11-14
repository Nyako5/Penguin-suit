package dev.penguinsuit.bridge;

import dev.penguinsuit.config.BridgeConfig;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.function.LongConsumer;

/** All accesses are under this object's monitor, including queue admission. */
public final class Admission {
    private final LinkedHashMap<String, Long> seen = new LinkedHashMap<>();
    private final LinkedHashMap<String, Long> senders = new LinkedHashMap<>();
    private final LinkedHashMap<String, Long> groups = new LinkedHashMap<>();
    private final Set<String> pending = new HashSet<>();
    public enum Result { ACCEPTED, DUPLICATE, PASS }
    /** now/slot use one monotonic millisecond clock. Reserve a future slot instead of losing burst media. */
    public synchronized Result submit(String key, String user, String group, long now, BridgeConfig cfg, LongConsumer enqueue) {
        seen.entrySet().removeIf(e -> !pending.contains(e.getKey()) && now - e.getValue() > cfg.dedupSeconds * 1000L);
        if (seen.containsKey(key)) return Result.DUPLICATE;
        if (pending.size() >= cfg.workerThreads + cfg.queueCapacity) return Result.PASS;
        String sender = group + "\n" + user;
        long slot=Math.max(now,Math.max(senders.getOrDefault(sender,Long.MIN_VALUE/2)+cfg.minSenderIntervalMillis,
                groups.getOrDefault(group,Long.MIN_VALUE/2)+cfg.minGroupIntervalMillis));
        try { enqueue.accept(slot); }
        catch (java.util.concurrent.RejectedExecutionException e) { return Result.PASS; }
        seen.put(key, now); pending.add(key);senders.put(sender, slot); groups.put(group, slot);
        trimSeen(); trim(senders); trim(groups);
        return Result.ACCEPTED;
    }
    /** Keep queued/preparing/publication-pending IDs protected even when the ordinary dedup TTL expires. */
    public synchronized void complete(String key,long now){if(pending.remove(key))seen.put(key,now);}
    public synchronized int pending(){return pending.size();}
    private void trimSeen(){
        var it=seen.keySet().iterator();while(seen.size()>4096&&it.hasNext())if(!pending.contains(it.next()))it.remove();
    }
    private static void trim(LinkedHashMap<String, Long> map) {
        while (map.size() > 4096) map.remove(map.keySet().iterator().next());
    }
}
