package io.github.pkeppeler.deepcharter.test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CandleBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import io.github.pkeppeler.deepcharter.colony.Colony;
import io.github.pkeppeler.deepcharter.colony.ColonyAnchor;
import io.github.pkeppeler.deepcharter.colony.ColonySite;
import io.github.pkeppeler.deepcharter.handbook.HandbookRegistry;
import io.github.pkeppeler.deepcharter.handbook.NoteBlock;
import io.github.pkeppeler.deepcharter.layer.LayerChain;
import io.github.pkeppeler.deepcharter.layer.LayerStructures;
import io.github.pkeppeler.deepcharter.layer.StructureKind;
import io.github.pkeppeler.deepcharter.layer.StructureSite;
import io.github.pkeppeler.deepcharter.layer.Zones;

/**
 * Server GameTests for #79: layer structures stand in their zones, one site of each kind in every 384-block square, from
 * the world seed alone, and the colony holds Notes N01 to N04. The generation test draws real chunks of both layers.
 */
public class LayerStructuresTest {
	private static final int SPACING = 384;
	/** A spacing cell that is far from the colony's Conduit, so no structure shares its blocks. */
	private static final int CELL = 3;
	private static final int PAD_REACH = 40;
	private static final int PAD_HEIGHT = 8;
	/** How near a colony Note is to the anchor of its building, in blocks on the map. */
	private static final double NEAR_ANCHOR = 6;
	private static final int PROSPECTOR_CELLS = 6;

	/** What a generated site holds, as block counts inside its bounds. */
	private static Map<Block, Integer> signature(StructureKind kind, int height) {
		return switch (kind) {
			case TOPSOIL_SHAFT, BENCHES_SHAFT, DEEP_SHAFT -> Map.of(Blocks.LADDER, height, Blocks.CANDLE, 1, HandbookRegistry.NOTE, 1);
			case GALLERY -> Map.of(Blocks.CONCRETE.black(), 6, Blocks.RAW_IRON_BLOCK, 3, HandbookRegistry.NOTE, 1);
			case PUNCH_CLOCK -> Map.of(Blocks.BOOKSHELF, 104, Blocks.SEA_LANTERN, 1, Blocks.IRON_BLOCK, 2, HandbookRegistry.NOTE, 1);
			case RAILS -> Map.of(Blocks.OAK_PLANKS, 45, Blocks.OAK_LOG, 54);
			case WRECK -> Map.of(Blocks.BLACKSTONE, 25, Blocks.DEEPSLATE_TILES, 144, Blocks.RAIL, 4);
		};
	}

	/** The Note each kind holds; the numbers are permanent (ADR 0020). The rail line and the wreck site hold none. */
	private static final Map<StructureKind, Integer> NOTES = Map.of(
			StructureKind.TOPSOIL_SHAFT, 5,
			StructureKind.BENCHES_SHAFT, 6,
			StructureKind.DEEP_SHAFT, 7,
			StructureKind.GALLERY, 8,
			StructureKind.PUNCH_CLOCK, 9);

	@GameTest
	public void zoneSpansPartitionTheLayer(GameTestHelper helper) {
		for (int[] layer : new int[][] {{0, 256}, {-64, 384}, {0, 100}}) {
			int minY = layer[0];
			int height = layer[1];
			int covered = 0;
			for (int index = 0; index < Zones.COUNT; index++) {
				Zones.Span span = Zones.span(minY, height, index);
				covered += span.size();
				for (int y = span.low(); y <= span.high(); y++) {
					if (Zones.index(minY, height, y) != index) {
						throw failure(helper, "y=%d is in the span of zone %d but Zones.index says %d (minY %d, height %d)",
								y, index, Zones.index(minY, height, y), minY, height);
					}
				}
			}
			if (covered != height) {
				throw failure(helper, "the three zone spans cover %d blocks of %d (minY %d)", covered, height, minY);
			}
		}
		helper.succeed();
	}

	@GameTest
	public void everyKindHasOneSiteInEachCellInsideItsCellAndZone(GameTestHelper helper) {
		ServerLevel[] layers = {level(helper, 1), level(helper, 2)};
		for (ServerLevel level : layers) {
			int layer = LayerChain.layerOf(level.dimensionTypeRegistration().unwrapKey().orElseThrow().identifier()).getAsInt();
			for (StructureKind kind : StructureKind.inLayer(layer)) {
				for (int cellX = -2; cellX <= 1; cellX++) {
					for (int cellZ = -2; cellZ <= 1; cellZ++) {
						StructureSite site = StructureSite.in(level.getSeed(), kind, level.getMinY(), level.getHeight(), cellX, cellZ);
						BoundingBox box = site.bounds();
						if (box.minX() < cellX * SPACING || box.maxX() >= (cellX + 1) * SPACING
								|| box.minZ() < cellZ * SPACING || box.maxZ() >= (cellZ + 1) * SPACING) {
							throw failure(helper, "%s in cell (%d, %d) reaches out of it: %s", kind, cellX, cellZ, box);
						}
						for (int y = box.minY(); y <= box.maxY(); y++) {
							if (Zones.index(level.getMinY(), level.getHeight(), y) != kind.zone()) {
								throw failure(helper, "%s in cell (%d, %d) at y=%d is in zone %d, not %d",
										kind, cellX, cellZ, y, Zones.index(level.getMinY(), level.getHeight(), y), kind.zone());
							}
						}
					}
				}
			}
		}
		helper.succeed();
	}

