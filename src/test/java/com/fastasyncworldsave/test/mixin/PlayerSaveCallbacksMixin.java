package com.fastasyncworldsave.test.mixin;

import com.fastasyncworldsave.test.SaveCallbacks;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.storage.PlayerDataStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PlayerDataStorage.class)
public abstract class PlayerSaveCallbacksMixin {
    @Inject(method = "save", at = @At("RETURN"))
    private void persistOtherModData(Player player, CallbackInfo ci) {
        SaveCallbacks.PLAYERS.incrementAndGet();
        SaveCallbacks.playerThread = Thread.currentThread();
    }
}
