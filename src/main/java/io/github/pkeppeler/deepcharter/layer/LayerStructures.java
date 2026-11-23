package io.github.pkeppeler.deepcharter.layer;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.colony.Colony;
import io.github.pkeppeler.deepcharter.colony.ColonyAnchor;
import io.github.pkeppeler.deepcharter.colony.ColonyEvents;
import io.github.pkeppeler.deepcharter.layer.LayerChain.Band;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
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
    private static final AtomicBoolean LOGGED_UNREADABLE_COLONY = new AtomicBoolean();

    /**
     * The chunks that generated before the colony's pad was chosen, by level: held, and drawn once the pad is chosen. Each is a chunk of a
     * level that holds layers, so in the campaign world it is a chunk of the surface band and of every layer band. Server thread only.
     */
    private static final Map<ResourceKey<Level>, Set<ChunkPos>> WAITING = new HashMap<>();

    private LayerStructures() {}

    public static void init() {
        ServerChunkEvents.CHUNK_LOAD.register((level, chunk, newlyGenerated) -> {
            if (newlyGenerated) {
                draw(level, chunk);
            }
        });
        ColonyEvents.BEGUN.register((server, colony) -> drawWaiting(server));
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> WAITING.clear());
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
            drawSites(level, chunk, bands, conduit.get());
        } else if (Colony.readable(level.getServer()).isPresent()) {
            WAITING.computeIfAbsent(level.dimension(), key -> new HashSet<>()).add(chunk.getPos());
        } else if (LOGGED_UNREADABLE_COLONY.compareAndSet(false, true)) {
            DeepCharter.LOGGER.warn(
                    "A chunk of layer {} generated while the colony's saved data is unreadable, so it gets no structures",
                    bands.getFirst().layer());
        }
    }

    /** Draws the chunks that generated before the pad was chosen, now that it is; each once. */
    private static void drawWaiting(MinecraftServer server) {
        Map<ResourceKey<Level>, Set<ChunkPos>> waiting = new HashMap<>(WAITING);
        WAITING.clear();
        waiting.forEach((dimension, chunks) -> {
            ServerLevel level = server.getLevel(dimension);
            if (level == null) {
                return;
            }
            for (ChunkPos pos : chunks) {
                // A chunk that unloaded since was saved undrawn: it loads from disk, which is no draw of its own.
                draw(level, level.getChunk(pos.x(), pos.z()));
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
                                "Layer structures failed in chunk {} of layer {}; this and later chunks may lack theirs",
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
     * The layers a level holds: the one layer of a layer dimension, every layer of a campaign world (#743) that is inside its height, or
     * none for the surface and any other level. A level of the campaign generator cut to one band's rows (the cost test's band windows,
     * ADR 0086) holds only that band, and a site of another would be drawn outside the level.
     */
    private static List<Band> layerBands(ServerLevel level) {
        List<Band> campaign = LayerChain.campaignLayers(level);
        if (!campaign.isEmpty()) {
            return campaign.stream()
                    .filter(band ->
                            !level.isOutsideBuildHeight(band.minY()) && !level.isOutsideBuildHeight(band.maxY()))
                    .toList();
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
