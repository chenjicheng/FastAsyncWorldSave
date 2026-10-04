package com.fastasyncworldsave.mixin;

import com.fastasyncworldsave.FastAsyncWorldSave;
import com.mojang.serialization.Dynamic;
import java.nio.file.Path;
import java.util.function.Consumer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.storage.LevelStorageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LevelStorageSource.LevelStorageAccess.class)
public abstract class LevelStorageSourceMixin {
    @Shadow public abstract LevelStorageSource.LevelDirectory getLevelDirectory();

    @Redirect(method = "saveLevelData", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/nbt/NbtIo;writeCompressed(Lnet/minecraft/nbt/CompoundTag;Ljava/nio/file/Path;)V"))
    private void saveSnapshot(CompoundTag tag, Path temporary) {
        var directory = getLevelDirectory();
        FastAsyncWorldSave.save(tag, temporary, directory.dataFile(), directory.oldDataFile());
    }

    @Redirect(method = "saveLevelData", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/util/Util;safeReplaceFile(Ljava/nio/file/Path;Ljava/nio/file/Path;Ljava/nio/file/Path;)V"))
    private void leaveReplacementToWorker(Path target, Path temporary, Path backup) {
        // The queued transaction publishes this same temporary file only after a complete write.
    }

    // These methods read, replace, archive or delete files that a queued save owns.
    @Inject(method = {"close", "deleteLevel"}, at = @At("HEAD"))
    private void waitBeforeStorageOperation(CallbackInfo ci) {
        FastAsyncWorldSave.awaitPendingSaves();
    }

    @Inject(method = "getDataTag(Z)Lcom/mojang/serialization/Dynamic;", at = @At("HEAD"))
    private void waitBeforeRead(boolean fallback, CallbackInfoReturnable<Dynamic<?>> ci) {
        FastAsyncWorldSave.awaitPendingSaves();
    }

    @Inject(method = "makeWorldBackup", at = @At("HEAD"))
    private void waitBeforeBackup(CallbackInfoReturnable<Long> ci) {
        FastAsyncWorldSave.awaitPendingSaves();
    }

    @Inject(method = "restoreLevelDataFromOld", at = @At("HEAD"))
    private void waitBeforeRestore(CallbackInfoReturnable<Boolean> ci) {
        FastAsyncWorldSave.awaitPendingSaves();
    }

    @Inject(method = "modifyLevelDataWithoutDatafix", at = @At("HEAD"))
    private void waitBeforeMetadataChange(Consumer<CompoundTag> change, CallbackInfo ci) {
        FastAsyncWorldSave.awaitPendingSaves();
    }

    @Inject(method = "modifyLevelDataWithoutDatafix", at = @At("RETURN"))
    private void finishMetadataChange(Consumer<CompoundTag> change, CallbackInfo ci) {
        FastAsyncWorldSave.awaitPendingSaves();
    }
}
