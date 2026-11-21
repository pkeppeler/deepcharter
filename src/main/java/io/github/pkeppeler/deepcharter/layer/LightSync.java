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
 * <p>The safety net (#863, ADR 0068): a client was left with the light a chunk had at its send, because the light settled after
 * the send and no light packet followed, and the cause was not found. {@code LightSync} records the light each player holds of a
 * chunk in a hold's 3x3, and for a window after the hold ends sends a player whose chunk's published light differs from it.
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

    /** The safety net's record of a chunk: the light each player holds of it, and when to compare next and to stop. */
    private static final class NetWatch {
        /** The print of the published light that each player was last sent for the chunk, by the chunk packet or a light packet. */
        final Map<UUID, Integer> held = new HashMap<>();

        int nextCheck;
        int until;

        NetWatch(int nextCheck, int until) {
            this.nextCheck = nextCheck;
            this.until = until;
        }
    }

    /** The chunks in the 3x3 of a hold, from its request to the end of its window (#863). Server thread only. */
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
        ChunkPos.rangeClosed(chunk, 1)
                .filter(near -> level.getChunkSource().getChunkNow(near.x(), near.z()) != null)
                .forEach(near -> watchNet(level, near, now));
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
        NetWatch watch = NET.get(key);
        if (watch != null) {
            int now = key.level.getServer().getTickCount();
            watch.nextCheck = now + NET_INTERVAL_TICKS;
            watch.until = now + NET_WINDOW_TICKS;
        }
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
     * One number for the published light of every section of the chunk, both layers: the data the light engine publishes, which is
     * what a chunk or light packet carries. An empty layer, which holds one value throughout, counts as a fixed number, and its
     * {@code getData()} is not called (it allocates). A published layer is replaced, not written in place, so reading it is safe.
     */
    private static int lightPrint(ServerLevel level, ChunkPos chunk) {
        LevelLightEngine engine = level.getLightEngine();
        int print = 1;
        for (LightLayer layer : LightLayer.values()) {
            for (int y = engine.getMinLightSection(); y < engine.getMaxLightSection(); y++) {
                DataLayer data = engine.getLayerListener(layer).getDataLayerData(SectionPos.of(chunk, y));
                print = 31 * print + (data == null || data.isEmpty() ? 0 : Arrays.hashCode(data.getData()));
            }
        }
        return print;
    }

    /**
     * Starts or extends the net's watch of a chunk in a hold's 3x3, and records that the players who were sent it hold its published
     * light. A chunk that cannot be sent yet has no such player: a player is recorded when the chunk is sent ({@link #chunkSent}).
     */
    private static void watchNet(ServerLevel level, ChunkPos chunk, int now) {
        Key key = new Key(level, chunk);
        NetWatch watch = NET.computeIfAbsent(key, k -> new NetWatch(now + NET_INTERVAL_TICKS, 0));
        watch.until = now + TIMEOUT_TICKS + NET_WINDOW_TICKS;
        if (level.getChunkSource().chunkMap.getChunkToSend(chunk.pack()) == null) {
            return;
        }
        int print = lightPrint(level, chunk);
        for (ServerPlayer player : level.players()) {
            if (player.getChunkTrackingView().contains(chunk)
                    && !player.connection.chunkSender.isPending(chunk.pack())) {
                watch.held.putIfAbsent(player.getUUID(), print);
            }
        }
    }

    /** Called when a chunk packet is about to be built for a player: the player will hold the light published now. */
    public static void chunkSent(ServerPlayer player, LevelChunk chunk) {
        NetWatch watch = NET.get(new Key(player.level(), chunk.getPos()));
        if (watch != null) {
            watch.held.put(player.getUUID(), lightPrint(player.level(), chunk.getPos()));
        }
    }

    /**
     * The safety net: for {@value #NET_WINDOW_TICKS} ticks after a hold ended, every {@value #NET_INTERVAL_TICKS} ticks, sends each
     * player whose recorded light of the chunk differs from its published light one light packet. It compares with what the player
     * was sent, not with an earlier sample, so a light that moved and came back between two samples is still caught. It does not look
     * at why the light changed or why vanilla did not send it. A player vanilla also served gets a duplicate, which does no harm.
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
            if (now > watch.until || key.level.getChunkSource().getChunkNow(key.chunk.x(), key.chunk.z()) == null) {
                watches.remove(); // the window is over, or the chunk unloaded
                continue;
            }
            if (now < watch.nextCheck || HOLDS.containsKey(key)) {
                continue;
            }
            watch.nextCheck = now + NET_INTERVAL_TICKS;
            Set<UUID> tracking = trackers(key.level, key.chunk);
            watch.held.keySet().retainAll(tracking);
            if (watch.held.isEmpty()) {
                continue;
            }
            int print = lightPrint(key.level, key.chunk);
            ClientboundLightUpdatePacket light = null;
            for (ServerPlayer player : key.level.players()) {
                Integer held = watch.held.get(player.getUUID());
                if (held != null && held != print && !player.connection.chunkSender.isPending(key.chunk.pack())) {
                    if (light == null) {
                        light = new ClientboundLightUpdatePacket(key.chunk, key.level.getLightEngine(), null, null);
                    }
                    sendLight(player, light);
                }
            }
        }
    }

    private static void sendLight(ServerPlayer player, ClientboundLightUpdatePacket light) {
        player.connection.send(light);
        ChunkPos chunk = new ChunkPos(light.x(), light.z());
        NetWatch watch = NET.get(new Key(player.level(), chunk));
        if (watch != null) {
            watch.held.put(player.getUUID(), lightPrint(player.level(), chunk));
        }
    }
}
