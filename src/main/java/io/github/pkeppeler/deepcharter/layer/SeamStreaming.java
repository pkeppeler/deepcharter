package io.github.pkeppeler.deepcharter.layer;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.layer.LayerChain.Band;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.Nullable;

/**
 * The server's half of a seam (#1159, ADR 0089): the arrival area of the level below is loaded under a vehicle that nears a floor, ahead
 * of the crossing and on the worker threads, so {@link BreachService}'s crossing finds it ready.
 *
 * <p>Each tick, for every level of the chain that has a level below it, each root vehicle carrying a player is read: its height over the
 * floor, and its descent over the last {@link LayerTuning#seamSpeedWindow()} ticks. Inside the zone of {@link #shouldStream} it takes a
 * {@link LayerRegistry#SEAM_ARRIVAL} ticket of radius {@link LayerTuning#seamRadius()} on the chunk under it, refreshed every
 * {@link LayerTuning#seamRefreshTicks()} ticks and re-taken on the new chunk when the vehicle moves to another. Leaving the zone, changing
 * level, being removed or disconnecting ends the approach; the ticket is not removed but lapses on its timeout.
 *
 * <p>The state is on the server thread only, and holds no level, entity or player: a root's UUID, its riders' UUIDs, level keys and a chunk.
 */
public final class SeamStreaming {
    /**
     * One vehicle's approach to the floor of a level with a level below it, keyed by the root vehicle (the player when on foot), listing every
     * player it carries. {@code ready} is whether the arrival area's chunks have loaded.
     */
    public record Approach(
            UUID root,
            List<UUID> riders,
            ResourceKey<Level> upper,
            ResourceKey<Level> lower,
            ChunkPos centre,
            long startedTick,
            boolean ready) {}

    /** What a root vehicle is holding open under it: the ticket's chunk and when it was last taken. */
    private record Streaming(
            ResourceKey<Level> lower,
            ChunkPos centre,
            long startedTick,
            long refreshedTick,
            CompletableFuture<Void> loaded,
            List<UUID> riders) {}

    /** A root vehicle's recent heights in one level, and the area it streams. */
    private static final class Track {
        private final ResourceKey<Level> upper;
        private final Deque<Double> heights = new ArrayDeque<>();
        private @Nullable Streaming streaming;
        private boolean seen;

        Track(ResourceKey<Level> upper) {
            this.upper = upper;
        }
    }

    /** The tracks by root vehicle UUID. Server thread only. */
    private static final Map<UUID, Track> TRACKS = new HashMap<>();

    private SeamStreaming() {}

