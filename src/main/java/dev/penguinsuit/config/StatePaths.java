package dev.penguinsuit.config;

import java.io.IOException;
import java.nio.file.*;

/** Move the complete private state directory once; never combine two identity databases. */
public final class StatePaths {
    public static Path prepare(Path configDirectory)throws IOException{
        Path target=configDirectory.resolve("penguin-suit");
        Path legacy=configDirectory.resolve("penguin-media-bridge");
        if(Files.notExists(target)&&Files.exists(legacy)){
            if(!Files.isDirectory(legacy)||Files.isSymbolicLink(legacy))throw new IOException("Legacy state path must be a real directory");
            Files.move(legacy,target);
        }
        Files.createDirectories(target);
        return target;
    }
    private StatePaths(){}
}
