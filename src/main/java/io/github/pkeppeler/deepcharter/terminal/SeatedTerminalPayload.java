package io.github.pkeppeler.deepcharter.terminal;

import io.github.pkeppeler.deepcharter.DeepCharter;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Serverbound: a seated player asks to open the terminal in reach that they face ({@link SeatedTerminals#open}). It
 * carries nothing: the server picks the terminal from where the player sits and looks.
 */
public record SeatedTerminalPayload() implements CustomPacketPayload {
    public static final SeatedTerminalPayload INSTANCE = new SeatedTerminalPayload();
    public static final Type<SeatedTerminalPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "seated_terminal"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SeatedTerminalPayload> CODEC = StreamCodec.unit(INSTANCE);

    static void register() {
        PayloadTypeRegistry.serverboundPlay().register(TYPE, CODEC);
        ServerPlayNetworking.registerGlobalReceiver(TYPE, (payload, context) -> SeatedTerminals.open(context.player()));
    }

    @Override
    public Type<SeatedTerminalPayload> type() {
        return TYPE;
    }
}
