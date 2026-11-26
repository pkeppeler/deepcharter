package io.github.pkeppeler.deepcharter.layer;

import io.github.pkeppeler.deepcharter.DeepCharter;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongPredicate;
import java.util.function.Supplier;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.CommonPlayerSpawnInfo;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import org.jspecify.annotations.Nullable;

/**
 * The server's second half of a seam (#1185, ADR 0089): the riders of a vehicle that {@link SeamStreaming} holds an arrival area open
 * for are sent that area, chunk by chunk, ahead of the crossing, so a client that has the seam channels holds it already when the
 * respawn into the level below arrives.
 *
 * <p>Each tick, for every rider of an approach that ready, whose client announced the seam
 * channels and who is in the approach's upper level: a {@link SeamPreparePayload} once per area (again when the centre moves), then the
 * chunks of the area nearest the centre first, until the tick's estimated bytes reach {@link LayerTuning#seamBytesPerTick()}, and at
 * least one. A rider whose approach ends, or who leaves its level, is sent a {@link SeamDiscardPayload}; a disconnect and the server
 * stopping drop the state without sending.
 *
 * <p>The state is on the server thread only and holds no level, entity or player: a rider's UUID, a level key, a chunk and the chunks sent.
 */
public final class SeamSender {
    /** The most a custom payload may take on the wire in vanilla's codec: a bigger one is an encoder error that drops the client. */
    private static final int MAX_PAYLOAD_SIZE = 1_048_576;

    /** Bytes kept under the payload limit for what {@link #estimatedBytes} does not count: heightmaps, block entities and the codec's framing. */
    private static final int ESTIMATE_MARGIN = 64 * 1024;

    /** Bytes the estimate counts for each light section of a chunk packet: a light array is 2048 bytes. */
    private static final int LIGHT_ARRAY_BYTES = 2048;

    /** What the server knows of a rider's seam: the area it was last sent, and the chunks of it it holds. */
    private static final class RiderSeam {
        final ResourceKey<Level> lower;
        ChunkPos centre;
        /** Prepares sent to this rider: the generation of the current one (#1229). */
        int generation;
        /** Whether the client accepted the current generation and has not refused it since. */
        boolean accepted;

        final LongSet sent = new LongOpenHashSet();
        /** Positions in the area that were sent and have since changed or may have: the crossing sends them again. */
        final LongSet stale = new LongOpenHashSet();
        /**
         * Positions of the carve ahead of a swap, sent again at once: the crossing skips them whatever {@link
         * LightSync} is settling, since the client holds them right.
         */
        final LongSet fresh = new LongOpenHashSet();
        /** Positions the crossing skipped, because the client holds them already. */
        final LongSet skipped = new LongOpenHashSet();
        /** Estimated bytes sent to this rider ahead. */
        long bytes;
        /** Positions whose packet is over {@link #MAX_PAYLOAD_SIZE}: never sent, and not tried again while the area stays. */
        final LongSet oversize = new LongOpenHashSet();

        RiderSeam(ResourceKey<Level> lower, ChunkPos centre) {
            this.lower = lower;
            this.centre = centre;
        }
    }

    /** A chunk of one level, as a cache key. */
    private record Chunk(ResourceKey<Level> level, long pos) {}

    /** The chunks a crossing skipped for a rider: kept until {@code until}, for a client that reports them missing. */
    private record Skipped(ResourceKey<Level> level, LongSet positions, long until) {}

    /** Whether a crossing's teleport is under way, so the sends of the chunks around the arrival may be skipped. Server thread only. */
    private static boolean crossing;

    /**
     * A swap of a rider into {@code level} at {@code generation}, kept until {@code until} for a client that reports it
     * failed.
     */
    private record Swapped(ResourceKey<Level> level, int generation, long until) {}

    /** Swaps by rider UUID. Server thread only. */
    private static final Map<UUID, Swapped> SWAPPED = new HashMap<>();

    /** Skipped chunks by rider UUID. Server thread only. */
    private static final Map<UUID, Skipped> RECENT = new HashMap<>();

    /** Riders' seams by UUID. Server thread only. */
    private static final Map<UUID, RiderSeam> STATE = new HashMap<>();

    /** The payloads built this tick, shared by every rider that needs the chunk; null for a chunk that cannot be sent. Server thread only. */
    private static final Map<Chunk, @Nullable SeamChunkPayload> BUILT = new HashMap<>();

