package io.github.pkeppeler.deepcharter.layer;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.colony.Colony;
import io.github.pkeppeler.deepcharter.colony.ColonyAnchor;
import io.github.pkeppeler.deepcharter.colony.ColonyEvents;
import io.github.pkeppeler.deepcharter.layer.LayerChain.Band;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/**
 * Layer structures ({@link StructureKind}), drawn when a fresh chunk of a layer loads and placed by {@link StructureSite#in}.
 * Where a site stands, what a chunk draws and what it leaves alone is in ADR 0025.
 */
public final class LayerStructures {
    /**
     * How many spacing cells out from a point {@link #nearest} looks. The site in the point's own cell is less than 1.5 spacings
     * away, and any site in a cell three out is more than 2 spacings away, so two out cannot miss the nearest.
     */
    private static final int NEAREST_CELLS = 2;

    private static final AtomicBoolean LOGGED_FAILURE = new AtomicBoolean();
    private static final AtomicBoolean LOGGED_PENDING_FAILURE = new AtomicBoolean();
    private static final AtomicBoolean LOGGED_UNREADABLE_COLONY = new AtomicBoolean();

    /** Set by a chunk load that finds drawable chunks held; drained at the end of the tick, not inside the load. Server thread only. */
    private static boolean drainQueued;

    private static boolean draining;

    private LayerStructures() {}

