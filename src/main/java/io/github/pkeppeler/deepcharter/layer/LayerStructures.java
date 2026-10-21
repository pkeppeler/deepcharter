package io.github.pkeppeler.deepcharter.layer;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.colony.Colony;
import io.github.pkeppeler.deepcharter.colony.ColonyAnchor;
import io.github.pkeppeler.deepcharter.layer.LayerChain.Band;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
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
    private static final AtomicBoolean LOGGED_NO_COLONY = new AtomicBoolean();

    private LayerStructures() {}

    public static void init() {
        ServerChunkEvents.CHUNK_LOAD.register((level, chunk, newlyGenerated) -> {
            if (newlyGenerated) {
                draw(level, chunk);
            }
        });
    }

    private static void draw(ServerLevel level, LevelChunk chunk) {
        // A dimension type given inline by a data pack has no key: it is no layer. A callback never throws.
        if (level.dimensionTypeRegistration().unwrapKey().isEmpty()) {
            return;
        }
        Optional<Band> band = LayerChain.layerAt(level, level.getMinY());
        if (band.isEmpty()) {
            return;
        }
        int layer = band.get().layer();
        Optional<BlockPos> conduit = Colony.anchor(level.getServer(), ColonyAnchor.CONDUIT);
        if (conduit.isEmpty()) {
            if (LOGGED_NO_COLONY.compareAndSet(false, true)) {
                DeepCharter.LOGGER.warn(
                        "A chunk of layer {} generated before the colony was built, so it gets no structures", layer);
            }
            return;
        }
        List<StructureSite> sites =
                sitesIn(level.getSeed(), layer, band.get().minY(), band.get().height(), chunk.getPos(), conduit.get());
        try {
            for (StructureSite site : sites) {
                StructurePlan plan = new StructurePlan(site, level, chunk, band.get());
                plan.seal();
                site.kind().draw(plan, site.height());
                if (site.kind() == StructureKind.WRECK) {
                    boolean famous =
                            prospector(level.getServer()).filter(site::equals).isPresent();
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
                        layer,
                        e);
            }
        } finally {
            // A site that was drawn before a failure is held too: the chunk may be sent at once, before its light is
            // computed.
            if (!sites.isEmpty()) {
                LightSync.holdSendUntilLit(level, chunk.getPos());
            }
        }
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
     * The wreck site that is PROSPECTOR-0002's: the one nearest the Conduit in layer 2, with a lamp burning and Note N10
     * ({@link ProspectorWrecks}). Empty before the colony is built or when its data is unreadable.
     */
    public static Optional<StructureSite> prospector(MinecraftServer server) {
        StructureKind kind = StructureKind.WRECK;
        ServerLevel level = server.getLevel(LayerChain.dimension(kind.layer()));
        if (level == null) {
            throw new IllegalStateException(
                    "Layer " + kind.layer() + " is not loaded, so the Prospector's site is not known");
        }
        return Colony.anchor(server, ColonyAnchor.CONDUIT)
                .map(conduit -> nearest(level.getSeed(), kind, level.getMinY(), level.getHeight(), conduit, conduit));
    }
}
