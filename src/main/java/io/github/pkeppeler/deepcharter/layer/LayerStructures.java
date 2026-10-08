package io.github.pkeppeler.deepcharter.layer;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import io.github.pkeppeler.deepcharter.colony.Colony;
import io.github.pkeppeler.deepcharter.colony.ColonyAnchor;

/**
 * Layer structures: shafts with candle niches in layer 1; galleries, the punch clock hall, the rail line and wreck sites in
 * layer 2 ({@link StructureKind}). Each kind has one site in every square of {@link LayerTuning#structureSpacing()} blocks,
 * placed by {@link StructureSite#in} from the world seed alone.
 *
 * <p>It is not a worldgen structure. A chunk that has just generated draws the part of every site that touches it, when it
 * loads (ADR 0025), so a site needs no record and a chunk saved with its structure is never drawn on again.
 */
public final class LayerStructures {
	/**
	 * How many spacing cells out from a point {@link #nearest} looks. The site in the point's own cell is less than 1.5 spacings
	 * away, and any site in a cell three out is more than 2 spacings away, so two out cannot miss the nearest.
	 */
	private static final int NEAREST_CELLS = 2;

	private LayerStructures() {
	}

	public static void init() {
		ServerChunkEvents.CHUNK_LOAD.register((level, chunk, newlyGenerated) -> {
			if (newlyGenerated) {
				draw(level, chunk);
			}
		});
	}

	private static void draw(ServerLevel level, LevelChunk chunk) {
		// A dimension type given inline by a data pack has no key: it is no layer. A callback never throws.
		Optional<ResourceKey<DimensionType>> type = level.dimensionTypeRegistration().unwrapKey();
		if (type.isEmpty()) {
			return;
		}
		OptionalInt layer = LayerChain.layerOf(type.get().identifier());
		if (layer.isEmpty()) {
			return;
		}
		for (StructureSite site : sitesIn(level.getSeed(), layer.getAsInt(), level.getMinY(), level.getHeight(), chunk.getPos())) {
			site.kind().draw(new StructurePlan(site, level, chunk), site.height());
		}
	}

	/** The sites of the layer whose structure touches {@code chunk}. */
	public static List<StructureSite> sitesIn(long worldSeed, int layer, int minY, int levelHeight, ChunkPos chunk) {
		int spacing = LayerTuning.DEFAULT.structureSpacing();
		// A structure lies wholly in its cell and a cell is a whole number of chunks, so the chunk's own cell is the only one to ask.
		int cellX = Math.floorDiv(chunk.getMinBlockX(), spacing);
		int cellZ = Math.floorDiv(chunk.getMinBlockZ(), spacing);
		List<StructureSite> sites = new ArrayList<>();
		for (StructureKind kind : StructureKind.inLayer(layer)) {
			StructureSite site = StructureSite.in(worldSeed, kind, minY, levelHeight, cellX, cellZ);
			BoundingBox bounds = site.bounds();
			if (bounds.intersects(chunk.getMinBlockX(), chunk.getMinBlockZ(), chunk.getMaxBlockX(), chunk.getMaxBlockZ())) {
				sites.add(site);
			}
		}
		return sites;
	}

	/** The site of {@code kind} nearest to {@code target} on the map (heights do not count), in the layer {@code kind} belongs to. */
	public static StructureSite nearest(long worldSeed, StructureKind kind, int minY, int levelHeight, BlockPos target) {
		int spacing = LayerTuning.DEFAULT.structureSpacing();
		int targetCellX = Math.floorDiv(target.getX(), spacing);
		int targetCellZ = Math.floorDiv(target.getZ(), spacing);
		StructureSite nearest = null;
		double nearestDistance = Double.MAX_VALUE;
		for (int cellX = targetCellX - NEAREST_CELLS; cellX <= targetCellX + NEAREST_CELLS; cellX++) {
			for (int cellZ = targetCellZ - NEAREST_CELLS; cellZ <= targetCellZ + NEAREST_CELLS; cellZ++) {
				StructureSite site = StructureSite.in(worldSeed, kind, minY, levelHeight, cellX, cellZ);
				double distance = Math.pow(site.origin().getX() - target.getX(), 2) + Math.pow(site.origin().getZ() - target.getZ(), 2);
				if (distance < nearestDistance) {
					nearest = site;
					nearestDistance = distance;
				}
			}
		}
		return nearest;
	}

	/**
	 * The wreck site that is PROSPECTOR-0002's: the one nearest the Conduit in layer 2. Empty before the colony is built or
	 * when its data is unreadable. A later issue (#82) puts the pod there.
	 */
	public static Optional<StructureSite> prospector(MinecraftServer server) {
		StructureKind kind = StructureKind.WRECK;
		ServerLevel level = server.getLevel(LayerChain.dimension(kind.layer()));
		if (level == null) {
			throw new IllegalStateException("Layer " + kind.layer() + " is not loaded, so the Prospector's site is not known");
		}
		return Colony.anchor(server, ColonyAnchor.CONDUIT)
				.map(conduit -> nearest(level.getSeed(), kind, level.getMinY(), level.getHeight(), conduit));
	}
}