    public static void init() {
        ServerChunkEvents.CHUNK_LOAD.register((level, chunk, newlyGenerated) -> {
            if (newlyGenerated) {
                draw(level, chunk);
            }
            queueDrainIfDue(level.getServer());
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (drainQueued) {
                drainQueued = false;
                drawPending(server);
            }
        });
        ColonyEvents.BEGUN.register((server, colony) -> drawPending(server));
        // A chunk saved undrawn before the pad was chosen: the pad may be chosen already, in an earlier run.
        ServerLifecycleEvents.SERVER_STARTED.register(LayerStructures::drawPending);
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> drainQueued = false);
    }

    private static void draw(ServerLevel level, LevelChunk chunk) {
        // A dimension type given inline by a data pack has no key: it is no layer. A callback never throws.
        if (level.dimensionTypeRegistration().unwrapKey().isEmpty()) {
            return;
        }
        List<Band> bands = layerBands(level);
        if (bands.isEmpty()) {
            return;
        }
        // The pad is chosen before the build sets a block, and the build loads the pad's chunks to read the
        // ground. A chunk that waited for the finished colony would lose its sites for good, and in the
        // campaign world those chunks hold both layers.
        Optional<BlockPos> conduit = Colony.plannedAnchor(level.getServer(), ColonyAnchor.CONDUIT);
        if (conduit.isPresent()) {
            // Drawn now, so a chunk that was held and generated again (its first save never happened) is not
            // drawn twice.
            PendingStructures.readable(level.getServer())
                    .ifPresent(pending -> pending.remove(level.dimension(), chunk.getPos()));
            drawSites(level, chunk, bands, conduit.get());
        } else if (Colony.readable(level.getServer()).isPresent()) {
            PendingStructures.readable(level.getServer())
                    .ifPresent(pending -> pending.add(level.dimension(), chunk.getPos()));
        } else if (LOGGED_UNREADABLE_COLONY.compareAndSet(false, true)) {
            DeepCharter.LOGGER.warn(
                    "A chunk of layer {} generated while the colony's saved data is unreadable, so it gets no"
                            + " structures",
                    bands.getFirst().layer());
        }
    }

    /**
     * A chunk may be held while the pad is chosen: a test that swaps the colony's record parks chunks the real colony would have drawn,
     * and no event follows. Any chunk load finding some held asks for a drain at the end of the tick.
     */
    private static void queueDrainIfDue(MinecraftServer server) {
        if (drainQueued || draining) {
            return;
        }
        boolean held = PendingStructures.readable(server)
                .filter(pending -> !pending.isEmpty())
                .isPresent();
        if (held && Colony.plannedAnchor(server, ColonyAnchor.CONDUIT).isPresent()) {
            drainQueued = true;
        }
    }

    /**
     * Draws the chunks held before the pad was chosen ({@link PendingStructures}), each once, now that it is: on {@code BEGUN}, on
     * server start and after a chunk load that finds some held.
     *
     * <p>A chunk's load can itself draw it (it generated again): it is then no longer held, and is not drawn a second time. A chunk that
     * cannot be loaded stays held, as nothing was drawn; one that fails to draw is dropped, as a half-drawn chunk would draw twice.
     */
    public static void drawPending(MinecraftServer server) {
        if (draining || Colony.plannedAnchor(server, ColonyAnchor.CONDUIT).isEmpty()) {
            return;
        }
        draining = true;
        try {
            PendingStructures.readable(server).ifPresent(pending -> drawHeld(server, pending));
        } finally {
            draining = false;
        }
    }

    private static void drawHeld(MinecraftServer server, PendingStructures pending) {
        pending.snapshot().forEach((dimension, chunks) -> {
            ServerLevel level = server.getLevel(dimension);
            if (level == null) {
                return;
            }
            for (ChunkPos pos : chunks) {
                LevelChunk chunk;
                try {
                    // A chunk that unloaded since was saved undrawn: it loads from disk, which is no draw of its own.
                    chunk = level.getChunk(pos.x(), pos.z());
                } catch (RuntimeException e) {
                    if (LOGGED_PENDING_FAILURE.compareAndSet(false, true)) {
                        DeepCharter.LOGGER.error(
                                "A pending chunk {} of {} could not be loaded; it stays held",
                                pos,
                                dimension.identifier(),
                                e);
                    }
                    continue;
                }
                if (!pending.remove(dimension, pos)) {
                    continue;
                }
                try {
                    draw(level, chunk);
                } catch (RuntimeException e) {
                    DeepCharter.LOGGER.error(
                            "A pending chunk {} of {} could not be drawn", pos, dimension.identifier(), e);
                }
            }
        });
    }

    private static void drawSites(ServerLevel level, LevelChunk chunk, List<Band> bands, BlockPos conduit) {
        boolean held = false;
        try {
            for (Band band : bands) {
                List<StructureSite> sites =
                        sitesIn(level.getSeed(), band.layer(), band.minY(), band.height(), chunk.getPos(), conduit);
                // Set before the sites are drawn, so a site drawn before a failure is held too.
                held |= !sites.isEmpty();
                try {
                    for (StructureSite site : sites) {
                        StructurePlan plan = new StructurePlan(site, level, chunk, band);
                        plan.seal();
                        site.kind().draw(plan, site.height());
                        if (site.kind() == StructureKind.WRECK) {
                            boolean famous =
                                    prospector(level).filter(site::equals).isPresent();
                            if (famous) {
                                ProspectorWrecks.light(plan);
                            }
                            placeWreck(level, site, chunk, famous);
                        }
                    }
                } catch (RuntimeException e) {
                    if (LOGGED_FAILURE.compareAndSet(false, true)) {
                        DeepCharter.LOGGER.error(
                                "Layer structures failed in chunk {} of layer {}; this and later chunks may lack"
                                        + " theirs",
                                chunk.getPos(),
                                band.layer(),
                                e);
                    }
                }
            }
        } finally {
            // The chunk may be sent at once, before its light is computed.
            if (held) {
                LightSync.holdSendUntilLit(level, chunk.getPos());
            }
        }
    }

    /**
     * The layers a level holds: the one layer of a layer dimension, every layer of a campaign world (#743) inside its height
     * ({@link LayerChain#campaignLayersIn}), or none for the surface and any other level.
     */
    private static List<Band> layerBands(ServerLevel level) {
        if (!LayerChain.campaignLayers(level).isEmpty()) {
            return LayerChain.campaignLayersIn(level);
        }
        return LayerChain.layerAt(level, level.getMinY()).stream().toList();
    }

    /** A pod that cannot be made is logged and skips its own site only: it must not drop the blocks of the other sites in the chunk. */
    private static void placeWreck(ServerLevel level, StructureSite site, LevelChunk chunk, boolean famous) {
        try {
            ProspectorWrecks.place(level, site, chunk.getPos(), famous);
        } catch (RuntimeException e) {
            DeepCharter.LOGGER.error(
                    "The Prospector wreck at the site {} in chunk {} of {} failed; the site stays without a pod",
                    site.origin().toShortString(),
                    chunk.getPos(),
                    level.dimension().identifier(),
                    e);
        }
    }

    /** The sites of the layer whose structure, or the shell round it, touches {@code chunk}. */
    public static List<StructureSite> sitesIn(
            long worldSeed, int layer, int minY, int levelHeight, ChunkPos chunk, BlockPos conduit) {
        int spacing = LayerTuning.DEFAULT.structureSpacing();
        int cellX = Math.floorDiv(chunk.getMinBlockX(), spacing);
        int cellZ = Math.floorDiv(chunk.getMinBlockZ(), spacing);
        List<StructureSite> sites = new ArrayList<>();
        for (StructureKind kind : StructureKind.inLayer(layer)) {
            StructureSite site = StructureSite.in(worldSeed, kind, minY, levelHeight, cellX, cellZ, conduit);
            BoundingBox bounds = site.bounds();
            if (bounds.intersects(
                    chunk.getMinBlockX() - 1,
                    chunk.getMinBlockZ() - 1,
                    chunk.getMaxBlockX() + 1,
                    chunk.getMaxBlockZ() + 1)) {
                sites.add(site);
            }
        }
        return sites;
    }

    /** The site of {@code kind} nearest to {@code target} on the map (heights do not count), in the layer {@code kind} belongs to. */
    public static StructureSite nearest(
            long worldSeed, StructureKind kind, int minY, int levelHeight, BlockPos target, BlockPos conduit) {
        int spacing = LayerTuning.DEFAULT.structureSpacing();
        int targetCellX = Math.floorDiv(target.getX(), spacing);
        int targetCellZ = Math.floorDiv(target.getZ(), spacing);
        StructureSite nearest = null;
        double nearestDistance = Double.MAX_VALUE;
        for (int cellX = targetCellX - NEAREST_CELLS; cellX <= targetCellX + NEAREST_CELLS; cellX++) {
            for (int cellZ = targetCellZ - NEAREST_CELLS; cellZ <= targetCellZ + NEAREST_CELLS; cellZ++) {
                StructureSite site = StructureSite.in(worldSeed, kind, minY, levelHeight, cellX, cellZ, conduit);
                double distance = Math.pow(site.origin().getX() - target.getX(), 2)
                        + Math.pow(site.origin().getZ() - target.getZ(), 2);
                if (distance < nearestDistance) {
                    nearest = site;
                    nearestDistance = distance;
                }
            }
        }
        return Objects.requireNonNull(
                nearest, "no structure site found in the search window: the cell loop always visits at least one cell");
    }

    /**
     * The site of {@code kind} nearest {@code pos} on the map, when {@code pos} is inside the band of the layer {@code kind} belongs to
     * in {@code level}. Empty for a level that does not hold that layer, a Y outside its band, and before the colony's pad is chosen
     * or while its data is unreadable. Pure position arithmetic, so it asks no chunk and no entity.
     */
    public static Optional<StructureSite> siteNear(ServerLevel level, StructureKind kind, BlockPos pos) {
        return Colony.plannedAnchor(level.getServer(), ColonyAnchor.CONDUIT)
                .flatMap(conduit -> layerBands(level).stream()
                        .filter(band ->
                                band.layer() == kind.layer() && pos.getY() >= band.minY() && pos.getY() <= band.maxY())
                        .findFirst()
                        .map(band -> nearest(level.getSeed(), kind, band.minY(), band.height(), pos, conduit)));
    }

    /**
     * The wreck site that is PROSPECTOR-0002's: the one nearest the Conduit in the wreck's layer of {@code level}, with a lamp burning and
     * Note N10 ({@link ProspectorWrecks}). Empty before the colony's pad is chosen or when its data is unreadable, and for a level that does
     * not hold that layer. It is known from the choice of the pad, not the end of the build, so a bay in a chunk that loads during the
     * build is drawn with its lamp.
     */
    public static Optional<StructureSite> prospector(ServerLevel level) {
        StructureKind kind = StructureKind.WRECK;
        return Colony.plannedAnchor(level.getServer(), ColonyAnchor.CONDUIT)
                .flatMap(conduit -> layerBands(level).stream()
                        .filter(band -> band.layer() == kind.layer())
                        .findFirst()
                        .map(band -> nearest(level.getSeed(), kind, band.minY(), band.height(), conduit, conduit)));
    }
}
