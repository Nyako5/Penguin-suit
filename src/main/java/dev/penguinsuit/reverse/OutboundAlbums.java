package dev.penguinsuit.reverse;

import java.util.*;
import java.util.function.Predicate;

/** Bounded assembly of native uploads. Album IDs are scoped to authenticated player UUIDs. */
public final class OutboundAlbums<C> {
    public record Image(String id, UUID player, String sender, String caption, String album, int index, int count) {}
    public record Batch<C>(C context, List<Image> images) {}
    public record Poll<C>(List<Batch<C>> ready, List<Batch<C>> expired) {}
    public enum Admission { ACCEPTED, DUPLICATE, INVALID, FULL }
    private static final long DEDUP_MS = 600_000;
    private static final int MAX_SEEN = 4096;
    private final Map<String, Pending<C>> pending = new LinkedHashMap<>();
    private final Map<String, Long> seen = new HashMap<>(), finished = new HashMap<>();
    private static final class Pending<C> {
        final C context; final Image[] slots; final long created;
        Pending(C context, int count, long created) { this.context = context; slots = new Image[count]; this.created = created; }
        Batch<C> snapshot() { return new Batch<>(context, Arrays.stream(slots).filter(Objects::nonNull).toList()); }
    }
    public static boolean valid(Image image, int maxImages) {
        return image != null && image.player() != null && image.sender() != null && image.sender().length() <= 128
                && image.id() != null && image.id().matches("[A-Za-z0-9_-]{1,128}") && !image.id().startsWith("pmb_")
                && image.caption() != null && image.caption().length() <= 8192 && image.album() != null
                && (image.album().isEmpty() || image.album().matches("[A-Za-z0-9_-]{1,128}"))
                && image.count() >= 1 && image.count() <= maxImages && image.index() >= 0 && image.index() < image.count()
                && (image.count() == 1 || !image.album().isEmpty());
    }
    private static String key(Image image) {
        return image.player() + (image.album().isEmpty() ? "/image/" + image.id() : "/album/" + image.album());
    }
    public synchronized Admission offer(Image image, C context, long now, int capacity, int maxImages) {
        if (!valid(image, maxImages)) return Admission.INVALID;
        prune(now);
        String key = key(image);
        if (seen.containsKey(image.id()) || finished.containsKey(key)) return Admission.DUPLICATE;
        if (seen.size() >= MAX_SEEN || finished.size() >= MAX_SEEN) return Admission.FULL;
        Pending<C> batch = pending.get(key);
        if (batch == null) {
            if (pending.size() >= capacity) return Admission.FULL;
            batch = new Pending<>(context, image.count(), now);
            pending.put(key, batch);
        } else if (batch.slots.length != image.count() || !Objects.equals(batch.context, context)) {
            return Admission.INVALID;
        }
        if (batch.slots[image.index()] != null) return Admission.DUPLICATE;
        batch.slots[image.index()] = image;
        seen.put(image.id(), now);
        return Admission.ACCEPTED;
    }
    public synchronized Poll<C> poll(Predicate<String> cached, long now, long timeoutMs) {
        List<Batch<C>> ready = new ArrayList<>(), expired = new ArrayList<>();
        var iterator = pending.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next(); var value = entry.getValue();
            boolean complete = Arrays.stream(value.slots).allMatch(i -> i != null && cached.test(i.id()));
            if (complete || now - value.created >= timeoutMs) {
                (complete ? ready : expired).add(value.snapshot());
                finished.put(entry.getKey(), now);
                iterator.remove();
            }
        }
        prune(now);
        return new Poll<>(List.copyOf(ready), List.copyOf(expired));
    }
    private void prune(long now) {
        seen.entrySet().removeIf(e -> now - e.getValue() >= DEDUP_MS);
        finished.entrySet().removeIf(e -> now - e.getValue() >= DEDUP_MS);
    }
    public synchronized int size() { return pending.size(); }
    public synchronized void clear() { pending.clear(); seen.clear(); finished.clear(); }
}
