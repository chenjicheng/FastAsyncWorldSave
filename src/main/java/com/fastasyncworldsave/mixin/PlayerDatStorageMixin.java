package com.fastasyncworldsave.mixin;

import com.fastasyncworldsave.FastAsyncWorldSave;
import java.io.File;
import java.util.Optional;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.players.NameAndId;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.storage.PlayerDataStorage;
import net.minecraft.world.level.storage.TagValueOutput;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PlayerDataStorage.class)
public abstract class PlayerDatStorageMixin {
    @Shadow @Final private File playerDir;

    @Inject(method = "save", at = @At("HEAD"), cancellable = true)
    private void saveSnapshot(Player player, CallbackInfo ci) {
        // Match 1.21.11's serialization on the server thread. Only detached data
        // and paths cross to the worker; never read a live Player off-thread.
        try (var problems = new ProblemReporter.ScopedCollector(player.problemPath(), FastAsyncWorldSave.LOGGER)) {
            TagValueOutput output = TagValueOutput.createWithContext(problems, player.registryAccess());
            player.saveWithoutId(output);
            String uuid = player.getStringUUID();
            var directory = playerDir.toPath();
            FastAsyncWorldSave.save(output.buildResult(), directory.resolve(uuid + ".dat"),
                    directory.resolve(uuid + ".dat_old"), uuid + "-");
        } catch (Exception failure) {
            FastAsyncWorldSave.LOGGER.error("Failed to serialize player data for {}", player.getPlainTextName(), failure);
        }
        ci.cancel();
    }

    @Inject(method = "load(Lnet/minecraft/server/players/NameAndId;)Ljava/util/Optional;", at = @At("HEAD"))
    private void waitBeforeLoad(NameAndId player, CallbackInfoReturnable<Optional<CompoundTag>> ci) {
        FastAsyncWorldSave.awaitPendingSaves();
    }
}
