# Save architecture for Minecraft 1.21.11

## Ownership and transaction boundary

`PlayerDataStorage.save` keeps vanilla 1.21.11's caller-thread serialization and control flow. `LevelStorageAccess.saveLevelData` receives already serialized world metadata. Narrow redirects intercept only `NbtIo.writeCompressed` and the following `Util.safeReplaceFile` call, preserving other mods' normal save callbacks, including Essential Commands' player-data persistence at `RETURN`. Both paths call `FastAsyncWorldSave.save`, which deep-copies the NBT before accepting the save. Only copied NBT and immutable file paths reach the worker.

Vanilla still creates its temporary file beside the destination on the caller thread. Ownership of that exact file transfers to one queued task, which writes compressed NBT completely, then uses vanilla's `Util.safeReplaceOrMoveFile` to rotate the current file into its backup and publish the temporary file. The original replacement call is suppressed; writing and replacement are never separate tasks.

Preserved `RETURN` callbacks run when the save is enqueued, before asynchronous disk publication necessarily finishes. A callback that needs the freshly written vanilla NBT on disk must coordinate through a completion barrier. Essential Commands saves its own in-memory player data to a separate file and does not need that barrier.

On serialization/write failure, the invalid temporary file is removed and neither good file is rotated. Cleanup failures are retained as suppressed exceptions in the logged failure. If publication fails, vanilla performs its retry/recovery sequence and the completed temporary file is retained when still present; its path is included in the exception. Errors include the destination and cause. Later saves continue to run.

A queue barrier means previous writes have **finished their attempts**. It does not turn a logged disk failure into a successful save. Disk errors must still be acted on by the operator; this preserves vanilla's log-based failure reporting. Flush does not add an operating-system `fsync` guarantee beyond Minecraft's file-writing behavior.

## Ordering and backpressure

One process-wide worker writes player and world NBT in FIFO order. Its queue holds at most 256 waiting tasks plus the running task. When full, submission waits for queue space, preserving order instead of discarding a save or executing a newer write on the caller thread.

The worker remains available throughout the Java process. Temporary world accesses used by other mods and reopening an integrated world cannot permanently shut down a global executor. The daemon worker is safe for normal world closure because storage barriers drain accepted writes before the world lock is released. Forced process termination cannot guarantee completion of pending writes.

Waiting is uninterruptible until the barrier completes and restores the caller's interrupt flag. A worker trying to wait on its own queue fails explicitly instead of deadlocking. Save tasks perform file I/O only; they must not synchronously call code that waits on the server thread or submits additional work to a saturated save queue.

## Storage barriers

| Boundary | Behavior |
| --- | --- |
| `PlayerDataStorage.load(NameAndId)` | Wait before reading player NBT or its backup |
| `MinecraftServer.saveAllChunks(..., flush=true, ...)` | Wait before returning; includes earlier player saves from `saveEverything` and normal shutdown |
| Normal save with `flush=false` | Enqueue and return, except when applying queue backpressure |
| `LevelStorageAccess.getDataTag(boolean)` | Wait before reading current or fallback metadata |
| `modifyLevelDataWithoutDatafix` | Wait before reading and after saving metadata, so consecutive rename operations preserve changes |
| `makeWorldBackup` | Wait before archiving files |
| `restoreLevelDataFromOld` | Wait before restoring the current backup |
| `deleteLevel` | Wait before deleting files |
| `close` | Wait before releasing `DirectoryLock` |

These storage operations retain vanilla's caller/lifecycle ownership: callers must not concurrently submit new saves for a world being closed or deleted. The barriers cover work accepted before the operation.

## Compatibility

The metadata pins Minecraft to exactly 1.21.11. Required Java 21 mixins and explicit injection requirements make incompatible targets fail visibly. The 1.21.11 migration also moves `Util` to `net.minecraft.util.Util`.

The unused upstream weather configuration, empty client initializer, empty access widener, missing icon reference and Cupboard dependency are removed. There is no runtime configuration or client UI added by this fork. Mods that replace the same player/storage methods need separate compatibility testing.