	@GameTest
	public void sitesFollowTheSeedAndNothingElse(GameTestHelper helper) {
		ServerLevel level = level(helper, 2);
		int differing = 0;
		for (StructureKind kind : StructureKind.inLayer(2)) {
			for (int cell = 0; cell < 8; cell++) {
				StructureSite one = StructureSite.in(11L, kind, level.getMinY(), level.getHeight(), cell, -cell);
				if (!one.equals(StructureSite.in(11L, kind, level.getMinY(), level.getHeight(), cell, -cell))) {
					throw failure(helper, "%s in cell %d is not the same when asked twice for the same seed", kind, cell);
				}
				if (!one.equals(StructureSite.in(12L, kind, level.getMinY(), level.getHeight(), cell, -cell))) {
					differing++;
				}
			}
		}
		// Of 32 sites, a second seed that moved none of them would be a seed that is not used.
		if (differing < 30) {
			throw failure(helper, "only %d of 32 sites moved with the seed", differing);
		}
		helper.succeed();
	}

	@GameTest(maxTicks = 600)
	public void eachStructureGeneratesInItsZone(GameTestHelper helper) {
		List<StructureSite> sites = new ArrayList<>();
		for (int layer = 1; layer <= 2; layer++) {
			ServerLevel level = level(helper, layer);
			for (StructureKind kind : StructureKind.inLayer(layer)) {
				StructureSite site = StructureSite.in(level.getSeed(), kind, level.getMinY(), level.getHeight(), CELL, CELL);
				sites.add(site);
				BoundingBox box = site.bounds();
				for (int chunkX = box.minX() >> 4; chunkX <= box.maxX() >> 4; chunkX++) {
					for (int chunkZ = box.minZ() >> 4; chunkZ <= box.maxZ() >> 4; chunkZ++) {
						level.getChunk(chunkX, chunkZ, ChunkStatus.FULL);
					}
				}
			}
		}
		helper.succeedWhen(() -> {
			for (StructureSite site : sites) {
				ServerLevel level = level(helper, site.kind().layer());
				Map<Block, Integer> found = new HashMap<>();
				Map<Integer, BlockPos> notes = new HashMap<>();
				BoundingBox box = site.bounds();
				for (BlockPos pos : BlockPos.betweenClosed(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ())) {
					BlockState state = level.getBlockState(pos);
					found.merge(state.getBlock(), 1, Integer::sum);
					if (state.is(HandbookRegistry.NOTE)) {
						notes.put(state.getValue(NoteBlock.NOTE), pos.immutable());
					}
				}
				for (Map.Entry<Block, Integer> expected : signature(site.kind(), site.height()).entrySet()) {
					int count = found.getOrDefault(expected.getKey(), 0);
					if (count != expected.getValue()) {
						throw failure(helper, "%s at %s holds %d of %s, expected %d", site.kind(), site.origin().toShortString(),
								count, expected.getKey(), expected.getValue());
					}
				}
				if (NOTES.containsKey(site.kind())) {
					int number = NOTES.get(site.kind());
					if (!notes.keySet().equals(Set.of(number))) {
						throw failure(helper, "%s at %s holds Notes %s, expected only N%02d", site.kind(), site.origin().toShortString(), notes.keySet(), number);
					}
					int zone = Zones.index(level.getMinY(), level.getHeight(), notes.get(number).getY());
					if (zone != site.kind().zone()) {
						throw failure(helper, "N%02d of %s is in zone %d, not %d", number, site.kind(), zone, site.kind().zone());
					}
				} else if (!notes.isEmpty()) {
					throw failure(helper, "%s at %s holds Notes %s but should hold none", site.kind(), site.origin().toShortString(), notes.keySet());
				}
				if (NOTES.containsKey(site.kind()) && site.kind().layer() == 1) {
					BlockPos note = notes.values().iterator().next();
					// The candle is one block across the structure from the Note, which is south when the structure runs east to west.
					BlockState candle = level.getBlockState(site.alongZ() ? note.east() : note.south());
					if (!candle.is(Blocks.CANDLE) || !candle.getValue(CandleBlock.LIT)) {
						throw failure(helper, "the niche of %s has no lit candle beside its Note: %s", site.kind(), candle);
					}
				}
			}
		});
	}