    public static void init() {
        ServerTickEvents.END_LEVEL_TICK.register(SeamStreaming::tick);
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> TRACKS.clear());
    }

    /** Blocks above a floor at which a vehicle that falls at the design speed starts to stream the {@code lowerHeight} rows below. */
    public static int lead(int lowerHeight, LayerTuning tuning) {
        return (int) Math.ceil(tuning.seamLeadPerRow() * lowerHeight);
    }

    /**
     * Whether a vehicle {@code depthAboveFloor} blocks over a floor and falling {@code descentPerTick} blocks a tick streams the level
     * below, which is {@code lowerHeight} rows tall. Inside the floor margin it always does. Further up it does when it is inside the
     * lead and its speed, as a share of the design speed, would bring it to the floor within the time the lead is sized for: a vehicle at
     * the design speed streams from the whole lead, one at half of it from half, and one standing or rising only inside the margin.
     */
    public static boolean shouldStream(
            double depthAboveFloor, double descentPerTick, int lowerHeight, LayerTuning tuning) {
        if (depthAboveFloor <= tuning.seamFloorMargin()) {
            return true;
        }
        int lead = lead(lowerHeight, tuning);
        if (depthAboveFloor > lead) {
            return false;
        }
        return depthAboveFloor <= Math.min(1.0, descentPerTick / tuning.seamDesignFallSpeed()) * lead;
    }

    /** The approach of the root vehicle with this UUID, if it is streaming a level below now. */
    public static Optional<Approach> approachOf(UUID root) {
        Track track = TRACKS.get(root);
        if (track == null || track.streaming == null) {
            return Optional.empty();
        }
        Streaming streaming = track.streaming;
        return Optional.of(new Approach(
                root,
                streaming.riders(),
                track.upper,
                streaming.lower(),
                streaming.centre(),
                streaming.startedTick(),
                streaming.loaded().isDone() && !streaming.loaded().isCompletedExceptionally()));
    }

    /**
     * Logs a crossing that finds less than the whole arrival area loaded, which the crossing then waits for on the server thread: how many
     * of its chunks are loaded and how long the approach had run. Silent when the area is ready.
     */
    static void reportCrossing(Entity root, ServerLevel to, ChunkPos arrival) {
        Optional<Approach> approach = approachOf(root.getUUID());
        ChunkPos centre = approach.map(Approach::centre).orElse(arrival);
        int loaded = loadedAround(to, centre);
        int total = areaChunks();
        if (loaded < total) {
            DeepCharter.LOGGER.warn(
                    "{} crossed into {} before its arrival area was loaded: {} of {} chunks around {}, {}",
                    root.getUUID(),
                    to.dimension().identifier(),
                    loaded,
                    total,
                    centre,
                    approach.map(found -> "approach under way for "
                                    + (to.getServer().getTickCount() - found.startedTick()) + " ticks")
                            .orElse("no approach"));
        }
    }

    private static int areaChunks() {
        int side = 2 * LayerTuning.DEFAULT.seamRadius() + 1;
        return side * side;
    }

    private static int loadedAround(ServerLevel level, ChunkPos centre) {
        int radius = LayerTuning.DEFAULT.seamRadius();
        int loaded = 0;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (level.getChunkSource().getChunkNow(centre.x() + dx, centre.z() + dz) != null) {
                    loaded++;
                }
            }
        }
        return loaded;
    }

    private static void tick(ServerLevel level) {
        ResourceKey<Level> key = level.dimension();
        if (level.players().isEmpty() && TRACKS.isEmpty()) {
            return;
        }
        TRACKS.values().stream().filter(track -> track.upper.equals(key)).forEach(track -> track.seen = false);
        if (!level.players().isEmpty() && !LayerChain.isCampaign(level)) {
            Optional<ServerLevel> below = levelBelow(level);
            if (below.isPresent()) {
                follow(level, below.get());
            }
        }
        TRACKS.values().removeIf(track -> track.upper.equals(key) && !track.seen);
    }

    /** The level under {@code level} in the chain, empty for the surface, the last level and a level outside the chain. */
    private static Optional<ServerLevel> levelBelow(ServerLevel level) {
        Optional<Band> band = LayerChain.layerAt(level, level.getMinY());
        if (band.isEmpty() || band.get().layer() >= LayerChain.count(level.registryAccess())) {
            return Optional.empty();
        }
        return Optional.of(LayerChain.levelOf(level.getServer(), band.get().layer() + 1));
    }

    private static void follow(ServerLevel level, ServerLevel below) {
        Map<UUID, Entity> roots = new LinkedHashMap<>();
        Map<UUID, List<UUID>> riders = new HashMap<>();
        for (ServerPlayer player : level.players()) {
            Entity root = player.getRootVehicle();
            if (!root.isRemoved()) {
                roots.putIfAbsent(root.getUUID(), root);
                riders.computeIfAbsent(root.getUUID(), id -> new ArrayList<>()).add(player.getUUID());
            }
        }
        roots.forEach((id, root) -> {
            Track track = TRACKS.get(id);
            if (track == null || !track.upper.equals(level.dimension())) {
                track = new Track(level.dimension());
                TRACKS.put(id, track);
            }
            track.seen = true;
            stream(level, below, root, track, List.copyOf(riders.get(id)));
        });
    }

    private static void stream(ServerLevel level, ServerLevel below, Entity root, Track track, List<UUID> riders) {
        LayerTuning tuning = LayerTuning.DEFAULT;
        double y = root.getY();
        track.heights.addLast(y);
        while (track.heights.size() > tuning.seamSpeedWindow() + 1) {
            track.heights.removeFirst();
        }
        double descent = Math.max(0, (track.heights.getFirst() - y) / Math.max(1, track.heights.size() - 1));
        if (!shouldStream(y - level.getMinY(), descent, below.getHeight(), tuning)) {
            track.streaming = null;
            return;
        }
        long now = level.getServer().getTickCount();
        ChunkPos centre = ChunkPos.containing(root.blockPosition());
        Streaming current = track.streaming;
        if (current == null || !current.centre().equals(centre)) {
            CompletableFuture<Void> loaded = below.getChunkSource()
                    .addTicketAndLoadWithRadius(LayerRegistry.SEAM_ARRIVAL, centre, tuning.seamRadius())
                    .thenAccept(chunk -> {});
            track.streaming = new Streaming(
                    below.dimension(), centre, current == null ? now : current.startedTick(), now, loaded, riders);
        } else if (now - current.refreshedTick() >= tuning.seamRefreshTicks()) {
            below.getChunkSource().addTicketWithRadius(LayerRegistry.SEAM_ARRIVAL, centre, tuning.seamRadius());
            track.streaming =
                    new Streaming(current.lower(), centre, current.startedTick(), now, current.loaded(), riders);
        } else {
            track.streaming = new Streaming(
                    current.lower(), centre, current.startedTick(), current.refreshedTick(), current.loaded(), riders);
        }
    }
}
