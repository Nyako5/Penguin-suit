package dev.penguinsuit.reverse;

import com.google.gson.Gson;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Small durable cleanup journal; stores owned IDs only, never image bytes or signed URLs. */
public final class QqMaterialLedger {
    public record Entry(String scope, String id, long retryAfter, long createdAt, long protectedUntil) {}
    private static final Gson JSON = new Gson();
    private final Path file;
    private final LinkedHashMap<String, Entry> entries = new LinkedHashMap<>();
    public QqMaterialLedger(Path file) throws IOException {
        this.file = file;
        if (file != null && Files.exists(file)) {
            if (Files.size(file) > 131072) throw new IOException("material_journal_size");
            try {
                Entry[] saved = JSON.fromJson(Files.readString(file), Entry[].class);
                if (saved == null || saved.length > 100) throw new IOException("material_journal_entries");
                for (Entry e : saved) {
                    if (e == null || !e.scope().matches("[0-9a-f]{64}") || !validId(e.id()) || e.retryAfter() < 0
                            || e.createdAt() < 0 || e.protectedUntil() < 0
                            || entries.putIfAbsent(e.scope()+"/"+e.id(), e) != null) throw new IOException("material_journal_invalid");
                }
            } catch (RuntimeException e) { throw new IOException("material_journal_invalid"); }
        }
    }
    public static boolean validId(String id) { return id != null && id.matches("[A-Za-z0-9_-]{1,256}"); }
    public static String scope(String appId) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(appId.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    public synchronized void track(String scope, String id, long retryAfter) throws IOException {
        track(scope,id,retryAfter,0,0);
    }
    public synchronized void track(String scope, String id, long retryAfter, long createdAt, long protectedUntil) throws IOException {
        if (!scope.matches("[0-9a-f]{64}") || !validId(id)) throw new IOException("material_journal_invalid");
        if(retryAfter<0||createdAt<0||protectedUntil<0) throw new IOException("material_journal_invalid");
        String key = scope+"/"+id;
        if (!entries.containsKey(key) && entries.size() >= 100) throw new IOException("material_journal_full");
        Entry previous = entries.put(key, new Entry(scope,id,retryAfter,createdAt,protectedUntil));
        try { save(); } catch (IOException e) { if(previous == null) entries.remove(key); else entries.put(key,previous); throw e; }
    }
    public synchronized void remove(String scope, String id) throws IOException {
        String key=scope+"/"+id; Entry previous=entries.remove(key);
        if(previous == null) return;
        try { save(); } catch(IOException e) { entries.put(key,previous); throw e; }
    }
    public synchronized List<Entry> due(String scope, long now) {
        return entries.values().stream().filter(e -> e.scope().equals(scope) && e.retryAfter() <= now && e.protectedUntil() <= now)
                .sorted(Comparator.comparingLong(Entry::createdAt)).toList();
    }
    public synchronized Entry get(String scope,String id) { return entries.get(scope+"/"+id); }
    public synchronized Entry oldestEvictable(String scope,long now) {
        return oldestEvictable(scope,now,Set.of());
    }
    public synchronized Entry oldestEvictable(String scope,long now,Set<String> pinned) {
        return entries.values().stream().filter(e->e.scope().equals(scope)&&e.protectedUntil()<=now&&!pinned.contains(e.id()))
                .min(Comparator.comparingLong(Entry::createdAt)).orElse(null);
    }
    public synchronized int count(String scope) { return (int)entries.values().stream().filter(e->e.scope().equals(scope)).count(); }
    public synchronized int size() { return entries.size(); }
    private void save() throws IOException {
        if(file == null) return;
        Files.createDirectories(file.toAbsolutePath().getParent());
        Path tmp=file.resolveSibling(file.getFileName()+".tmp");
        Files.writeString(tmp,JSON.toJson(entries.values()));
        try { Files.move(tmp,file,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE); }
        catch(AtomicMoveNotSupportedException e) { Files.move(tmp,file,StandardCopyOption.REPLACE_EXISTING); }
    }
}
