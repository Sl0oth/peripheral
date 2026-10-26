package slooth.peripheral.testing;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Turns on Peripheral's packet tools for players on this server. Peripheral checks whether the
 * server listens on the {@code peripheral:testing} channel; this mod listens on it, and Fabric tells
 * every joining client so. Nothing is ever sent on the channel.
 */
public final class PeripheralTesting implements ModInitializer {
    /** The opt-in channel. It carries nothing. */
    public record OptIn() implements CustomPacketPayload {
        public static final Type<OptIn> TYPE = new Type<>(Identifier.fromNamespaceAndPath("peripheral", "testing"));
        public static final StreamCodec<RegistryFriendlyByteBuf, OptIn> CODEC = StreamCodec.unit(new OptIn());

        @Override
        public Type<OptIn> type() { return TYPE; }
    }

    @Override
    public void onInitialize() {
        PayloadTypeRegistry.serverboundPlay().register(OptIn.TYPE, OptIn.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(OptIn.TYPE, (payload, context) -> {});
    }
}
