package com.github.mkram17.bazaarutils.mixin;

import com.github.mkram17.bazaarutils.features.PacketMonitor;
import io.netty.channel.ChannelFutureListener;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Counts every outgoing packet for /flippackets. Connection.send(packet) and send(packet, listener) both end in this
 * three-argument send, so each packet is counted once. Read-only: never cancels or changes anything.
 */
@Mixin(Connection.class)
public abstract class MixinPacketCounter {
    @Inject(method = "send(Lnet/minecraft/network/protocol/Packet;Lio/netty/channel/ChannelFutureListener;Z)V", at = @At("HEAD"), require = 0)
    private void bazaarutils$countPacket(Packet<?> packet, ChannelFutureListener listener, boolean flush, CallbackInfo ci) {
        PacketMonitor.onSend(packet);
    }
}