    /**
     * Chunks out from the centre that can be sent: one less than the ticket's radius. A ticket of radius {@code r} puts the chunk at
     * distance {@code d} from its centre at level {@code 33 - r + d}, and vanilla's {@code getChunkToSend} gives only a chunk that ticks
     * blocks, level 32 or lower, so the outermost ring is loaded but not sendable; vanilla sends it after the crossing, from the
     * player's own tickets.
     */
    public static final int SEND_RADIUS = LayerTuning.DEFAULT.seamRadius() - 1;

    /** The offsets of the chunks sent ahead, nearest first. */
    private static final List<ChunkPos> AREA = offsets(SEND_RADIUS);

    private SeamSender() {}

    public static void init() {
        SeamPreparePayload.init();
        SeamChunkPayload.init();
        SeamDiscardPayload.init();
        SeamMissedPayload.init();
        SeamSwapPayload.init();
        SeamAcceptPayload.init();
        SeamRefusePayload.init();
        ServerTickEvents.END_SERVER_TICK.register(SeamSender::tick);
        ServerPlayNetworking.registerGlobalReceiver(
                SeamMissedPayload.TYPE, (payload, context) -> missed(context.player(), payload.dimension()));
        ServerPlayNetworking.registerGlobalReceiver(
                SeamAcceptPayload.TYPE,
                (payload, context) -> answered(context.player(), payload.dimension(), payload.generation(), true));
        ServerPlayNetworking.registerGlobalReceiver(SeamRefusePayload.TYPE, (payload, context) -> {
            answered(context.player(), payload.dimension(), payload.generation(), false);
            if (payload.reason() == SeamRefuseReason.SWAP_FAILED) {
                swapFailed(context.player(), payload.dimension(), payload.generation());
            }
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            STATE.remove(handler.player.getUUID());
            RECENT.remove(handler.player.getUUID());
            SWAPPED.remove(handler.player.getUUID());
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            STATE.clear();
            RECENT.clear();
            SWAPPED.clear();
            crossing = false;
            BUILT.clear();
        });
    }

