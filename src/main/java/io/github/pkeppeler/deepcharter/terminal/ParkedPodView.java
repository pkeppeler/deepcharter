package io.github.pkeppeler.deepcharter.terminal;

import io.github.pkeppeler.deepcharter.charter.Charter;
import io.netty.buffer.ByteBuf;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

/**
 * The pod a pod terminal serves, chosen by the server ({@link Terminals#parkedPods(net.minecraft.server.level.ServerLevel, BlockPos, Optional)}),
 * so a screen shows the pod that an action will use. The client cannot choose alike: it does not know the owner of a pod.
 *
 * @param pod the id of the pod the terminal serves the player, or empty when none is parked that the player may use
 */
public record ParkedPodView(Optional<UUID> pod) implements TerminalFeature {
    public static final StreamCodec<ByteBuf, ParkedPodView> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.optional(UUIDUtil.STREAM_CODEC), ParkedPodView::pod, ParkedPodView::new);

    /** Gives {@code type}'s view this feature. */
    public static void register(TerminalType type) {
        TerminalFeatures.register(type, STREAM_CODEC, ParkedPodView::supply);
    }

    private static ParkedPodView supply(
            MinecraftServer server, ServerPlayer player, Optional<Charter> charter, BlockPos pos) {
        return new ParkedPodView(Terminals.parkedPods(player.level(), pos, charter).stream()
                .findFirst()
                .map(Entity::getUUID));
    }
}
