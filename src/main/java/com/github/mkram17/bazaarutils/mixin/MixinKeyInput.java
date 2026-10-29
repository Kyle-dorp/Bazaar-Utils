package com.github.mkram17.bazaarutils.mixin;

import com.github.mkram17.bazaarutils.features.PacketMonitor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Marks when the game is handling your own key press, so /flippackets can tell your container clicks from a mod's. Read-only. */
@Mixin(net.minecraft.client.KeyboardHandler.class)
public abstract class MixinKeyInput {
    @Inject(method = "keyPress", at = @At("HEAD"), require = 0)
    private void bazaarutils$start(long window, int action, net.minecraft.client.input.KeyEvent event, CallbackInfo ci) {
        PacketMonitor.userInputStart();
    }

    @Inject(method = "keyPress", at = @At("RETURN"), require = 0)
    private void bazaarutils$end(long window, int action, net.minecraft.client.input.KeyEvent event, CallbackInfo ci) {
        PacketMonitor.userInputEnd();
    }
}
