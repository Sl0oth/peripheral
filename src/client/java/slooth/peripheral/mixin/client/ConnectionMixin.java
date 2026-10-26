package slooth.peripheral.mixin.client;

import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import slooth.peripheral.net.PacketTools;

/** Lets Peripheral's packet tools log, block and hold the client's own traffic. */
@Mixin(Connection.class)
public class ConnectionMixin {
    @Inject(method = "send(Lnet/minecraft/network/protocol/Packet;Lio/netty/channel/ChannelFutureListener;Z)V", at = @At("HEAD"), cancellable = true)
    private void peripheral$send(Packet<?> packet, ChannelFutureListener listener, boolean flush, CallbackInfo ci) {
        Connection self = (Connection) (Object) this;
        if (PacketTools.clientSide(self) && PacketTools.sending(self, packet)) ci.cancel();
    }

    @ModifyVariable(method = "channelRead0(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/protocol/Packet;)V", at = @At("HEAD"), argsOnly = true)
    private Packet<?> peripheral$filterBundle(Packet<?> packet) {
        return PacketTools.clientSide((Connection) (Object) this) ? PacketTools.filterBundle(packet) : packet;
    }

    @Inject(method = "channelRead0(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/protocol/Packet;)V", at = @At("HEAD"), cancellable = true)
    private void peripheral$receive(ChannelHandlerContext context, Packet<?> packet, CallbackInfo ci) {
        if (PacketTools.clientSide((Connection) (Object) this) && PacketTools.receiving(packet)) ci.cancel();
    }
}
