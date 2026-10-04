package com.fastasyncworldsave;

import java.nio.file.Path;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import net.fabricmc.api.ModInitializer;
import net.minecraft.nbt.CompoundTag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class FastAsyncWorldSave implements ModInitializer {
    public static final String MOD_ID = "fastasyncworldsave";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    private static final int MAX_PENDING_SAVES = 256;
    private static volatile Thread saveThread;

    // A process-wide FIFO also covers temporary world accesses used by other mods.
    // Storage-close barriers drain it; it stays available for the next integrated world.
    static final ExecutorService threadPool = new ThreadPoolExecutor(
            1, 1, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(MAX_PENDING_SAVES, true),
            task -> {
                Thread thread = new Thread(task, MOD_ID);
                thread.setDaemon(true);
                saveThread = thread;
                return thread;
            },
            (task, executor) -> {
                // Apply backpressure on a slow disk without dropping data or running
                // a newer save on the caller ahead of older queued writes.
                boolean interrupted = false;
                while (true) {
                    try {
                        executor.getQueue().put(task);
                        break;
                    } catch (InterruptedException interruption) {
                        interrupted = true;
                    }
                }
                if (interrupted) Thread.currentThread().interrupt();
            });

    @Override
    public void onInitialize() {
        LOGGER.info("Fast Async World Save maintenance build for Minecraft 1.21.11");
    }

    /** Capture detached NBT on the caller; perform all file I/O as one ordered task. */
    public static void save(CompoundTag tag, Path target, Path backup, String temporaryPrefix) {
        CompoundTag snapshot = tag.copy();
        threadPool.execute(() -> {
            try {
                NbtSaveTransaction.write(snapshot, target, backup, temporaryPrefix);
            } catch (Exception failure) {
                LOGGER.error("Failed to save NBT data to {}", target, failure);
            }
        });
    }

    /** Wait for all previously accepted saves, preserving the caller's interrupt flag. */
    public static void awaitPendingSaves() {
        if (Thread.currentThread() == saveThread) {
            throw new IllegalStateException("The save worker cannot wait for its own queue");
        }
        var barrier = threadPool.submit(() -> {});
        boolean interrupted = false;
        try {
            while (true) {
                try {
                    barrier.get();
                    return;
                } catch (InterruptedException interruption) {
                    interrupted = true;
                } catch (ExecutionException failure) {
                    throw new IllegalStateException("Save queue barrier failed", failure.getCause());
                }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }
}
