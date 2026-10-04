package com.fastasyncworldsave;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.util.Util;

/** A failed serialization must never publish a partial file or rotate good backups. */
final class NbtSaveTransaction {
    private NbtSaveTransaction() {}

    static void write(CompoundTag snapshot, Path target, Path backup, String temporaryPrefix) throws IOException {
        Path temporary = Files.createTempFile(target.getParent(), temporaryPrefix, ".dat");
        try {
            NbtIo.writeCompressed(snapshot, temporary);
        } catch (IOException | RuntimeException failure) {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }

        // Minecraft's void safeReplaceFile helper hides a failed replacement.
        // Retain a fully written temporary file for recovery if publication fails.
        if (!Util.safeReplaceOrMoveFile(target, temporary, backup, false)) {
            throw new IOException("Could not replace " + target + "; completed NBT retained at " + temporary);
        }
    }
}
