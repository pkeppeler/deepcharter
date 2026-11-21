package io.github.pkeppeler.deepcharter.layer;

import io.github.pkeppeler.deepcharter.DeepCharter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * Light is computed off the server thread, and vanilla drops the light update of a chunk that is not yet ticking (issue 427), so a client
 * sent a chunk while light is pending keeps the old light; call this after the last block placed in a chunk a player may be sent.
 * Vanilla offers a chunk to its trackers only once, so this also offers it again, when the hold ends, to those who came into view
 * during the hold (issue 524). A player who already had a chunk of the hold when it began is sent the chunk's settled light when the hold
 * ends (#824): the change reaches that chunk from a neighbour's draw, and vanilla marks a light change for sending only once the chunk
 * ticks.
 */
public final class LightSync {
    /** Server ticks a hold may last before it is reported as stuck (30 s). */
    private static final int TIMEOUT_TICKS = 600;

    private record Key(ServerLevel level, ChunkPos chunk) {}

    /** One chunk hold. The player sets are server thread only; test threads read {@code startTick}. */
    private static final class Hold {
        final Set<UUID> trackedThroughout;
        /** The players who had been sent the chunk when the hold began and still track it. */
        final Set<UUID> sentAtStart;

        volatile int startTick;
        /** Server thread only. */
        boolean reported;

        Hold(int startTick, Set<UUID> trackedThroughout, Set<UUID> sentAtStart) {
            this.startTick = startTick;
            this.trackedThroughout = trackedThroughout;
            this.sentAtStart = sentAtStart;
        }
    }

    private static final Map<Key, Hold> HOLDS = new ConcurrentHashMap<>();

    private LightSync() {}

    public static void init() {
        ServerTickEvents.END_SERVER_TICK.register(LightSync::sweep);
        ServerLifecycleEvents.SERVER_STOPPED.register(
                server -> HOLDS.keySet().removeIf(key -> key.level.getServer() == server));
    }

    /** Whether a hold of {@code ticksHeld} ticks is stuck. At or past the limit, so a skipped tick count cannot miss it. */
    public static boolean timedOut(int ticksHeld) {
        return ticksHeld >= TIMEOUT_TICKS;
    }

    /** Each chunk whose sending is held now, with its dimension and how long, for the stuck-hold log and test timeouts. */
    public static List<String> heldChunks() {
        List<String> held = new ArrayList<>();
        HOLDS.forEach((key, hold) -> held.add("chunk " + key.chunk + " in "
                + key.level.dimension().identifier() + " held for "
                + (key.level.getServer().getTickCount() - hold.startTick) + " ticks"));
        return held;
    }

    /** As {@link #holdSendUntilLit(ServerLevel, ChunkPos)}, for every chunk the box touches. */
    public static void holdSendUntilLit(ServerLevel level, BlockPos min, BlockPos max) {
        ChunkPos.rangeClosed(ChunkPos.containing(min), ChunkPos.containing(max))
                .forEach(chunk -> holdSendUntilLit(level, chunk));
    }

    /**
     * Holds the sending of the chunk, and of each of its eight neighbours that has a loaded holder (light spreads at most 15 blocks, so
     * one ring is enough), until the light tasks queued so far are done: vanilla's {@code ChunkMap.waitForLightBeforeSending}, which
     * takes the radius and holds only the chunks within it.
     */
    public static void holdSendUntilLit(ServerLevel level, ChunkPos chunk) {
        List<ChunkPos> sendable = new ArrayList<>();
        ChunkPos.rangeClosed(chunk, 1)
                .filter(near -> level.getChunkSource().chunkMap.getChunkToSend(near.pack()) != null)
                .forEach(sendable::add);
        level.getChunkSource().chunkMap.waitForLightBeforeSending(chunk, 1);
        int now = level.getServer().getTickCount();
        for (ChunkPos held : sendable) {
            Hold existing = HOLDS.get(new Key(level, held));
            if (existing == null) {
                HOLDS.put(new Key(level, held), new Hold(now, trackers(level, held), sentTo(level, held)));
            } else {
                existing.startTick = now; // new light work was queued: the timeout counts from the latest carve
                existing.reported = false;
            }
        }
    }

    private static Set<UUID> trackers(ServerLevel level, ChunkPos chunk) {
        Set<UUID> tracking = new HashSet<>();
        for (ServerPlayer player : level.players()) {
            if (player.getChunkTrackingView().contains(chunk)) {
                tracking.add(player.getUUID());
            }
        }
        return tracking;
    }

    /** The players that track {@code chunk} and have been sent it: it is not pending to them. */
    private static Set<UUID> sentTo(ServerLevel level, ChunkPos chunk) {
        Set<UUID> sent = new HashSet<>();
        for (ServerPlayer player : level.players()) {
            if (player.getChunkTrackingView().contains(chunk)
                    && !player.connection.chunkSender.isPending(chunk.pack())) {
                sent.add(player.getUUID());
            }
        }
        return sent;
    }

    private static void sweep(MinecraftServer server) {
        Iterator<Map.Entry<Key, Hold>> holds = HOLDS.entrySet().iterator();
        while (holds.hasNext()) {
            Map.Entry<Key, Hold> entry = holds.next();
            Key key = entry.getKey();
            Hold hold = entry.getValue();
            if (key.level.getServer() != server) {
                continue;
            }
            if (key.level.getChunkSource().getChunkNow(key.chunk.x(), key.chunk.z()) == null) {
                holds.remove(); // unloaded: the next load sends it the vanilla way
                continue;
            }
            LevelChunk sendable = key.level.getChunkSource().chunkMap.getChunkToSend(key.chunk.pack());
            if (sendable != null) {
                offerAfterHold(key, hold, sendable);
                holds.remove();
                continue;
            }
            if (!hold.reported && timedOut(server.getTickCount() - hold.startTick)) {
                hold.reported = true;
                DeepCharter.LOGGER.error(
                        "A chunk hold is stuck: the light has not released it. Held: {}", heldChunks());
            }
            Set<UUID> tracking = trackers(key.level, key.chunk);
            hold.trackedThroughout.retainAll(tracking);
            hold.sentAtStart.retainAll(tracking);
        }
    }

    /** Offers the chunk again to the players who came into view during the hold, and sends its settled light to those who had it. */
    private static void offerAfterHold(Key key, Hold hold, LevelChunk chunk) {
        ClientboundLightUpdatePacket light = null;
        for (ServerPlayer player : key.level.players()) {
            if (!player.getChunkTrackingView().contains(key.chunk)) {
                continue;
            }
            if (!hold.trackedThroughout.contains(player.getUUID())) {
                player.connection.chunkSender.markChunkPendingToSend(chunk);
            } else if (hold.sentAtStart.contains(player.getUUID())) {
                // Every section of both layers: which sections the neighbour's light changed is not known here, and a
                // ticking chunk that vanilla also updates gets a duplicate, which does no harm.
                if (light == null) {
                    light = new ClientboundLightUpdatePacket(key.chunk, key.level.getLightEngine(), null, null);
                }
                sendLight(player, light);
            }
        }
    }

    private static void sendLight(ServerPlayer player, ClientboundLightUpdatePacket light) {
        player.connection.send(light);
    }
}
