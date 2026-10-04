package com.fastasyncworldsave.mixin;

import com.fastasyncworldsave.FastAsyncWorldSave;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(MinecraftServer.class)
public abstract class MinecraftServerMixin {
    @Inject(method = "saveAllChunks", at = @At("RETURN"))
    private void finishFlush(boolean suppressLogs, boolean flush, boolean force,
            CallbackInfoReturnable<Boolean> ci) {
        if (flush) FastAsyncWorldSave.awaitPendingSaves();
    }
}
