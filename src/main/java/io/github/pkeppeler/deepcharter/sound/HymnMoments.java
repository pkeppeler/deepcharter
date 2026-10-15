package io.github.pkeppeler.deepcharter.sound;

import io.github.pkeppeler.deepcharter.charter.CharterEvents;
import io.github.pkeppeler.deepcharter.layer.BreachEvents;
import io.github.pkeppeler.deepcharter.layer.LayerChain;
import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

/**
 * The Lamp Hymn: a short original motif on one reed, played to a single player at the moments that mean something. It comes back slower
 * and more worn for each layer, so the version played is the one for the depth reached ({@link #hymnFor}). The moments:
 *
 * <ul>
 *   <li>a charter is founded: the founder has arrived at the colony;
 *   <li>a player joins a charter: the same arrival;
 *   <li>a player goes down a breach: the hymn of the layer reached;
 *   <li>a player climbs out of the layers into the colony: the colony hymn again.
 * </ul>
 *
 * A climb from one layer to another is silent. Pods and other riders play nothing.
 */
public final class HymnMoments {
    /** The version for each depth, the colony first: the index is the layer, counted from the surface at 0. */
    private static final List<DeepSound> HYMNS =
            List.of(DeepSound.HYMN_COLONY, DeepSound.HYMN_LAYER_1, DeepSound.HYMN_LAYER_2);

    private HymnMoments() {}

    /** The hymn for a depth: layer 0 is the surface and the colony. A layer with no version is a bug, found at start-up by {@link #init}. */
    public static DeepSound hymnFor(int layer) {
        if (layer < LayerChain.SURFACE || layer >= HYMNS.size()) {
            throw new IllegalArgumentException("The Lamp Hymn has no version for layer " + layer);
        }
        return HYMNS.get(layer);
    }

    static void init() {
        ServerLifecycleEvents.SERVER_STARTED.register(
                server -> requireVersionsFor(LayerChain.count(server.registryAccess())));
        CharterEvents.FOUNDED.register(
                (server, charter) -> charter.director().ifPresent(director -> arrived(server, director)));
        CharterEvents.JOINED.register((server, charter, player) -> arrived(server, player));
        BreachEvents.CROSSED.register(HymnMoments::onCrossed);
    }

    /** A chain with a layer that has no hymn version is a bug, found at start-up and not in the middle of a breach. */
    static void requireVersionsFor(int layers) {
        if (layers >= HYMNS.size()) {
            throw new IllegalStateException(
                    "The chain has " + layers + " layers but the Lamp Hymn has versions for " + (HYMNS.size() - 1));
        }
    }

    private static void arrived(MinecraftServer server, UUID player) {
        ServerPlayer online = server.getPlayerList().getPlayer(player);
        if (online != null) {
            SoundPackets.playTo(online, hymnFor(LayerChain.SURFACE));
        }
    }

    private static void onCrossed(Entity entity, ServerLevel from, ServerLevel to, int fromLayer, int toLayer) {
        if (!(entity instanceof ServerPlayer player)) {
            return;
        }
        if (toLayer > fromLayer || toLayer == LayerChain.SURFACE) {
            SoundPackets.playTo(player, hymnFor(toLayer));
        }
    }
}
