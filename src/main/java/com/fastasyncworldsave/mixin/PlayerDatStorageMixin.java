package com.fastasyncworldsave.mixin;

import com.fastasyncworldsave.FastAsyncWorldSave;
import java.nio.file.Path;
import java.util.Optional;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.storage.PlayerDataStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PlayerDataStorage.class)
public abstract class PlayerDatStorageMixin {
    @Redirect(method = "save", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/nbt/NbtIo;writeCompressed(Lnet/minecraft/nbt/CompoundTag;Ljava/nio/file/Path;)V"))
    private void saveSnapshot(CompoundTag tag, Path temporary, Player player) {
        // Preserve vanilla serialization and other mods' save callbacks on the caller.
        String uuid = player.getStringUUID();
        Path directory = temporary.getParent();
        FastAsyncWorldSave.save(tag, temporary, directory.resolve(uuid + ".dat"),
                directory.resolve(uuid + ".dat_old"));
    }

    @Redirect(method = "save", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/util/Util;safeReplaceFile(Ljava/nio/file/Path;Ljava/nio/file/Path;Ljava/nio/file/Path;)V"))
    private void leaveReplacementToWorker(Path target, Path temporary, Path backup) {
        // Writing and replacement belong to one task; failed NBT must never be published.
    }

    @Inject(method = "load(Lnet/minecraft/server/players/NameAndId;)Ljava/util/Optional;", at = @At("HEAD"))
    private void waitBeforeLoad(NameAndId player, CallbackInfoReturnable<Optional<CompoundTag>> ci) {
        FastAsyncWorldSave.awaitPendingSaves();
    }
}
