package dev.penguinsuit.bridge;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** The upstream delete method ignores File.delete() failure; do not release that quota silently. */
public final class VerifiedDiskCache implements OwnedMediaIndex.Cache {
    private final Path directory;
    private final OwnedMediaIndex.Cache delegate;
    public VerifiedDiskCache(Path directory, OwnedMediaIndex.Cache delegate) {
        this.directory = directory.toAbsolutePath().normalize(); this.delegate = delegate;
    }
    public void store(String id, byte[] bytes) throws IOException {
        path(id);
        delegate.store(id, bytes);
    }
    public void delete(String id) throws IOException {
        // Fail before forgetting ownership or removing the in-memory bytes if the disk file is locked.
        Files.deleteIfExists(path(id));
        delegate.delete(id);
    }
    private Path path(String id) throws IOException {
        if (!OwnedMediaIndex.validId(id)) throw new IOException("Invalid bridge image ID");
        return directory.resolve(id);
    }
}
