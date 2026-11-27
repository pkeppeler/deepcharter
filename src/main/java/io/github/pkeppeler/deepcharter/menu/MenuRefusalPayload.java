package io.github.pkeppeler.deepcharter.menu;

import io.github.pkeppeler.deepcharter.DeepCharter;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Clientbound: the server's {@link SlottedMenu} of container {@code containerId} refused a click, and why. The client's
 * menu of that id shows it, so a refusal the client did not predict still shows on the open screen.
 */
public record MenuRefusalPayload(int containerId, Component reason) implements CustomPacketPayload {
    public static final Type<MenuRefusalPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "menu_refusal"));
    public static final StreamCodec<RegistryFriendlyByteBuf, MenuRefusalPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT,
            MenuRefusalPayload::containerId,
            ComponentSerialization.TRUSTED_CONTEXT_FREE_STREAM_CODEC,
            MenuRefusalPayload::reason,
            MenuRefusalPayload::new);

    /** Registers the payload type. Called once, by the registry of the first feature with a slotted menu. */
    public static void register() {
        PayloadTypeRegistry.clientboundPlay().register(TYPE, CODEC);
    }

    @Override
    public Type<MenuRefusalPayload> type() {
        return TYPE;
    }
}
