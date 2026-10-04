package com.fastasyncworldsave.test.mixin;

import com.fastasyncworldsave.test.SaveCallbacks;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.storage.LevelStorageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelStorageSource.LevelStorageAccess.class)
public abstract class LevelSaveCallbacksMixin {
    @Inject(method = "saveLevelData", at = @At("RETURN"))
    private void persistOtherModData(CompoundTag tag, CallbackInfo ci) {
        SaveCallbacks.LEVELS.incrementAndGet();
        SaveCallbacks.levelThread = Thread.currentThread();
    }
}
