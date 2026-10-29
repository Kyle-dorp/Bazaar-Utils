package com.github.mkram17.bazaarutils.mixin;

import com.github.mkram17.bazaarutils.features.PacketMonitor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Marks when the game is handling your own mouse button, so /flippackets can tell your container clicks from a mod's. Read-only. */
@Mixin(net.minecraft.client.MouseHandler.class)
public abstract class MixinMouseInput {
    @Inject(method = "onButton", at = @At("HEAD"), require = 0)
    private void bazaarutils$start(long window, net.minecraft.client.input.MouseButtonInfo info, int action, CallbackInfo ci) {
        PacketMonitor.userInputStart();
    }

    @Inject(method = "onButton", at = @At("RETURN"), require = 0)
    private void bazaarutils$end(long window, net.minecraft.client.input.MouseButtonInfo info, int action, CallbackInfo ci) {
        PacketMonitor.userInputEnd();
    }
}
