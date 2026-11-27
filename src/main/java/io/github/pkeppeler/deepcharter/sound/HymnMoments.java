package io.github.pkeppeler.deepcharter.sound;

import io.github.pkeppeler.deepcharter.charter.CharterEvents;
import io.github.pkeppeler.deepcharter.handbook.HandbookEvents;
import io.github.pkeppeler.deepcharter.layer.BreachEvents;
import io.github.pkeppeler.deepcharter.layer.LayerChain;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
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
 *   <li>a player climbs out of the layers into the colony: the colony hymn again;
 *   <li>the last directive of a handbook chapter is done: a short phrase of the colony hymn ({@link DeepSound#HYMN_CHAPTER}) to each
 *       online member of the charter, or to the player alone on none.
 * </ul>
 *
 * A climb from one layer to another is silent. Pods and other riders play nothing. A hymn is never stacked on itself: a player who
 * crosses again while their hymn still plays hears no second one, and a crossing to a different version stops the old one and starts
 * the new one.
 *
 * <p>The chapter phrase waits its turn and gives way. A completed chapter queues it for the player, to play at the end of the next tick
 * at the earliest, and later while a machine's online chord rings ({@link #holdPhrasesFor}): the last directive of a chapter is often
 * a repair, whose power-on and chord would clash with it. When it falls due, a player whose hymn (or phrase) is still playing is not
 * given it at all. So a chapter finished by the crossing or the founding that starts a hymn in the same tick plays the hymn alone,
 * whichever listener ran first.
 */
public final class HymnMoments {
    /** The version for each depth, the colony first: the index is the layer, counted from the surface at 0. */
    private static final List<DeepSound> HYMNS =
            List.of(DeepSound.HYMN_COLONY, DeepSound.HYMN_LAYER_1, DeepSound.HYMN_LAYER_2);

    /** The hymn each player is hearing: which version and the server tick it ends. Server thread only. */
    private static final Map<UUID, Playing> PLAYING = new HashMap<>();

    /** The chapter phrases waiting to play: the player and the first server tick it may. Server thread only. */
    private static final Map<UUID, Integer> PENDING = new HashMap<>();

    /** No queued phrase plays before this server tick, while a machine's chord rings. Server thread only. */
    private static int phrasesHeldUntil;

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
        HandbookEvents.CHAPTER_COMPLETED.register(
                (server, player, chapter) -> PENDING.putIfAbsent(player.getUUID(), server.getTickCount() + 1));
        ServerTickEvents.END_SERVER_TICK.register(HymnMoments::playDuePhrases);
        ServerPlayConnectionEvents.DISCONNECT.register(
                (handler, server) -> forget(handler.getPlayer().getUUID()));
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> stopped());
    }

    /**
     * Forgets every hymn and every queued phrase: the state is static, so an integrated server restarted in one client session would
     * inherit the last one's, and its tick count starts again from 0.
     */
    static void stopped() {
        PLAYING.clear();
        PENDING.clear();
        phrasesHeldUntil = 0;
    }

    /** Forgets the hymn {@code player} was hearing and their queued phrase: they left, so it stopped, and their next moment plays in full. */
    static void forget(UUID player) {
        PLAYING.remove(player);
        PENDING.remove(player);
    }

    /** Keeps every queued chapter phrase from playing for {@code ticks} from now: a machine's online chord is ringing. */
    static void holdPhrasesFor(MinecraftServer server, int ticks) {
        phrasesHeldUntil = Math.max(phrasesHeldUntil, server.getTickCount() + ticks);
    }

    private static void playDuePhrases(MinecraftServer server) {
        int now = server.getTickCount();
        if (PENDING.isEmpty() || now < phrasesHeldUntil) {
            return;
        }
        Iterator<Map.Entry<UUID, Integer>> pending = PENDING.entrySet().iterator();
        while (pending.hasNext()) {
            Map.Entry<UUID, Integer> phrase = pending.next();
            if (phrase.getValue() > now) {
                continue;
            }
            pending.remove();
            ServerPlayer player = server.getPlayerList().getPlayer(phrase.getKey());
            if (player != null) {
                playPhrase(player);
            }
        }
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

    private static void playPhrase(ServerPlayer player) {
        Playing last = PLAYING.get(player.getUUID());
        if (last == null || player.level().getServer().getTickCount() >= last.endsAtTick()) {
            play(player, DeepSound.HYMN_CHAPTER);
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
        PLAYING.put(player.getUUID(), new Playing(hymn, now + ticksOf(hymn)));
    }

    private static int ticksOf(DeepSound hymn) {
        return hymn == DeepSound.HYMN_CHAPTER
                ? SoundTuning.DEFAULT.chapterPhraseTicks()
                : SoundTuning.DEFAULT.hymnTicks().get(HYMNS.indexOf(hymn));
    }
}
