package com.fastasyncworldsave.test.mixin;

import com.fastasyncworldsave.test.WriteFault;
import java.io.IOException;
import java.nio.file.Path;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(NbtIo.class)
public abstract class NbtWriteFaultMixin {
    @Inject(method = "writeCompressed(Lnet/minecraft/nbt/CompoundTag;Ljava/nio/file/Path;)V", at = @At("HEAD"))
    private static void failOneWrite(CompoundTag tag, Path path, CallbackInfo ci) throws IOException {
        WriteFault.beforeWrite(path);
    }
}
