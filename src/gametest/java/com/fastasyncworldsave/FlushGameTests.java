package com.fastasyncworldsave;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;

/** Exercises transformed Minecraft save methods in the headless GameTest server. */
public final class FlushGameTests {
    private static final long INITIAL_TIME = 10_000L;
    private static final long AUTOSAVE_TIME = 20_000L;
    private static final long FLUSH_TIME = 30_000L;
    private static final long CHUNK_FLUSH_TIME = 40_000L;

    @GameTest(maxTicks = 200)
    // The 1.21.11 helper provides a real player and loopback connection for saveAll().
    @SuppressWarnings("removal")
    public void savesFlushPlayerAndWorldData(GameTestHelper helper) throws Exception {
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        Path playerFile = server.getWorldPath(LevelResource.PLAYER_DATA_DIR)
                .resolve(player.getStringUUID() + ".dat");
        Path levelFile = server.getWorldPath(LevelResource.LEVEL_DATA_FILE);

        player.experienceLevel = 3;
        helper.getLevel().setDayTime(INITIAL_TIME);
        server.saveEverything(true, true, true);
        drain();
        assertSaved(helper, playerFile, levelFile, 3, INITIAL_TIME);

        try (BlockedWriter blocked = new BlockedWriter()) {
            player.experienceLevel = 42;
            helper.getLevel().setDayTime(AUTOSAVE_TIME);
            server.saveEverything(true, false, true);
            helper.assertFalse(blocked.isReleased(), "An autosave must return while disk writes remain queued");
            assertSaved(helper, playerFile, levelFile, 3, INITIAL_TIME);
        }
        drain();
        assertSaved(helper, playerFile, levelFile, 42, AUTOSAVE_TIME);

        try (BlockedWriter blocked = new BlockedWriter()) {
            player.experienceLevel = 43;
            helper.getLevel().setDayTime(FLUSH_TIME);
            blocked.releaseAfterObservationWindow();
            server.saveEverything(true, true, true);
            helper.assertTrue(blocked.isReleased(), "A flush must wait for the blocked save writer");
            assertSaved(helper, playerFile, levelFile, 43, FLUSH_TIME);
        }

        try (BlockedWriter blocked = new BlockedWriter()) {
            player.experienceLevel = 44;
            helper.getLevel().setDayTime(CHUNK_FLUSH_TIME);
            server.getPlayerList().saveAll();
            blocked.releaseAfterObservationWindow();
            server.saveAllChunks(true, true, true);
            helper.assertTrue(blocked.isReleased(), "A direct chunk flush must include previously queued player saves");
            assertSaved(helper, playerFile, levelFile, 44, CHUNK_FLUSH_TIME);
        }
        helper.succeed();
    }

    private static void assertSaved(GameTestHelper helper, Path playerFile, Path levelFile,
            int experience, long dayTime) throws Exception {
        CompoundTag player = NbtIo.readCompressed(playerFile, NbtAccounter.unlimitedHeap());
        CompoundTag level = NbtIo.readCompressed(levelFile, NbtAccounter.unlimitedHeap());
        helper.assertValueEqual(experience, player.getIntOr("XpLevel", -1), "Player experience persisted before save returned");
        helper.assertValueEqual(dayTime, level.getCompoundOrEmpty("Data").getLongOr("DayTime", -1L),
                "World metadata persisted before save returned");
    }

    private static void drain() throws Exception {
        FastAsyncWorldSave.threadPool.submit(() -> {}).get(15, TimeUnit.SECONDS);
    }

    private static final class BlockedWriter implements AutoCloseable {
        private final CountDownLatch release = new CountDownLatch(1);

        BlockedWriter() throws Exception {
            CountDownLatch started = new CountDownLatch(1);
            FastAsyncWorldSave.threadPool.submit(() -> {
                started.countDown();
                try {
                    if (!release.await(30, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("GameTest did not release its save writer");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("GameTest save writer was interrupted", interrupted);
                }
            });
            if (!started.await(15, TimeUnit.SECONDS)) {
                release.countDown();
                throw new IllegalStateException("GameTest save writer did not start");
            }
        }

        void releaseAfterObservationWindow() {
            // Minecraft save methods stay on the server thread. Only the queue gate is
            // released elsewhere, so this test also covers the actual synchronous flush.
            Thread.ofPlatform().daemon().name("fastasyncworldsave-gametest-releaser").start(() -> {
                try {
                    release.await(2, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                } finally {
                    release.countDown();
                }
            });
        }

        boolean isReleased() {
            return release.getCount() == 0;
        }

        @Override
        public void close() {
            release.countDown();
        }
    }
}