	@GameTest
	public void theProspectorsSiteIsTheWreckSiteNearestTheConduit(GameTestHelper helper) {
		MinecraftServer server = server(helper);
		BlockPos conduit = Colony.anchor(server, ColonyAnchor.CONDUIT).orElseThrow(() -> failure(helper, "the colony was not built"));
		ServerLevel level = level(helper, 2);
		StructureSite prospector = LayerStructures.prospector(server).orElseThrow(() -> failure(helper, "no Prospector site"));
		StructureSite best = null;
		double bestDistance = Double.MAX_VALUE;
		int centreX = Math.floorDiv(conduit.getX(), SPACING);
		int centreZ = Math.floorDiv(conduit.getZ(), SPACING);
		for (int cellX = centreX - PROSPECTOR_CELLS; cellX <= centreX + PROSPECTOR_CELLS; cellX++) {
			for (int cellZ = centreZ - PROSPECTOR_CELLS; cellZ <= centreZ + PROSPECTOR_CELLS; cellZ++) {
				StructureSite site = StructureSite.in(level.getSeed(), StructureKind.WRECK, level.getMinY(), level.getHeight(), cellX, cellZ);
				double distance = Math.hypot(site.origin().getX() - conduit.getX(), site.origin().getZ() - conduit.getZ());
				if (distance < bestDistance) {
					best = site;
					bestDistance = distance;
				}
			}
		}
		if (!prospector.equals(best)) {
			throw failure(helper, "the Prospector's site is %s but the nearest of %d cells is %s", prospector, 2 * PROSPECTOR_CELLS + 1, best);
		}
		Optional<Zones.Zone> zone = Zones.of(level, prospector.origin().getY());
		if (zone.isEmpty() || !zone.get().id().getPath().equals("prospectors_run")) {
			throw failure(helper, "the Prospector's site is in %s, not Prospector's Run", zone);
		}
		helper.succeed();
	}

	@GameTest(maxTicks = 600)
	public void theColonyHoldsNotesOneToFourAtTheirBuildings(GameTestHelper helper) {
		MinecraftServer server = server(helper);
		ColonySite.Placed colony = Colony.placed(server).orElseThrow(() -> failure(helper, "the colony was not built"));
		ServerLevel overworld = server.overworld();
		BlockPos centre = colony.center();
		for (int x = centre.getX() - PAD_REACH; x <= centre.getX() + PAD_REACH; x += 16) {
			for (int z = centre.getZ() - PAD_REACH; z <= centre.getZ() + PAD_REACH; z += 16) {
				overworld.getChunk(x >> 4, z >> 4, ChunkStatus.FULL);
			}
		}
		Map<Integer, BlockPos> notes = new HashMap<>();
		for (BlockPos pos : BlockPos.betweenClosed(centre.getX() - PAD_REACH, centre.getY(), centre.getZ() - PAD_REACH,
				centre.getX() + PAD_REACH, centre.getY() + PAD_HEIGHT, centre.getZ() + PAD_REACH)) {
			BlockState state = overworld.getBlockState(pos);
			if (state.is(HandbookRegistry.NOTE) && notes.put(state.getValue(NoteBlock.NOTE), pos.immutable()) != null) {
				throw failure(helper, "N%02d stands twice in the colony", state.getValue(NoteBlock.NOTE));
			}
		}
		if (!notes.keySet().equals(Set.of(1, 2, 3, 4))) {
			throw failure(helper, "the colony holds Notes %s, expected N01 to N04", notes.keySet());
		}
		Map<Integer, ColonyAnchor> buildings = Map.of(1, ColonyAnchor.PERSONNEL_OFFICE, 2, ColonyAnchor.PAY_OFFICE,
				3, ColonyAnchor.CHAPEL_CANDLE, 4, ColonyAnchor.CONTINUITY_OFFICE);
		buildings.forEach((number, anchor) -> {
			BlockPos at = colony.anchors().get(anchor);
			double distance = Math.hypot(notes.get(number).getX() - at.getX(), notes.get(number).getZ() - at.getZ());
			if (distance > NEAR_ANCHOR) {
				throw failure(helper, "N%02d is %.1f blocks from the %s, expected at most %.0f", number, distance, anchor, NEAR_ANCHOR);
			}
		});
		helper.succeed();
	}

	private static MinecraftServer server(GameTestHelper helper) {
		return helper.getLevel().getServer();
	}

	private static ServerLevel level(GameTestHelper helper, int layer) {
		ServerLevel level = server(helper).getLevel(LayerChain.dimension(layer));
		if (level == null) {
			throw failure(helper, "dimension %s did not load", LayerChain.dimension(layer));
		}
		return level;
	}

	// assertionException(String, Object...) leaves the placeholders unfilled in the report.
	private static RuntimeException failure(GameTestHelper helper, String format, Object... args) {
		return helper.assertionException(Component.literal(String.format(format, args)));
	}
}
