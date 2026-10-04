package com.fastasyncworldsave;

import com.fastasyncworldsave.test.WriteFault;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.PlayerDataStorage;
import net.minecraft.world.level.storage.ValueOutput;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class StorageRegressionTest {
    @TempDir Path directory;
    private LevelStorageSource source;
    private LevelStorageSource.LevelStorageAccess access;
    private PlayerDataStorage players;
    private final UUID playerId = UUID.fromString("86df358d-1788-4ecd-8770-4265ce5f500c");

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @BeforeEach
    void openWorld() throws Exception {
        source = LevelStorageSource.createDefault(directory.resolve("saves"));
        access = source.validateAndCreateAccess("world");
        players = access.createPlayerStorage();
    }

    @AfterEach
    void closeWorld() throws Exception {
        drain();
        WriteFault.DIRECTORY.set(null);
        access.close();
    }

    @Test
    void failedPlayerWritePreservesBothGoodFilesAndLaterSaveRecovers() throws Exception {
        Path data = access.getLevelPath(LevelResource.PLAYER_DATA_DIR);
        Path current = data.resolve(playerId + ".dat");
        Path backup = data.resolve(playerId + ".dat_old");
        NbtIo.writeCompressed(playerTag("current"), current);
        NbtIo.writeCompressed(playerTag("backup"), backup);
        byte[] currentBytes = Files.readAllBytes(current);
        byte[] backupBytes = Files.readAllBytes(backup);

        WriteFault.DIRECTORY.set(data);
        players.save(player("failed"));
        drain();

        assertNull(WriteFault.DIRECTORY.get(), "The write fault must run in the actual storage path");
        assertArrayEquals(currentBytes, Files.readAllBytes(current), "A partial write must never replace player.dat");
        assertArrayEquals(backupBytes, Files.readAllBytes(backup), "A failed write must not rotate the good backup");
        try (var files = Files.list(data)) {
            assertEquals(2, files.count(), "Failed temporary files must be cleaned up");
        }
        players.save(player("recovered"));
        drain();
        assertEquals("recovered", read(current).getStringOr("Marker", ""));
        assertEquals("current", read(backup).getStringOr("Marker", ""));
    }

    @Test
    void immediatePlayerReloadWaitsForQueuedSave() throws Exception {
        players.save(player("old"));
        drain();
        try (var blocked = new BlockedWriter()) {
            players.save(player("new"));
            var reload = background(() -> players.load(new NameAndId(playerId, "Tester")));
            assertWaiting(reload);
            blocked.release();
            assertEquals("new", reload.get(10, TimeUnit.SECONDS).orElseThrow().getStringOr("Marker", ""));
        }
    }

    @Test
    void levelSaveUsesDetachedNestedSnapshotAndKeepsSubmissionOrder() throws Exception {
        CompoundTag nested = new CompoundTag();
        nested.putInt("Value", 1);
        CompoundTag first = levelTag("first");
        first.put("Nested", nested);
        first.putIntArray("Array", new int[] {1, 2});
        try (var blocked = new BlockedWriter()) {
            saveLevel(first);
            nested.putInt("Value", 99);
            first.putIntArray("Array", new int[] {99});
            saveLevel(levelTag("second"));
            blocked.release();
        }
        drain();
        Path world = access.getLevelDirectory().path();
        assertEquals("second", read(world.resolve("level.dat")).getCompoundOrEmpty("Data").getStringOr("LevelName", ""));
        CompoundTag previous = read(world.resolve("level.dat_old"));
        assertEquals(1, previous.getCompoundOrEmpty("Nested").getIntOr("Value", 0));
        assertArrayEquals(new int[] {1, 2}, previous.getIntArray("Array").orElseThrow());
    }

    @Test
    void closingWorldWaitsBeforeReleasingItsLockAndQueueWorksAfterReopen() throws Exception {
        try (var blocked = new BlockedWriter()) {
            saveLevel(levelTag("saved before close"));
            var closing = background(() -> { access.close(); return true; });
            assertWaiting(closing);
            Exception contention = assertThrows(Exception.class, () -> source.validateAndCreateAccess("world"));
            assertTrue(contention instanceof java.io.IOException
                    || contention instanceof java.nio.channels.OverlappingFileLockException,
                    "The filesystem must report lock contention while close waits for queued saves");
            var lockField = access.getClass().getDeclaredField("lock");
            lockField.setAccessible(true);
            var heldLock = (net.minecraft.util.DirectoryLock) lockField.get(access);
            assertTrue(heldLock.isValid(), "The original world lock must remain valid until the save finishes");
            blocked.release();
            assertTrue(closing.get(10, TimeUnit.SECONDS));
        }
        access = source.validateAndCreateAccess("world");
        saveLevel(levelTag("reopened"));
        access.close();
        assertEquals("reopened", read(access.getLevelDirectory().dataFile()).getCompoundOrEmpty("Data").getStringOr("LevelName", ""));
    }

    @Test
    void renamingWorldWaitsForPendingMetadataAndPersistsBeforeReturning() throws Exception {
        saveLevel(levelTag("old"));
        drain();
        try (var blocked = new BlockedWriter()) {
            CompoundTag pending = levelTag("pending");
            pending.getCompoundOrEmpty("Data").putInt("Keep", 42);
            saveLevel(pending);
            var renaming = background(() -> { access.renameLevel("renamed"); return true; });
            assertWaiting(renaming);
            blocked.release();
            renaming.get(10, TimeUnit.SECONDS);
            CompoundTag result = read(access.getLevelDirectory().dataFile()).getCompoundOrEmpty("Data");
            assertEquals("renamed", result.getStringOr("LevelName", ""));
            assertEquals(42, result.getIntOr("Keep", 0));
        }
    }

    @Test
    void worldBackupWaitsForLatestQueuedSave() throws Exception {
        saveLevel(levelTag("old"));
        drain();
        try (var blocked = new BlockedWriter()) {
            saveLevel(levelTag("backup latest"));
            var backup = background(() -> access.makeWorldBackup());
            assertWaiting(backup);
            blocked.release();
            assertTrue(backup.get(10, TimeUnit.SECONDS) > 0);
            Path archives = source.getBackupPath();
            try (var files = Files.list(archives)) {
                Path archive = files.findFirst().orElseThrow();
                try (var zip = new java.util.zip.ZipFile(archive.toFile())) {
                    var entry = zip.getEntry("world/level.dat");
                    assertNotNull(entry);
                    try (var input = zip.getInputStream(entry)) {
                        CompoundTag saved = NbtIo.readCompressed(input, NbtAccounter.unlimitedHeap());
                        assertEquals("backup latest", saved.getCompoundOrEmpty("Data").getStringOr("LevelName", ""));
                    }
                }
            }
        }
    }

    @Test
    void failedLevelWriteKeepsCurrentBackupAndCleansPartialTemporaryFile() throws Exception {
        saveLevel(levelTag("backup"));
        saveLevel(levelTag("current"));
        drain();
        var world = access.getLevelDirectory();
        byte[] current = Files.readAllBytes(world.dataFile());
        byte[] backup = Files.readAllBytes(world.oldDataFile());
        WriteFault.DIRECTORY.set(world.path());
        saveLevel(levelTag("failed"));
        drain();
        assertNull(WriteFault.DIRECTORY.get());
        assertArrayEquals(current, Files.readAllBytes(world.dataFile()));
        assertArrayEquals(backup, Files.readAllBytes(world.oldDataFile()));
        try (var files = Files.list(world.path())) {
            assertFalse(files.anyMatch(path -> path.getFileName().toString().matches("level[0-9]+\\.dat")));
        }
    }

    @Test
    void replacementFailureRetainsCompleteTemporaryNbtForRecovery() throws Exception {
        Path target = directory.resolve("current.dat");
        Path backup = directory.resolve("backup.dat");
        NbtIo.writeCompressed(playerTag("original"), target);
        byte[] original = Files.readAllBytes(target);
        Files.createDirectory(backup);
        Files.writeString(backup.resolve("keep.txt"), "unrelated file");
        var failure = assertThrows(java.io.IOException.class,
                () -> NbtSaveTransaction.write(playerTag("recovery"), target, backup, "recovery"));
        assertTrue(failure.getMessage().contains("completed NBT retained"));
        assertArrayEquals(original, Files.readAllBytes(target));
        assertEquals("unrelated file", Files.readString(backup.resolve("keep.txt")));
        try (var files = Files.list(directory)) {
            Path recovery = files.filter(path -> path.getFileName().toString().startsWith("recovery")).findFirst().orElseThrow();
            assertEquals("recovery", read(recovery).getStringOr("Marker", ""));
        }
    }

    @Test
    void restoringBackupWaitsUntilPendingSaveHasRotatedIt() throws Exception {
        saveLevel(levelTag("oldest"));
        saveLevel(levelTag("current"));
        drain();
        try (var blocked = new BlockedWriter()) {
            saveLevel(levelTag("latest"));
            var restoring = background(access::restoreLevelDataFromOld);
            assertWaiting(restoring);
            blocked.release();
            assertTrue(restoring.get(10, TimeUnit.SECONDS));
        }
        assertEquals("current", read(access.getLevelDirectory().dataFile()).getCompoundOrEmpty("Data").getStringOr("LevelName", ""));
    }

    @Test
    void deletingWorldWaitsForWritesAndDoesNotRecreateDeletedFiles() throws Exception {
        Path world = access.getLevelDirectory().path();
        try (var blocked = new BlockedWriter()) {
            saveLevel(levelTag("queued before delete"));
            var deleting = background(() -> { access.deleteLevel(); return true; });
            assertWaiting(deleting);
            blocked.release();
            assertTrue(deleting.get(10, TimeUnit.SECONDS));
        }
        drain();
        assertFalse(Files.exists(world));
    }

    @Test
    void readingLevelDataWaitsForLatestQueuedSnapshot() throws Exception {
        saveLevel(levelTag("old"));
        drain();
        try (var blocked = new BlockedWriter()) {
            saveLevel(levelTag("latest"));
            var reading = background(access::getDataTag);
            assertWaiting(reading);
            blocked.release();
            assertEquals("latest", reading.get(10, TimeUnit.SECONDS).get("LevelName").asString(""));
        }
    }

    @Test
    void saturatedQueueBlocksProducerWithoutRunningSaveOutOfOrder() throws Exception {
        try (var blocked = new BlockedWriter()) {
            for (int i = 0; i < 256; i++) FastAsyncWorldSave.threadPool.execute(() -> {});
            Path target = directory.resolve("bounded.dat");
            var producer = background(() -> {
                FastAsyncWorldSave.save(playerTag("last"), target, directory.resolve("bounded_old.dat"), "bounded");
                return true;
            });
            assertWaiting(producer);
            assertFalse(Files.exists(target));
            blocked.release();
            assertTrue(producer.get(10, TimeUnit.SECONDS));
            drain();
            assertEquals("last", read(target).getStringOr("Marker", ""));
        }
    }

    @Test
    void interruptedBarrierStillWaitsAndRestoresInterruptFlag() throws Exception {
        try (var blocked = new BlockedWriter()) {
            saveLevel(levelTag("interrupt safe"));
            var waiting = background(() -> {
                Thread.currentThread().interrupt();
                FastAsyncWorldSave.awaitPendingSaves();
                return Thread.currentThread().isInterrupted();
            });
            assertWaiting(waiting);
            blocked.release();
            assertTrue(waiting.get(10, TimeUnit.SECONDS));
            assertEquals("interrupt safe", read(access.getLevelDirectory().dataFile()).getCompoundOrEmpty("Data").getStringOr("LevelName", ""));
        }
    }

    @Test
    void saveWorkerRejectsSelfWaitInsteadOfDeadlocking() throws Exception {
        var waiting = FastAsyncWorldSave.threadPool.submit(FastAsyncWorldSave::awaitPendingSaves);
        var failure = assertThrows(java.util.concurrent.ExecutionException.class, () -> waiting.get(10, TimeUnit.SECONDS));
        assertInstanceOf(IllegalStateException.class, failure.getCause());
        players.save(player("queue remains usable"));
        drain();
        assertEquals("queue remains usable", players.load(new NameAndId(playerId, "Tester")).orElseThrow().getStringOr("Marker", ""));
    }

    private Player player(String marker) {
        Player player = mock(Player.class);
        when(player.problemPath()).thenReturn(() -> "test player");
        when(player.registryAccess()).thenReturn(RegistryAccess.EMPTY);
        when(player.getStringUUID()).thenReturn(playerId.toString());
        when(player.getPlainTextName()).thenReturn("Tester");
        when(player.getName()).thenReturn(net.minecraft.network.chat.Component.literal("Tester"));
        doAnswer(invocation -> {
            ValueOutput output = invocation.getArgument(0);
            output.putString("Marker", marker);
            output.putInt("DataVersion", SharedConstants.getCurrentVersion().dataVersion().version());
            return null;
        }).when(player).saveWithoutId(any(ValueOutput.class));
        return player;
    }

    private CompoundTag playerTag(String marker) {
        CompoundTag tag = new CompoundTag();
        tag.putString("Marker", marker);
        tag.putInt("DataVersion", SharedConstants.getCurrentVersion().dataVersion().version());
        return tag;
    }

    private CompoundTag levelTag(String name) {
        CompoundTag data = new CompoundTag();
        data.putString("LevelName", name);
        CompoundTag tag = new CompoundTag();
        tag.put("Data", data);
        return tag;
    }

    private void saveLevel(CompoundTag tag) throws Exception {
        Method save = access.getClass().getDeclaredMethod("saveLevelData", CompoundTag.class);
        save.setAccessible(true);
        save.invoke(access, tag);
    }

    private CompoundTag read(Path path) throws Exception {
        return NbtIo.readCompressed(path, NbtAccounter.unlimitedHeap());
    }

    private static void drain() throws Exception {
        FastAsyncWorldSave.threadPool.submit(() -> {}).get(10, TimeUnit.SECONDS);
    }

    private static void assertWaiting(CompletableFuture<?> task) {
        assertThrows(TimeoutException.class, () -> task.get(200, TimeUnit.MILLISECONDS), "Storage operation must wait for the blocked save");
    }

    private static <T> CompletableFuture<T> background(CheckedSupplier<T> action) throws Exception {
        CompletableFuture<T> result = new CompletableFuture<>();
        CountDownLatch started = new CountDownLatch(1);
        Thread.ofPlatform().daemon().start(() -> {
            started.countDown();
            try { result.complete(action.get()); }
            catch (Throwable failure) { result.completeExceptionally(failure); }
        });
        assertTrue(started.await(10, TimeUnit.SECONDS));
        return result;
    }

    @FunctionalInterface
    private interface CheckedSupplier<T> { T get() throws Exception; }

    private static final class BlockedWriter implements AutoCloseable {
        private final CountDownLatch release = new CountDownLatch(1);

        BlockedWriter() throws Exception {
            CountDownLatch started = new CountDownLatch(1);
            FastAsyncWorldSave.threadPool.submit(() -> {
                started.countDown();
                try { release.await(15, TimeUnit.SECONDS); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            });
            assertTrue(started.await(10, TimeUnit.SECONDS));
        }

        void release() { release.countDown(); }
        @Override public void close() { release(); }
    }
}
