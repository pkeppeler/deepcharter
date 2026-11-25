package io.github.pkeppeler.deepcharter.layer;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.layer.LayerChain.Band;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
 * {@link LayerTuning#seamRefreshTicks()} ticks and moved to the new chunk, with the old centre's ticket removed, once the vehicle is {@link LayerTuning#seamRecentreChunks()} chunks from the centre: one area per vehicle. Leaving the zone, changing
 * level, being removed or disconnecting ends the approach; the ticket is not removed but lapses on its timeout.
 *
 * <p>A rise through a level's ceiling is streamed the same way into the level above (#1316): the same ticket on the
 * chunk under the vehicle, in the zone of {@link #shouldStream} measured from the ceiling, with the closing speed read
 * as the rise. Only the ticket is taken: the rise still lands by {@link BreachService}'s teleport and fade, since no
 * rider is sent the area and no swap is prepared going up.
 *
 * <p>The state is on the server thread only, and holds no level, entity or player: a root's UUID, its riders' UUIDs, level keys and a chunk.
 */
public final class SeamStreaming {
    /**
     * One vehicle's approach to the floor of a level with a level below it, or to the ceiling of one with a level above
     * it, keyed by the root vehicle (the player when on foot), listing every player it carries. {@code lower} is the
     * level the arrival area is in: the one below for {@link #approachOf}, the one above for {@link #ascentOf}.
     * {@code descentPerTick} is the vehicle's closing speed on the seam over the last
     * {@link LayerTuning#seamSpeedWindow()} ticks, in blocks a tick, as of the last tick it was read (0 when it sits or
     * moves away): what the approach hands the swap. {@code ready} is whether the arrival area's chunks have loaded.
     */
    public record Approach(
            UUID root,
            List<UUID> riders,
            ResourceKey<Level> upper,
            ResourceKey<Level> lower,
            ChunkPos centre,
            long startedTick,
            double descentPerTick,
            boolean ready) {}

    /** Which seam of its level a vehicle nears: the floor below it, or the ceiling above it. */
    enum Direction {
        DOWN,
        UP
    }

    /** What a root vehicle holds open under or over it: the ticket's level and chunk, and when it was last taken. */
    private record Streaming(
            ResourceKey<Level> target,
            ChunkPos centre,
            long startedTick,
            long refreshedTick,
            double closingPerTick,
            CompletableFuture<Void> loaded,
            List<UUID> riders) {}

    /** A root vehicle's track in one direction: a vehicle near both its floor and its ceiling keeps two. */
    private record Key(UUID root, Direction direction) {}

    /**
     * A root vehicle's recent distances to the seam it nears in one level, and the area it streams. The distance is its
     * height over the floor going down and under the ceiling going up, so {@link #descentPerTick} reads the closing
     * speed for both.
     */
    private static final class Track {
        private final ResourceKey<Level> upper;
        private final Direction direction;
        private final Deque<Double> heights = new ArrayDeque<>();
        private @Nullable Streaming streaming;
        private boolean seen;

        Track(ResourceKey<Level> upper, Direction direction) {
            this.upper = upper;
            this.direction = direction;
        }
    }

    /** The tracks by root vehicle UUID and direction. Server thread only. */
    private static final Map<Key, Track> TRACKS = new HashMap<>();

    /** Level keys already warned about as missing, so the warning is once per key and not once per tick. Server thread only. */
    private static final Set<ResourceKey<Level>> MISSING_WARNED = new HashSet<>();

    private SeamStreaming() {}

    public static void init() {
        ServerTickEvents.END_LEVEL_TICK.register(SeamStreaming::tick);
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            TRACKS.clear();
            MISSING_WARNED.clear();
        });
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

    /**
     * Blocks a tick that a vehicle has fallen over {@code heights}, its heights on the last ticks, oldest first: the drop from the first
     * to the last over the ticks between them, 0 for a vehicle that sits or climbs and for fewer than two heights.
     */
    public static double descentPerTick(Collection<Double> heights) {
        if (heights.size() < 2) {
            return 0;
        }
        double first = heights.iterator().next();
        double last = first;
        for (double height : heights) {
            last = height;
        }
        return Math.max(0, (first - last) / (heights.size() - 1));
    }

    /** The approach of the root vehicle with this UUID, if it is streaming a level below now. */
    public static Optional<Approach> approachOf(UUID root) {
        return approachOf(root, Direction.DOWN);
    }

    /**
     * The approach of the root vehicle with this UUID to the level above, if it is streaming one now, with that level
     * as its {@code lower}: a ticket only, never sent to a rider.
     */
    public static Optional<Approach> ascentOf(UUID root) {
        return approachOf(root, Direction.UP);
    }

    private static Optional<Approach> approachOf(UUID root, Direction direction) {
        Track track = TRACKS.get(new Key(root, direction));
        return track == null ? Optional.empty() : approachOf(root, track);
    }

    /**
     * Every descent approach under way now: one per root vehicle that is streaming a level below. A rise is left out:
     * it holds a ticket only, and nothing is sent to its riders.
     */
    public static List<Approach> approaches() {
        List<Approach> approaches = new ArrayList<>();
        TRACKS.forEach((key, track) -> {
            if (key.direction() == Direction.DOWN) {
                approachOf(key.root(), track).ifPresent(approaches::add);
            }
        });
        return approaches;
    }

    private static Optional<Approach> approachOf(UUID root, Track track) {
        Streaming streaming = track.streaming;
        if (streaming == null) {
            return Optional.empty();
        }
        return Optional.of(new Approach(
                root,
                streaming.riders(),
                track.upper,
                streaming.target(),
                streaming.centre(),
                streaming.startedTick(),
                streaming.closingPerTick(),
                streaming.loaded().isDone() && !streaming.loaded().isCompletedExceptionally()));
    }

    /**
     * Logs a crossing in {@code direction} that finds less than the whole arrival area loaded, which the
     * crossing then waits for on the server thread: how many of its chunks are loaded and how long the approach had
     * run. Silent when the area is ready.
     */
    static void reportCrossing(Entity root, ServerLevel to, ChunkPos arrival, Direction direction) {
        Optional<Approach> approach = direction == Direction.UP ? ascentOf(root.getUUID()) : approachOf(root.getUUID());
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
            for (Direction direction : Direction.values()) {
                Optional<ServerLevel> target = levelBeyond(level, direction);
                if (target.isPresent()) {
                    follow(level, target.get(), direction);
                }
            }
        }
        TRACKS.values().removeIf(track -> track.upper.equals(key) && !track.seen);
    }

    /**
     * The level under {@code level} in the chain, or over it going {@link Direction#UP}: empty for the surface going
     * up, the last level going down, a level outside the chain and a registered layer whose level is not loaded. That
     * last is warned about once per level key, and never thrown, since this runs every tick.
     */
    private static Optional<ServerLevel> levelBeyond(ServerLevel level, Direction direction) {
        Optional<Band> band = LayerChain.layerAt(level, level.getMinY());
        if (band.isEmpty()
                || (direction == Direction.DOWN && band.get().layer() >= LayerChain.count(level.registryAccess()))) {
            return Optional.empty();
        }
        ResourceKey<Level> key = LayerChain.dimension(band.get().layer() + (direction == Direction.UP ? -1 : 1));
        ServerLevel beyond = level.getServer().getLevel(key);
        if (beyond == null && MISSING_WARNED.add(key)) {
            DeepCharter.LOGGER.warn(
                    "No arrival area is streamed from {}: the level {} it, {}, is registered but not loaded",
                    level.dimension().identifier(),
                    direction == Direction.UP ? "over" : "under",
                    key.identifier());
        }
        return Optional.ofNullable(beyond);
    }

    private static void follow(ServerLevel level, ServerLevel target, Direction direction) {
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
            Key key = new Key(id, direction);
            Track track = TRACKS.get(key);
            if (track == null || !track.upper.equals(level.dimension())) {
                track = new Track(level.dimension(), direction);
                TRACKS.put(key, track);
            }
            track.seen = true;
            stream(level, target, root, track, List.copyOf(riders.get(id)));
        });
    }

    private static void stream(ServerLevel level, ServerLevel target, Entity root, Track track, List<UUID> riders) {
        LayerTuning tuning = LayerTuning.DEFAULT;
        double y = root.getY();
        double toSeam = track.direction == Direction.UP ? level.getMaxY() - y : y - level.getMinY();
        track.heights.addLast(toSeam);
        while (track.heights.size() > tuning.seamSpeedWindow() + 1) {
            track.heights.removeFirst();
        }
        double closing = descentPerTick(track.heights);
        if (!shouldStream(toSeam, closing, target.getHeight(), tuning)) {
            track.streaming = null;
            return;
        }
        long now = level.getServer().getTickCount();
        ChunkPos here = ChunkPos.containing(root.blockPosition());
        Streaming current = track.streaming;
        if (current == null || current.centre().getChessboardDistance(here) >= tuning.seamRecentreChunks()) {
            if (current != null) {
                target.getChunkSource()
                        .removeTicketWithRadius(LayerRegistry.SEAM_ARRIVAL, current.centre(), tuning.seamRadius());
            }
            CompletableFuture<Void> loaded = target.getChunkSource()
                    .addTicketAndLoadWithRadius(LayerRegistry.SEAM_ARRIVAL, here, tuning.seamRadius())
                    .thenAccept(chunk -> {})
                    .whenComplete((nothing, cause) -> {
                        if (cause != null) {
                            DeepCharter.LOGGER.warn(
                                    "The arrival area of {} around {} failed to load",
                                    target.dimension().identifier(),
                                    here,
                                    cause);
                        }
                    });
            track.streaming = new Streaming(
                    target.dimension(),
                    here,
                    current == null ? now : current.startedTick(),
                    now,
                    closing,
                    loaded,
                    riders);
        } else {
            boolean refresh = now - current.refreshedTick() >= tuning.seamRefreshTicks();
            if (refresh) {
                target.getChunkSource()
                        .addTicketWithRadius(LayerRegistry.SEAM_ARRIVAL, current.centre(), tuning.seamRadius());
            }
            track.streaming = new Streaming(
                    current.target(),
                    current.centre(),
                    current.startedTick(),
                    refresh ? now : current.refreshedTick(),
                    closing,
                    current.loaded(),
                    riders);
        }
    }
}
