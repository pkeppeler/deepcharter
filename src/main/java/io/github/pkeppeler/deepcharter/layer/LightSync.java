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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.lighting.LevelLightEngine;
import org.jspecify.annotations.Nullable;

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
 * <p>Each neighbour in the hold also waits for the light tasks queued at the drawn chunk itself (#494): vanilla's wait
 * covers only the tasks queued at the neighbour's own position, and the engine runs different positions' queues in no
 * fixed order. The wait relies on light tasks at one position running in the order they were queued, which
 * {@code ChunkTaskPriorityQueueMixin} guarantees (#1448, ADR 0105).
 *
 * <p>The safety net (#863, ADR 0068): {@code LightSync} records the light each player was sent of a chunk in a hold's 3x3, and for
 * a window that starts when the chunk can be sent sends a player whose chunk's published light differs from it.
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

    /** The light tasks queued at a chunk after its last draw, until they have run. */
    private static final Map<Key, CompletableFuture<?>> QUEUED = new ConcurrentHashMap<>();

    /** The chunks whose extended send is offered to their trackers when it can be sent, until then. */
    private static final Map<Key, RingOffer> OFFERS = new ConcurrentHashMap<>();

    /** An armed offer: the holder it follows and the players sent the chunk since. */
    private record RingOffer(ChunkHolder holder, Set<UUID> sentSince) {}

    /** Server ticks a chunk's published light is compared after the chunk can be sent (5 s). */
    private static final int NET_WINDOW_TICKS = 100;
    /** Server ticks between two comparisons. */
    private static final int NET_INTERVAL_TICKS = 10;

    private static final class NetWatch {
        /** The print of the light each player was last sent for the chunk, by its chunk packet or a light packet. */
        final Map<UUID, Integer> held = new HashMap<>();

        int nextCheck;
        int until;
        /** Set when the window runs out; null while it runs. */
        @Nullable
        Settling settling;
        /** The tick the chunk was first seen held or unsendable, or -1. */
        int unsentSince = -1;

        boolean reported;
        /** Set once the light engine has been waited for {@value #TIMEOUT_TICKS} ticks. */
        boolean slowReported;

        /** A watch opened at {@code now}: its first check and the end of its window are counted from then. */
        NetWatch(int now) {
            this.nextCheck = now + NET_INTERVAL_TICKS;
            this.until = now + NET_WINDOW_TICKS;
        }

        void keepOpen(int now) {
            until = Math.max(until, now + NET_WINDOW_TICKS);
            settling = null;
            slowReported = false;
        }
    }

    /** The light tasks queued for a chunk when its window ran out, and the tick they were queued. */
    private record Settling(CompletableFuture<?> tasks, int since) {}

    /** The chunks in the 3x3 of a hold, until the window after they can be sent runs out and the light settles. Server thread only. */
    private static final Map<Key, NetWatch> NET = new HashMap<>();

    /**
     * The chunks whose watch was dropped because they did not tick and no player held them: they cannot be sent until
     * they tick, and a watch kept open for that would never end (#1297). Each is watched again once it ticks, so its
     * window runs from when it can be sent, as a kept watch's did (#954), or at its send if that comes first. No key is
     * in both this and {@link #NET}. Server thread only.
     */
    private static final Set<Key> DORMANT = new HashSet<>();

    private LightSync() {}

    public static void init() {
        ServerChunkEvents.CHUNK_LOAD.register((level, chunk, newlyGenerated) -> holdLoadedNeighbour(level, chunk));
        ServerChunkEvents.CHUNK_UNLOAD.register(LightSync::forgetUnloaded);
        ServerTickEvents.END_SERVER_TICK.register(LightSync::sweep);
        ServerTickEvents.END_SERVER_TICK.register(LightSync::compareLight);
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            HOLDS.keySet().removeIf(key -> key.level.getServer() == server);
            QUEUED.keySet().removeIf(key -> key.level.getServer() == server);
            OFFERS.keySet().removeIf(key -> key.level.getServer() == server);
            NET.keySet().removeIf(key -> key.level.getServer() == server);
            DORMANT.removeIf(key -> key.level.getServer() == server);
        });
    }

    /** An unloaded chunk's holder is gone, so its send future may never complete: drop what waits on it. */
    private static void forgetUnloaded(ServerLevel level, LevelChunk chunk) {
        Key key = new Key(level, chunk.getPos());
        QUEUED.remove(key);
        OFFERS.remove(key);
        DORMANT.remove(key);
    }

    /** Whether the chunk has a queued-tasks entry or an armed offer. */
    // test-seam: the two maps are private and an unload must leave nothing in either.
    public static boolean hasPending(ServerLevel level, ChunkPos chunk) {
        Key key = new Key(level, chunk);
        return QUEUED.containsKey(key) || OFFERS.containsKey(key);
    }

    /**
     * Whether the chunk's light is being settled: it is held, it waits on a neighbour's queued light tasks or an
     * armed offer ({@link #hasPending}), or it is inside the window after a hold in which its published light is
     * compared with what each player was sent. A seam crossing does not skip the send of such a chunk (#1185): the
     * ahead send bypasses the ring hold (#494). Server thread only.
     */
    public static boolean watching(ServerLevel level, ChunkPos chunk) {
        Key key = new Key(level, chunk);
        return HOLDS.containsKey(key) || NET.containsKey(key) || hasPending(level, chunk);
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
        holdRingUntilTasksRun(level, chunk, sendable);
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

    /**
     * Each neighbour also waits for the tasks queued at {@code chunk} by the draw (#494); one that loads later waits
     * when it loads ({@link #holdLoadedNeighbour}).
     */
    private static void holdRingUntilTasksRun(ServerLevel level, ChunkPos chunk, List<ChunkPos> sendable) {
        CompletableFuture<?> drawn = level.getChunkSource().getLightEngine().waitForPendingTasks(chunk.x(), chunk.z());
        Key key = new Key(level, chunk);
        QUEUED.put(key, drawn);
        var _ = drawn.thenRun(() -> QUEUED.remove(key, drawn));
        ChunkPos.rangeClosed(chunk, 1).filter(near -> !near.equals(chunk)).forEach(near -> {
            ChunkHolder holder = dependOn(level, near, drawn);
            if (holder != null && !sendable.contains(near)) {
                offerWhenSendable(level, near, holder);
            }
        });
    }

    private static @Nullable ChunkHolder dependOn(ServerLevel level, ChunkPos chunk, CompletableFuture<?> tasks) {
        ChunkHolder holder = level.getChunkSource().chunkMap.getUpdatingChunkIfPresent(chunk.pack());
        if (holder != null) {
            holder.addSendDependency(tasks);
        }
        return holder;
    }

    /**
     * Offers a chunk that could not be sent when its send was extended to its trackers once it can be: vanilla marks
     * nothing for a player who starts tracking while {@code getChunkToSend} is null. Players sent the chunk meanwhile,
     * and those already queued for it, are left out.
     */
    private static void offerWhenSendable(ServerLevel level, ChunkPos chunk, ChunkHolder holder) {
        Key key = new Key(level, chunk);
        RingOffer open = OFFERS.get(key);
        if (open != null && open.holder == holder) {
            return; // armed already: it follows the holder's send future as it is extended
        }
        RingOffer offer = new RingOffer(holder, ConcurrentHashMap.newKeySet());
        OFFERS.put(key, offer);
        armOffer(key, offer);
    }

    private static void armOffer(Key key, RingOffer offer) {
        var _ = offer.holder()
                .getSendSyncFuture()
                .thenRun(() -> key.level.getServer().execute(() -> {
                    ServerLevel level = key.level;
                    if (OFFERS.get(key) != offer) {
                        return;
                    }
                    if (level.getChunkSource().chunkMap.getUpdatingChunkIfPresent(key.chunk.pack()) != offer.holder()) {
                        OFFERS.remove(key, offer); // the holder was replaced: its send is not this offer's to make
                        return;
                    }
                    if (!offer.holder().getSendSyncFuture().isDone()) {
                        armOffer(key, offer); // the send was extended again meanwhile
                        return;
                    }
                    OFFERS.remove(key, offer);
                    LevelChunk sendable = offer.holder().getChunkToSend();
                    if (sendable == null) {
                        return; // it does not tick yet: vanilla offers it when it does, reading the send future then
                    }
                    for (ServerPlayer player : level.players()) {
                        if (player.getChunkTrackingView().contains(key.chunk)
                                && !offer.sentSince().contains(player.getUUID())
                                && !player.connection.chunkSender.isPending(key.chunk.pack())) {
                            player.connection.chunkSender.markChunkPendingToSend(sendable);
                        }
                    }
                }));
    }

    /** A chunk that loads while a neighbour's draw still has light tasks queued waits for them. */
    private static void holdLoadedNeighbour(ServerLevel level, LevelChunk chunk) {
        if (QUEUED.isEmpty()) {
            return;
        }
        ChunkPos loaded = chunk.getPos();
        ChunkPos.rangeClosed(loaded, 1).filter(near -> !near.equals(loaded)).forEach(near -> {
            CompletableFuture<?> tasks = QUEUED.get(new Key(level, near));
            if (tasks != null && !tasks.isDone()) {
                dependOn(level, loaded, tasks);
            }
        });
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
            watch.keepOpen(now);
        }
        ClientboundLightUpdatePacket light = null;
        for (ServerPlayer player : key.level.players()) {
            if (!player.getChunkTrackingView().contains(key.chunk)) {
                continue;
            }
            if (!hold.trackedThroughout.contains(player.getUUID())) {
                // A rider swapped in with this chunk was sent it at the swap, after the light settled: it holds
                // it already.
                if (!SeamSender.skippedAtCrossing(player, key.level, key.chunk)) {
                    player.connection.chunkSender.markChunkPendingToSend(chunk);
                }
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
     * One number for the published light of every section of the chunk, both layers. An empty layer counts as 0 and its
     * {@code getData()} is not called (it allocates). A published layer is replaced, never written in place.
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

    /** Starts or extends the watch of a chunk and records the light of the players already sent it; the others are recorded at their send. */
    private static void watchNet(ServerLevel level, ChunkPos chunk, int now) {
        Key key = new Key(level, chunk);
        DORMANT.remove(key);
        NetWatch watch = NET.computeIfAbsent(key, k -> new NetWatch(now));
        watch.keepOpen(now);
        if (level.getChunkSource().chunkMap.getChunkToSend(chunk.pack()) == null) {
            return;
        }
        int print = lightPrint(level, chunk);
        for (UUID sent : sentTo(level, chunk)) {
            watch.held.putIfAbsent(sent, print);
        }
    }

    /**
     * Called when {@code player} is sent the chunk of {@code level} as a seam chunk, ahead of its swap into {@code
     * level} (#1229): it holds the light published now, and the watch compares it later like any other.
     */
    public static void seamSent(ServerPlayer player, ServerLevel level, ChunkPos chunk) {
        NetWatch watch = sentWatch(new Key(level, chunk));
        if (watch != null) {
            watch.held.put(player.getUUID(), lightPrint(level, chunk));
            watch.keepOpen(level.getServer().getTickCount());
        }
    }

    /** Called when a chunk packet is about to be built for a player: the player will hold the light published now. */
    public static void chunkSent(ServerPlayer player, LevelChunk chunk) {
        RingOffer offer = OFFERS.get(new Key(player.level(), chunk.getPos()));
        if (offer != null) {
            offer.sentSince().add(player.getUUID());
        }
        NetWatch watch = sentWatch(new Key(player.level(), chunk.getPos()));
        if (watch != null) {
            watch.held.put(player.getUUID(), lightPrint(player.level(), chunk.getPos()));
            watch.keepOpen(player.level().getServer().getTickCount());
        }
    }

    /** Whether the chunk's holder has its ticking chunk: until it has, the chunk cannot be sent, whatever its light. */
    private static boolean ticking(Key key) {
        ChunkHolder holder = key.level.getChunkSource().chunkMap.getUpdatingChunkIfPresent(key.chunk.pack());
        return holder != null && holder.getTickingChunk() != null;
    }

    /** The watch of a chunk being sent, armed again if it was dropped as {@link #DORMANT}; null if it has none. */
    private static @Nullable NetWatch sentWatch(Key key) {
        // For a chunk sent in the tick it came to tick, before compareLight re-arms it: no test can stage that order.
        if (DORMANT.remove(key)) {
            return NET.computeIfAbsent(
                    key, k -> new NetWatch(key.level.getServer().getTickCount()));
        }
        return NET.get(key);
    }

    /**
     * For {@value #NET_WINDOW_TICKS} ticks after a watched chunk can be sent, every {@value #NET_INTERVAL_TICKS} ticks, sends each player whose
     * recorded light of the chunk differs from its published light one light packet. A player vanilla also served gets a duplicate.
     */
    private static void compareLight(MinecraftServer server) {
        int now = server.getTickCount();
        DORMANT.removeIf(key -> {
            boolean woke = key.level.getServer() == server && ticking(key);
            if (woke) {
                NET.putIfAbsent(key, new NetWatch(now));
            }
            return woke;
        });
        Iterator<Map.Entry<Key, NetWatch>> watches = NET.entrySet().iterator();
        while (watches.hasNext()) {
            Map.Entry<Key, NetWatch> entry = watches.next();
            Key key = entry.getKey();
            NetWatch watch = entry.getValue();
            if (key.level.getServer() != server) {
                continue;
            }
            if (key.level.getChunkSource().getChunkNow(key.chunk.x(), key.chunk.z()) == null) {
                watches.remove(); // the chunk unloaded
                continue;
            }
            boolean held = HOLDS.containsKey(key);
            if (held || key.level.getChunkSource().chunkMap.getChunkToSend(key.chunk.pack()) == null) {
                // Not sent yet, so nothing it was sent can be stale: the window starts when it can be sent (#954).
                watch.keepOpen(now);
                if (!ticking(key)) {
                    // It waits to tick, not for light: a chunk on the rim of the loaded area may never tick (#1297).
                    if (!held && watch.held.isEmpty()) {
                        watches.remove();
                        DORMANT.add(key);
                        continue;
                    }
                    watch.unsentSince = -1;
                } else if (watch.unsentSince < 0) {
                    watch.unsentSince = now;
                } else if (!watch.reported && timedOut(now - watch.unsentSince)) {
                    watch.reported = true;
                    DeepCharter.LOGGER.warn(
                            "A light watch has waited {} ticks for chunk {} in {} to be sendable",
                            now - watch.unsentSince,
                            key.chunk,
                            key.level.dimension().identifier());
                }
            } else {
                watch.unsentSince = -1;
                watch.reported = false;
            }
            boolean over = !held && windowOver(key, watch, now);
            if (!over && (now < watch.nextCheck || held)) {
                continue;
            }
            watch.nextCheck = now + NET_INTERVAL_TICKS;
            Set<UUID> tracking = trackers(key.level, key.chunk);
            watch.held.keySet().retainAll(tracking);
            if (over) {
                watches.remove(); // after this last comparison
            }
            if (watch.held.isEmpty()) {
                continue;
            }
            int print = lightPrint(key.level, key.chunk);
            ClientboundLightUpdatePacket light = null;
            for (ServerPlayer player : key.level.players()) {
                Integer sentPrint = watch.held.get(player.getUUID());
                if (sentPrint != null
                        && sentPrint != print
                        && !player.connection.chunkSender.isPending(key.chunk.pack())) {
                    if (light == null) {
                        light = new ClientboundLightUpdatePacket(key.chunk, key.level.getLightEngine(), null, null);
                    }
                    sendLight(player, light);
                }
            }
        }
    }

    /**
     * Whether the watch is over: its window has run out and the light tasks queued for the chunk by then have run. A light engine
     * that is behind publishes in wall time, not in ticks, so no tick count ends the watch before they have: a player would keep
     * the old light (#954). A watch that has waited {@value #TIMEOUT_TICKS} ticks is reported once and kept.
     */
    private static boolean windowOver(Key key, NetWatch watch, int now) {
        if (now <= watch.until) {
            return false;
        }
        if (watch.held.isEmpty()) {
            return true; // no player to send to
        }
        Settling settling = watch.settling;
        if (settling == null) {
            settling = new Settling(
                    key.level.getChunkSource().getLightEngine().waitForPendingTasks(key.chunk.x(), key.chunk.z()), now);
            watch.settling = settling;
        }
        if (settling.tasks.isDone()) {
            return true;
        }
        if (!watch.slowReported && timedOut(now - settling.since)) {
            watch.slowReported = true;
            DeepCharter.LOGGER.warn(
                    "A light watch of chunk {} in {} has waited {} ticks for the light engine to run its tasks",
                    key.chunk,
                    key.level.dimension().identifier(),
                    now - settling.since);
        }
        return false;
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
