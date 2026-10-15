package io.github.pkeppeler.deepcharter.sound;

import io.github.pkeppeler.deepcharter.charter.CharterEvents;
import io.github.pkeppeler.deepcharter.layer.BreachEvents;
import io.github.pkeppeler.deepcharter.layer.LayerChain;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
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
 * A climb from one layer to another is silent. Pods and other riders play nothing. A hymn is never stacked on itself: a player who
 * crosses again while their hymn still plays hears no second one, and a crossing to a different version stops the old one and starts
 * the new one.
 */
public final class HymnMoments {
    /** The version for each depth, the colony first: the index is the layer, counted from the surface at 0. */
    private static final List<DeepSound> HYMNS =
            List.of(DeepSound.HYMN_COLONY, DeepSound.HYMN_LAYER_1, DeepSound.HYMN_LAYER_2);

    /** The hymn each player is hearing: which version and the server tick it ends. Server thread only. */
    private static final Map<UUID, Playing> PLAYING = new HashMap<>();

    private HymnMoments() {}

    private record Playing(DeepSound hymn, int endsAtTick) {}

    /**
     * The hymn for a depth: layer 0 is the surface and the colony. A layer deeper than the last version plays the last, the most worn,
     * so a new layer is never silent at a breach.
     */
    public static DeepSound hymnFor(int layer) {
        if (layer < LayerChain.SURFACE) {
            throw new IllegalArgumentException("A layer is counted from the surface at 0, got " + layer);
        }
        return HYMNS.get(Math.min(layer, HYMNS.size() - 1));
    }

    static void init() {
        CharterEvents.FOUNDED.register(
                (server, charter) -> charter.director().ifPresent(director -> arrived(server, director)));
        CharterEvents.JOINED.register((server, charter, player) -> arrived(server, player));
        BreachEvents.CROSSED.register(HymnMoments::onCrossed);
        ServerPlayConnectionEvents.DISCONNECT.register(
                (handler, server) -> PLAYING.remove(handler.getPlayer().getUUID()));
    }

    private static void arrived(MinecraftServer server, UUID player) {
        ServerPlayer online = server.getPlayerList().getPlayer(player);
        if (online != null) {
            play(online, hymnFor(LayerChain.SURFACE));
        }
    }

    private static void onCrossed(Entity entity, ServerLevel from, ServerLevel to, int fromLayer, int toLayer) {
        if (!(entity instanceof ServerPlayer player)) {
            return;
        }
        if (toLayer > fromLayer || toLayer == LayerChain.SURFACE) {
            play(player, hymnFor(toLayer));
        }
    }

    private static void play(ServerPlayer player, DeepSound hymn) {
        int now = player.level().getServer().getTickCount();
        Playing last = PLAYING.get(player.getUUID());
        if (last != null && now < last.endsAtTick()) {
            if (last.hymn() == hymn) {
                return;
            }
            SoundPackets.stopFor(player, last.hymn());
        }
        SoundPackets.playTo(player, hymn);
        PLAYING.put(
                player.getUUID(),
                new Playing(hymn, now + SoundTuning.DEFAULT.hymnTicks().get(HYMNS.indexOf(hymn))));
    }
}
