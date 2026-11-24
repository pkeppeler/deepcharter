package io.github.pkeppeler.deepcharter.layer;

import io.github.pkeppeler.deepcharter.DeepCharter;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.CommonPlayerSpawnInfo;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
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

    /** Bytes the estimate counts for each light section of a chunk packet: a light array is 2048 bytes. */
    private static final int LIGHT_ARRAY_BYTES = 2048;

    /** What the server knows of a rider's seam: the area it was last sent, and the chunks of it it holds. */
    private static final class RiderSeam {
        final ResourceKey<Level> lower;
        ChunkPos centre;
        final LongSet sent = new LongOpenHashSet();
        /** Positions in the area that were sent and have since changed or may have: the crossing sends them again. */
        final LongSet stale = new LongOpenHashSet();
        /** Positions whose packet is over {@link #MAX_PAYLOAD_SIZE}: never sent, and not tried again while the area stays. */
        final LongSet oversize = new LongOpenHashSet();

        RiderSeam(ResourceKey<Level> lower, ChunkPos centre) {
            this.lower = lower;
            this.centre = centre;
        }
    }

    /** A chunk of one level, as a cache key. */
    private record Chunk(ResourceKey<Level> level, long pos) {}

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
        ServerTickEvents.END_SERVER_TICK.register(SeamSender::tick);
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> STATE.remove(handler.player.getUUID()));
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            STATE.clear();
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

    /** The bytes a chunk packet is estimated to take on the wire: its block data and heightmaps, and 2048 for each light array. */
    public static int estimatedBytes(ClientboundLevelChunkWithLightPacket packet) {
        int lightArrays = packet.lightData().skyUpdates().size()
                + packet.lightData().blockUpdates().size();
        return packet.chunkData().getReadBuffer().readableBytes() + LIGHT_ARRAY_BYTES * lightArrays;
    }

    private static void tick(MinecraftServer server) {
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
        seam.oversize.clear();
    }

    private static void prepare(ServerPlayer player, ServerLevel below, RiderSeam seam) {
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
                        seam.centre));
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
            if (size > MAX_PAYLOAD_SIZE) {
                DeepCharter.LOGGER.warn(
                        "The chunk {} of {} is estimated at {} bytes, over the {} a payload may take: it is not sent ahead",
                        pos,
                        below.dimension().identifier(),
                        size,
                        MAX_PAYLOAD_SIZE);
                seam.oversize.add(packed);
                continue;
            }
            ServerPlayNetworking.send(player, payload);
            seam.sent.add(packed);
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
