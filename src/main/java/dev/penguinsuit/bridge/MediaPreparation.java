package dev.penguinsuit.bridge;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Recover per-image failures before delivery, without swallowing fatal VM errors. */
public final class MediaPreparation {
    @FunctionalInterface public interface PrepareImage {
        ChatBackend.Prepared prepare(URI source) throws Exception;
    }
    public record Result(List<ChatBackend.Prepared> images, int failures, boolean cancelled, List<Integer> sourceIndices) {}

    public static Result prepare(List<URI> sources, int omitted, BooleanSupplier allowed,
                                 PrepareImage prepareImage, Consumer<List<ChatBackend.Prepared>> cleanup,
                                 Consumer<Throwable> onFailure) {
        List<ChatBackend.Prepared> ready = new ArrayList<>();
        List<Integer> indices = new ArrayList<>();
        int failures = omitted;
        for (int i=0;i<sources.size();i++) {
            URI source=sources.get(i);
            if (!allowed.getAsBoolean() || Thread.currentThread().isInterrupted()) {
                cleanup.accept(List.copyOf(ready));
                return new Result(List.of(), failures, true, List.of());
            }
            try {
                ready.add(java.util.Objects.requireNonNull(prepareImage.prepare(source)));
                indices.add(i);
            } catch (Exception | LinkageError failure) {
                // UnsatisfiedLinkError and subsequent NoClassDefFoundError are not
                // Exceptions. Previously they killed this accepted message's worker.
                failures++;
                onFailure.accept(failure);
            }
        }
        if (!allowed.getAsBoolean() || Thread.currentThread().isInterrupted()) {
            cleanup.accept(List.copyOf(ready));
            return new Result(List.of(), failures, true, List.of());
        }
        return new Result(List.copyOf(ready), failures, false, List.copyOf(indices));
    }

    private MediaPreparation() {}
}