    /**
     * The chunk offsets of an area of {@code radius} chunks, nearest the centre first: by squared distance, then by x, then by z, so the
     * order is the same on every call.
     */
    public static List<ChunkPos> offsets(int radius) {
        List<ChunkPos> offsets = new ArrayList<>();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                offsets.add(new ChunkPos(dx, dz));
            }
        }
        offsets.sort(Comparator.comparingInt((ChunkPos offset) -> offset.x() * offset.x() + offset.z() * offset.z())
                .thenComparingInt(ChunkPos::x)
                .thenComparingInt(ChunkPos::z));
        return List.copyOf(offsets);
    }

    /**
     * Whether a chunk packet estimated at {@code estimatedBytes} is sent: at most the payload limit less {@link #ESTIMATE_MARGIN}, for
     * what the estimate leaves out (heightmaps and block entities).
     */
    public static boolean fitsPayload(int estimatedBytes) {
        return estimatedBytes <= MAX_PAYLOAD_SIZE - ESTIMATE_MARGIN;
    }

    /** The bytes a chunk packet is estimated to take on the wire: its section data, and 2048 for each light array. Not its heightmaps or block entities. */
    public static int estimatedBytes(ClientboundLevelChunkWithLightPacket packet) {
        int lightArrays = packet.lightData().skyUpdates().size()
                + packet.lightData().blockUpdates().size();
        return packet.chunkData().getReadBuffer().readableBytes() + LIGHT_ARRAY_BYTES * lightArrays;
    }

    /**
     * Runs {@code teleport}, a crossing of {@code vehicle} and its riders into {@code to}, as a seam crossing: while it runs,
     * {@link #takePreSent} may skip the chunks vanilla would send each rider around its arrival, and when it returns an entity (a teleport that threw or was refused moved nobody) each rider's seam into
     * {@code to} becomes the chunks it skipped, kept for {@link LayerTuning#seamMissedWindowTicks()} ticks.
     */
    public static @Nullable Entity crossing(ServerLevel to, Entity vehicle, Supplier<@Nullable Entity> teleport) {
        Set<UUID> riders = new HashSet<>();
        vehicle.getPassengersAndSelf().forEach(crossed -> riders.add(crossed.getUUID()));
        crossing = true;
        Entity arrived;
        try {
            arrived = teleport.get();
        } finally {
            crossing = false;
        }
        // A teleport that threw or was refused moved nobody: the riders still approach the floor and keep their seams.
        if (arrived != null) {
            endCrossing(to, riders);
        }
        return arrived;
    }

    private static void endCrossing(ServerLevel to, Set<UUID> riders) {
        long until = missedWindowEnd(to);
        Iterator<Map.Entry<UUID, RiderSeam>> seams = STATE.entrySet().iterator();
        while (seams.hasNext()) {
            Map.Entry<UUID, RiderSeam> entry = seams.next();
            RiderSeam seam = entry.getValue();
            if (!seam.lower.equals(to.dimension()) || !riders.contains(entry.getKey())) {
                continue;
            }
            DeepCharter.LOGGER.info(
                    "Seam crossing of {} into {}: {} chunks sent ahead ({} bytes), {} skipped at the crossing, {} changed since",
                    entry.getKey(),
                    to.dimension().identifier(),
                    seam.sent.size(),
                    seam.bytes,
                    seam.skipped.size(),
                    seam.stale.size());
            RECENT.put(entry.getKey(), new Skipped(seam.lower, seam.skipped, until));
            seams.remove();
        }
    }

    /**
     * Whether vanilla's mark of {@code pos} of {@code level} as pending to send to {@code player} is skipped: during a crossing, when the
     * rider was sent the chunk ahead, it has not changed since, and its light is neither being settled nor
     * waiting for a send to be watched again ({@link LightSync#dormant}).
     */
    public static boolean takePreSent(ServerPlayer player, ServerLevel level, ChunkPos pos) {
        if (!crossing) {
            return false;
        }
        RiderSeam seam = STATE.get(player.getUUID());
        long packed = pos.pack();
        if (seam == null
                || !seam.lower.equals(level.dimension())
                || !seam.sent.contains(packed)
                || seam.stale.contains(packed)
                || (!seam.fresh.contains(packed)
                        && (LightSync.watching(level, pos) || LightSync.dormant(level, pos)))) {
            return false;
        }
        seam.skipped.add(packed);
        return true;
    }

    /** The server tick at which a crossing into {@code level} stops being recoverable by a client that reports it failed. */
    static long missedWindowEnd(ServerLevel level) {
        return (long) level.getServer().getTickCount() + LayerTuning.DEFAULT.seamMissedWindowTicks();
    }

    /** Records that {@code rider} was swapped into {@code to} on the generation it last accepted. */
    static void noteSwap(ServerPlayer rider, ServerLevel to) {
        SWAPPED.put(rider.getUUID(), new Swapped(to.dimension(), generationOf(rider), missedWindowEnd(to)));
    }

    /**
     * Whether the crossing of {@code player} into {@code level} left the chunk unsent, because the player holds it
     * already.
     */
    static boolean skippedAtCrossing(ServerPlayer player, ServerLevel level, ChunkPos pos) {
        Skipped skipped = RECENT.get(player.getUUID());
        return skipped != null
                && skipped.level().equals(level.dimension())
                && skipped.positions().contains(pos.pack());
    }

    /** The generation of the prepare most recently sent to {@code rider}, or 0 when it has no seam. */
    static int generationOf(ServerPlayer rider) {
        RiderSeam seam = STATE.get(rider.getUUID());
        return seam == null ? 0 : seam.generation;
    }

    /** Chunks {@link #resendCarved} is made to find gone, as one unloaded since it was sent is. Server thread and tests. */
    private static final Set<ChunkPos> VANISHED = ConcurrentHashMap.newKeySet();

    /** Makes {@link #resendCarved} find {@code pos} not loaded while {@code vanished}. */
    // test-seam: SeamSwapTest needs a chunk gone since it was sent, which a test cannot unload on demand; one chunk, so
    // that GameTests running at once do not meet each other's
    public static void setVanished(ChunkPos pos, boolean vanished) {
        if (vanished) {
            VANISHED.add(pos);
        } else {
            VANISHED.remove(pos);
        }
    }

    /**
     * Sends each of {@code riders} the chunks around {@code arrival} that changed since they were sent (the pocket a
     * crossing carved and the shell round it), at once and over the byte budget. They count as held for the crossing
     * whatever {@link LightSync} is settling, and {@link LightSync} sends them again once their light is. The server
     * does not wait for that light here: the light engine's tasks run on the server thread, so a wait on it from this
     * thread only ends at its timeout. Every chunk is built and checked before any is sent. Returns false, sending
     * nothing, if a rider has no seam or a chunk is not loaded or too big to send, so the crossing must not be a swap.
     */
    public static boolean resendCarved(List<ServerPlayer> riders, ServerLevel to, ChunkPos arrival) {
        List<RiderSeam> seams = new ArrayList<>();
        for (ServerPlayer rider : riders) {
            RiderSeam seam = STATE.get(rider.getUUID());
            if (seam == null) {
                return false;
            }
            seams.add(seam);
        }
        Map<ChunkPos, SeamChunkPayload> payloads = new LinkedHashMap<>();
        for (ChunkPos pos : ChunkPos.rangeClosed(arrival, 1).toList()) {
            long packed = pos.pack();
            if (seams.stream().noneMatch(seam -> seam.stale.contains(packed))) {
                continue;
            }
            // Not getChunkToSend: the carve's light hold keeps that null until a later tick.
            LevelChunk chunk =
                    VANISHED.contains(pos) ? null : to.getChunkSource().getChunkNow(pos.x(), pos.z());
            if (chunk == null) {
                return false;
            }
            SeamChunkPayload payload = new SeamChunkPayload(
                    to.dimension(), new ClientboundLevelChunkWithLightPacket(chunk, to.getLightEngine(), null, null));
            if (!fitsPayload(estimatedBytes(payload.chunk()))) {
                return false;
            }
            payloads.put(pos, payload);
        }
        for (int i = 0; i < riders.size(); i++) {
            ServerPlayer rider = riders.get(i);
            RiderSeam seam = seams.get(i);
            for (Map.Entry<ChunkPos, SeamChunkPayload> entry : payloads.entrySet()) {
                long packed = entry.getKey().pack();
                if (!seam.stale.contains(packed)) {
                    continue;
                }
                ServerPlayNetworking.send(rider, entry.getValue());
                LightSync.seamSent(rider, to, entry.getKey());
                seam.bytes += estimatedBytes(entry.getValue().chunk());
                seam.stale.remove(packed);
                seam.fresh.add(packed);
            }
        }
        return true;
    }

    /** A block of {@code level} changed: each rider that was sent the chunk of it, or one beside it, ahead no longer holds it right. */
    public static void blockChanged(ServerLevel level, BlockPos pos) {
        if (STATE.isEmpty()) {
            return;
        }
        int chunkX = pos.getX() >> 4;
        int chunkZ = pos.getZ() >> 4;
        for (RiderSeam seam : STATE.values()) {
            if (!seam.lower.equals(level.dimension())) {
                continue;
            }
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    long packed = ChunkPos.pack(chunkX + dx, chunkZ + dz);
                    if (seam.sent.contains(packed)) {
                        seam.stale.add(packed);
                        seam.fresh.remove(packed);
                    }
                }
            }
        }
    }

    /**
     * A client answered the prepare of {@code generation} for {@code dimension}: it accepted, or it refused. An answer
     * to another dimension or an older generation is ignored, so a late accept never makes a rider ready for a prepare
     * it was not sent.
     */
    private static void answered(ServerPlayer player, ResourceKey<Level> dimension, int generation, boolean accept) {
        RiderSeam seam = STATE.get(player.getUUID());
        if (seam == null || !seam.lower.equals(dimension) || seam.generation != generation) {
            return;
        }
        seam.accepted = accept;
    }

    /**
     * A client could not take the swap of {@code generation} into {@code dimension} it was sent: if the server swapped
     * that rider then, a moment ago, the rider alone is respawned where it is ({@link SeamSwap#recover}). Its vehicle
     * and the other riders are in the new level already and are not touched, so nobody is split from the vehicle.
     */
    private static void swapFailed(ServerPlayer player, ResourceKey<Level> dimension, int generation) {
        Swapped swapped = SWAPPED.get(player.getUUID());
        if (swapped == null
                || !swapped.level().equals(dimension)
                || swapped.generation() != generation
                || !player.level().dimension().equals(dimension)
                || player.isRemoved()) {
            return;
        }
        SWAPPED.remove(player.getUUID());
        DeepCharter.LOGGER.warn(
                "{} could not take the swap into {}: it is respawned there", player.getUUID(), dimension.identifier());
        SeamSwap.recover(player);
    }

    /**
     * Whether a crossing of {@code rider} into {@code to}, arriving in {@code arrivalChunk}, can be a swap: its client
     * can receive the swap, accepted the current prepare and has not refused it since, and holds every chunk within
     * {@link LayerTuning#seamSwapRadius()} of its area's centre, with the arrival inside it. A vehicle swaps only if
     * each of its riders passes: the vehicle never splits.
     */
    public static boolean readyToSwap(ServerPlayer rider, ServerLevel to, ChunkPos arrivalChunk) {
        RiderSeam seam = STATE.get(rider.getUUID());
        return seam != null
                && seam.accepted
                && seam.lower.equals(to.dimension())
                && ServerPlayNetworking.canSend(rider, SeamSwapPayload.TYPE)
                && holdsSwapArea(seam.sent::contains, seam.centre, arrivalChunk, LayerTuning.DEFAULT.seamSwapRadius());
    }

    /**
     * Whether {@code arrival} lies within {@code radius} chunks (Chebyshev) of {@code centre}, and every chunk within
     * {@code radius} of {@code centre} satisfies {@code sent}: the area the rider's held level has, with the arrival
     * inside it.
     */
    public static boolean holdsSwapArea(LongPredicate sent, ChunkPos centre, ChunkPos arrival, int radius) {
        if (Math.max(Math.abs(arrival.x() - centre.x()), Math.abs(arrival.z() - centre.z())) > radius) {
            return false;
        }
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (!sent.test(ChunkPos.pack(centre.x() + dx, centre.z() + dz))) {
                    return false;
                }
            }
        }
        return true;
    }

    /** A client crossed into {@code dimension} without the level it was sent: send it the chunks the crossing skipped. */
    private static void missed(ServerPlayer player, ResourceKey<Level> dimension) {
        Skipped skipped = RECENT.remove(player.getUUID());
        if (skipped == null
                || !skipped.level().equals(dimension)
                || !player.level().dimension().equals(dimension)) {
            return;
        }
        ServerLevel level = player.level();
        int resent = 0;
        for (long packed : skipped.positions()) {
            LevelChunk chunk = level.getChunkSource().chunkMap.getChunkToSend(packed);
            if (chunk != null) {
                player.connection.chunkSender.markChunkPendingToSend(chunk);
                resent++;
            }
        }
        DeepCharter.LOGGER.warn(
                "{} reported the seam into {} missing: {} skipped chunks sent again",
                player.getUUID(),
                dimension.identifier(),
                resent);
    }

    private static void tick(MinecraftServer server) {
        SeamSwap.tick(server);
        if (!RECENT.isEmpty()) {
            long now = server.getTickCount();
            RECENT.values().removeIf(skipped -> skipped.until() <= now);
        }
        if (!SWAPPED.isEmpty()) {
            long now = server.getTickCount();
            SWAPPED.values().removeIf(swapped -> swapped.until() <= now);
        }
        List<SeamStreaming.Approach> approaches = SeamStreaming.approaches();
        if (approaches.isEmpty() && STATE.isEmpty()) {
            return;
        }
        Map<UUID, SeamStreaming.Approach> serving = new HashMap<>();
        for (SeamStreaming.Approach approach : approaches) {
            approach.riders().forEach(rider -> serving.put(rider, approach));
        }
        endSeams(server, serving);
        serving.forEach((rider, approach) -> serve(server, rider, approach));
        BUILT.clear();
    }

    /** Discards the seam of each rider whose approach has ended or who left the approach's level, and drops the one of each who left. */
    private static void endSeams(MinecraftServer server, Map<UUID, SeamStreaming.Approach> serving) {
        Iterator<Map.Entry<UUID, RiderSeam>> seams = STATE.entrySet().iterator();
        while (seams.hasNext()) {
            Map.Entry<UUID, RiderSeam> entry = seams.next();
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (player == null) {
                seams.remove();
                continue;
            }
            SeamStreaming.Approach approach = serving.get(entry.getKey());
            if (approach == null || !approach.upper().equals(player.level().dimension())) {
                discard(player, entry.getValue().lower);
                seams.remove();
            }
        }
    }

    private static void serve(MinecraftServer server, UUID rider, SeamStreaming.Approach approach) {
        ServerPlayer player = server.getPlayerList().getPlayer(rider);
        ServerLevel below = server.getLevel(approach.lower());
        if (player == null
                || below == null
                || !approach.ready()
                || !ServerPlayNetworking.canSend(player, SeamChunkPayload.TYPE)
                || !player.level().dimension().equals(approach.upper())) {
            return;
        }
        RiderSeam seam = STATE.get(rider);
        if (seam != null && !seam.lower.equals(approach.lower())) {
            discard(player, seam.lower);
            seam = null;
        }
        if (seam == null) {
            seam = new RiderSeam(approach.lower(), approach.centre());
            STATE.put(rider, seam);
            prepare(player, below, seam);
        } else if (!seam.centre.equals(approach.centre())) {
            seam.centre = approach.centre();
            trimTo(seam);
            prepare(player, below, seam);
        }
        stream(player, below, seam);
    }

    /** Forgets what lies outside the new area: the client holds those chunks until its background level drops them. */
    private static void trimTo(RiderSeam seam) {
        LongSet inArea = new LongOpenHashSet();
        for (ChunkPos offset : AREA) {
            inArea.add(ChunkPos.pack(seam.centre.x() + offset.x(), seam.centre.z() + offset.z()));
        }
        seam.sent.removeIf(pos -> !inArea.contains(pos));
        seam.stale.removeIf(pos -> !inArea.contains(pos));
        seam.fresh.removeIf(pos -> !inArea.contains(pos));
        seam.oversize.clear();
    }

    private static void prepare(ServerPlayer player, ServerLevel below, RiderSeam seam) {
        seam.generation++;
        seam.accepted = false;
        CommonPlayerSpawnInfo info = player.createCommonSpawnInfo(below);
        ServerPlayNetworking.send(
                player,
                new SeamPreparePayload(
                        below.dimension(),
                        info.dimensionType().unwrapKey().orElseThrow(),
                        info.seed(),
                        info.seaLevel(),
                        info.isFlat(),
                        info.isDebug(),
                        seam.centre,
                        seam.generation));
    }

    private static void discard(ServerPlayer player, ResourceKey<Level> lower) {
        if (ServerPlayNetworking.canSend(player, SeamDiscardPayload.TYPE)) {
            ServerPlayNetworking.send(player, new SeamDiscardPayload(lower));
        }
    }

    /** Sends the unsent chunks of the area nearest first, until the tick's estimated bytes reach the budget: at least one if any is left. */
    private static void stream(ServerPlayer player, ServerLevel below, RiderSeam seam) {
        int budget = LayerTuning.DEFAULT.seamBytesPerTick();
        int bytes = 0;
        for (ChunkPos offset : AREA) {
            ChunkPos pos = new ChunkPos(seam.centre.x() + offset.x(), seam.centre.z() + offset.z());
            long packed = pos.pack();
            if (seam.sent.contains(packed) || seam.oversize.contains(packed)) {
                continue;
            }
            SeamChunkPayload payload = build(below, pos);
            if (payload == null) {
                continue;
            }
            int size = estimatedBytes(payload.chunk());
            if (!fitsPayload(size)) {
                DeepCharter.LOGGER.warn(
                        "The chunk {} of {} is estimated at {} bytes, over the {} a payload may take less a margin: it is not sent ahead",
                        pos,
                        below.dimension().identifier(),
                        size,
                        MAX_PAYLOAD_SIZE);
                seam.oversize.add(packed);
                continue;
            }
            ServerPlayNetworking.send(player, payload);
            seam.sent.add(packed);
            seam.bytes += size;
            bytes += size;
            if (bytes >= budget) {
                return;
            }
        }
    }

    /** The payload of a chunk, built once a tick and shared by the riders that need it; null for a chunk that is not loaded or not lit yet. */
    private static @Nullable SeamChunkPayload build(ServerLevel below, ChunkPos pos) {
        Chunk key = new Chunk(below.dimension(), pos.pack());
        if (BUILT.containsKey(key)) {
            return BUILT.get(key);
        }
        LevelChunk chunk = below.getChunkSource().chunkMap.getChunkToSend(pos.pack());
        SeamChunkPayload payload = chunk == null
                ? null
                : new SeamChunkPayload(
                        below.dimension(),
                        new ClientboundLevelChunkWithLightPacket(chunk, below.getLightEngine(), null, null));
        BUILT.put(key, payload);
        return payload;
    }
}
