package dev.penguinsuit.bridge;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.penguinsuit.config.BridgeConfig;
import dev.penguinsuit.media.MediaException;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/** Only touches IDs persisted by this bridge; never runs Chat Remastered's global eviction. */
public final class OwnedMediaIndex {
    public interface Cache { void store(String id, byte[] bytes) throws IOException; void delete(String id) throws IOException; }
    public record Entry(String id, long bytes, long created) {}
    private final Path file;
    private final Cache cache;
    private final LongSupplier clock;
    private final Consumer<String> removed;
    private final LinkedHashMap<String, Entry> entries = new LinkedHashMap<>();
    private final ConcurrentHashMap<String, Long> protectedUntil = new ConcurrentHashMap<>();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private volatile List<String> ids = List.of();
    private volatile long bytes;
    private boolean closed;

    public OwnedMediaIndex(Path file, Cache cache) throws IOException {
        this(file, cache, System::currentTimeMillis, id -> {});
    }
    public OwnedMediaIndex(Path file, Cache cache, LongSupplier clock, Consumer<String> removed) throws IOException {
        this.file = file; this.cache = cache;
        this.clock = Objects.requireNonNull(clock); this.removed = Objects.requireNonNull(removed);
        if (Files.exists(file)) {
            if (Files.size(file) > 1024 * 1024) throw new IOException("Owned-media index exceeds size limit");
            try {
                Entry[] saved = GSON.fromJson(Files.readString(file), Entry[].class);
                if (saved == null || saved.length > 2000) throw new IOException("Invalid owned-media index");
                for (Entry entry : saved) {
                    if (entry == null || !validId(entry.id()) || entry.bytes() < 1 || entry.bytes() > 32L * 1024 * 1024
                            || entry.created() < 0 || entries.putIfAbsent(entry.id(), entry) != null)
                        throw new IOException("Invalid owned-media entry");
                }
            } catch (RuntimeException e) { throw new IOException("Invalid owned-media JSON", e); }
        }
        refresh();
    }
    public static boolean validId(String id) { return id != null && id.matches("pmb_[0-9a-f]{32}"); }
    public synchronized String store(byte[] data, BridgeConfig cfg) throws IOException {
        if (closed) throw new MediaException("cancelled");
        if (data.length < 1 || data.length > cfg.maxDownloadBytes || data.length > cfg.maxOwnedCacheBytes)
            throw new MediaException("file_too_large");
        makeRoom(data.length, cfg);
        String id = "pmb_" + UUID.randomUUID().toString().replace("-", "");
        entries.put(id, new Entry(id, data.length, clock.getAsLong()));
        // Persist ownership first: even a crash during cache storage leaves a known, cleanable ID.
        try { persist(); }
        catch (IOException e) { entries.remove(id); refresh(); throw e; }
        refresh();
        try { cache.store(id, data); }
        catch (IOException | RuntimeException | LinkageError e) {
            MediaException failure = new MediaException("cache_write_failed");
            failure.addSuppressed(e);
            try { delete(id); } catch (IOException | RuntimeException | LinkageError cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
        // A whole message is prepared before delivery. Another worker must not evict its group head.
        protectedUntil.put(id, Long.MAX_VALUE);
        return id;
    }
    private void makeRoom(long incomingBytes, BridgeConfig cfg) throws IOException {
        long projectedBytes = bytes + incomingBytes;
        int projectedCount = entries.size() + 1;
        if (projectedBytes <= cfg.maxOwnedCacheBytes && projectedCount <= cfg.maxOwnedImages) return;
        long now = clock.getAsLong();
        List<String> victims = new ArrayList<>();
        for (Entry entry : entries.values().stream().sorted(Comparator.comparingLong(Entry::created)).toList()) {
            if (isProtected(entry.id(), now)) continue;
            victims.add(entry.id());
            projectedBytes -= entry.bytes(); projectedCount--;
            if (projectedBytes <= cfg.maxOwnedCacheBytes && projectedCount <= cfg.maxOwnedImages) break;
        }
        // Do not delete a partial set when protected images would still prevent admission.
        if (projectedBytes > cfg.maxOwnedCacheBytes || projectedCount > cfg.maxOwnedImages)
            throw new MediaException("bridge_cache_busy");
        for (String victim : victims) delete(victim);
    }
    private boolean isProtected(String id, long now) {
        Long deadline = protectedUntil.get(id);
        return deadline != null && (deadline == Long.MAX_VALUE || deadline > now);
    }
    /** No disk I/O or index monitor: safe to call after delivery on the server thread. */
    public void deliveryComplete(Collection<String> delivered, int graceSeconds) {
        long deadline = clock.getAsLong() + Math.max(0, graceSeconds) * 1000L;
        for (String id : delivered) protectedUntil.computeIfPresent(id, (key, previous) ->
                previous == Long.MAX_VALUE ? deadline : previous);
    }
    public synchronized boolean delete(String id) throws IOException {
        if (!validId(id) || !entries.containsKey(id)) return false;
        cache.delete(id);
        Entry entry = entries.remove(id);
        try { persist(); }
        catch (IOException e) {
            // Keep ownership and budget reserved until a subsequent cleanup can persist successfully.
            entries.put(id, entry); refresh(); throw e;
        }
        protectedUntil.remove(id); refresh();
        removed.accept(id);
        return true;
    }
    public synchronized List<String> expire(BridgeConfig cfg, long now) throws IOException {
        List<String> expired = new ArrayList<>();
        for (Entry e : List.copyOf(entries.values())) {
            if (!isProtected(e.id(), now) && now - e.created() >= cfg.cacheTtlMinutes * 60_000L) {
                delete(e.id()); expired.add(e.id());
            }
        }
        return List.copyOf(expired);
    }
    public record CleanupPreview(int images,long bytes,int protectedImages){}
    public synchronized CleanupPreview preview(BridgeConfig cfg,boolean expiredOnly){
        long now=clock.getAsLong();int count=0,protectedCount=0;long total=0;
        for(Entry e:entries.values()){
            if(isProtected(e.id(),now)){protectedCount++;continue;}
            if(!expiredOnly||now-e.created()>=cfg.cacheTtlMinutes*60000L){count++;total+=e.bytes();}
        }
        return new CleanupPreview(count,total,protectedCount);
    }
    public synchronized int clearUnprotected()throws IOException{
        int count=0;long now=clock.getAsLong();
        for(String id:List.copyOf(entries.keySet()))if(!isProtected(id,now)&&delete(id))count++;
        return count;
    }
    public List<String> ids() { return ids; }
    public long bytes() { return bytes; }
    public synchronized void close() { closed = true; }
    private void refresh() { ids = List.copyOf(entries.keySet()); bytes = entries.values().stream().mapToLong(Entry::bytes).sum(); }
    private void persist() throws IOException {
        Files.createDirectories(file.getParent());
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(temporary, GSON.toJson(entries.values()));
        try { Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (AtomicMoveNotSupportedException e) { Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING); }
    }
}
