package com.fastasyncworldsave.test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

/** A deterministic disk-write failure, injected only by the test mod. */
public final class WriteFault {
    public static final AtomicReference<Path> DIRECTORY = new AtomicReference<>();

    private WriteFault() {}

    public static void beforeWrite(Path path) throws IOException {
        Path directory = DIRECTORY.get();
        if (directory != null && directory.equals(path.getParent())
                && DIRECTORY.compareAndSet(directory, null)) {
            Files.writeString(path, "partial compressed NBT");
            throw new IOException("Injected partial NBT write failure");
        }
    }
}
