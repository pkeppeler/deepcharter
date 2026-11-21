package io.github.pkeppeler.deepcharter.layer;

import io.github.pkeppeler.deepcharter.DeepCharter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
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
import net.minecraft.core.SectionPos;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.lighting.LevelLightEngine;

/**
 * Light is computed off the server thread, so a client sent a chunk while light is pending keeps the old light: vanilla holds a
 * chunk's send until the light tasks queued for it are done, and this holds the sends of a chunk and its neighbours the same way.
 * Call this after the last block placed in a chunk a player may be sent. Vanilla offers a chunk to its trackers only once, so this
 * also offers it again, when the hold ends, to those who came into view during the hold (issue 524).
 *
 * <p>A player who already had a chunk of the hold when it began is sent the chunk's settled light when the hold ends (#824): a
 * neighbour's draw changes the light of that chunk, and vanilla drops a light change for a chunk that does not tick (issue 427), so
 * the player's client keeps the old light.
 *
 * <p>The safety net (#863, ADR 0068): a client was left with the light a chunk had at its send while the light settled after the
 * hold, and the cause was not found. For a short window after a hold ends, the chunk's published light is compared on, and a change
 * is sent to the players who track it.
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

    /** Server ticks a chunk's published light is compared after its hold ended (5 s). */
    private static final int NET_WINDOW_TICKS = 100;
    /** Server ticks between two comparisons. */
    private static final int NET_INTERVAL_TICKS = 10;

    /** The safety net's record of a chunk: the light it published when last compared, and when to compare next and stop. */
    private static final class NetWatch {
        int print;
        int nextCheck;
        final int until;

        NetWatch(int print, int nextCheck, int until) {
            this.print = print;
            this.nextCheck = nextCheck;
            this.until = until;
        }
    }

    /** The chunks whose hold ended and whose light is compared on (#863). Server thread only. */
    private static final Map<Key, NetWatch> NET = new HashMap<>();

    private LightSync() {}

    public static void init() {
        ServerTickEvents.END_SERVER_TICK.register(LightSync::sweep);
        ServerTickEvents.END_SERVER_TICK.register(LightSync::compareLight);
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            HOLDS.keySet().removeIf(key -> key.level.getServer() == server);
            NET.keySet().removeIf(key -> key.level.getServer() == server);
        });
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
     * one ring is enough), until the light tasks queued so far are done. Vanilla's {@code ChunkMap.waitForLightBeforeSending(pos, r)}
     * adds the dependency over {@code rangeClosed(pos, r + 1)}, so radius 0 already holds that 3x3. The hold does not make a
     * player's copy of a chunk that was sent before it right: for that, see the class comment.
     */
    public static void holdSendUntilLit(ServerLevel level, ChunkPos chunk) {
        List<ChunkPos> sendable = new ArrayList<>();
        ChunkPos.rangeClosed(chunk, 1)
                .filter(near -> level.getChunkSource().chunkMap.getChunkToSend(near.pack()) != null)
                .forEach(sendable::add);
        level.getChunkSource().chunkMap.waitForLightBeforeSending(chunk, 0);
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
        int now = key.level.getServer().getTickCount();
        NET.put(key, new NetWatch(lightPrint(key.level, key.chunk), now + NET_INTERVAL_TICKS, now + NET_WINDOW_TICKS));
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

    /**
     * One number for the published light of every section of the chunk, both layers. It reads the section data the light engine
     * publishes, and a chunk's light that a client was sent is that data.
     */
    private static int lightPrint(ServerLevel level, ChunkPos chunk) {
        LevelLightEngine engine = level.getLightEngine();
        int print = 1;
        for (LightLayer layer : LightLayer.values()) {
            for (int y = engine.getMinLightSection(); y < engine.getMaxLightSection(); y++) {
                DataLayer data = engine.getLayerListener(layer).getDataLayerData(SectionPos.of(chunk, y));
                print = 31 * print + (data == null ? 0 : Arrays.hashCode(data.getData()));
            }
        }
        return print;
    }

    /**
     * The safety net: for {@value #NET_WINDOW_TICKS} ticks after a hold ended, compares the chunk's published light every
     * {@value #NET_INTERVAL_TICKS} ticks, and when it changed sends the players who track the chunk one light packet. It does not
     * look at why the light changed or why vanilla did not send it, so a change vanilla fails to broadcast, whatever the reason, is
     * sent. A player vanilla also served gets a duplicate, which does no harm.
     */
    private static void compareLight(MinecraftServer server) {
        int now = server.getTickCount();
        Iterator<Map.Entry<Key, NetWatch>> watches = NET.entrySet().iterator();
        while (watches.hasNext()) {
            Map.Entry<Key, NetWatch> entry = watches.next();
            Key key = entry.getKey();
            NetWatch watch = entry.getValue();
            if (key.level.getServer() != server) {
                continue;
            }
            if (now > watch.until
                    || HOLDS.containsKey(key)
                    || key.level.getChunkSource().getChunkNow(key.chunk.x(), key.chunk.z()) == null) {
                watches.remove(); // over, or held again (that hold's end watches it anew), or unloaded
                continue;
            }
            if (now < watch.nextCheck) {
                continue;
            }
            watch.nextCheck = now + NET_INTERVAL_TICKS;
            int print = lightPrint(key.level, key.chunk);
            if (print == watch.print) {
                continue;
            }
            watch.print = print;
            ClientboundLightUpdatePacket light =
                    new ClientboundLightUpdatePacket(key.chunk, key.level.getLightEngine(), null, null);
            for (ServerPlayer player : key.level.players()) {
                if (player.getChunkTrackingView().contains(key.chunk)
                        && !player.connection.chunkSender.isPending(key.chunk.pack())) {
                    sendLight(player, light);
                }
            }
        }
    }

    private static void sendLight(ServerPlayer player, ClientboundLightUpdatePacket light) {
        player.connection.send(light);
    }
}
