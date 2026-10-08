package io.github.pkeppeler.deepcharter.test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CandleBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.ImposterProtoChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import io.github.pkeppeler.deepcharter.colony.Colony;
import io.github.pkeppeler.deepcharter.colony.ColonyAnchor;
import io.github.pkeppeler.deepcharter.colony.ColonyBlocks;
import io.github.pkeppeler.deepcharter.colony.ColonySite;
import io.github.pkeppeler.deepcharter.colony.ColonyTuning;
import io.github.pkeppeler.deepcharter.handbook.HandbookRegistry;
import io.github.pkeppeler.deepcharter.handbook.NoteBlock;
import io.github.pkeppeler.deepcharter.layer.LayerChain;
import io.github.pkeppeler.deepcharter.layer.LayerStructures;
import io.github.pkeppeler.deepcharter.layer.StructureKind;
import io.github.pkeppeler.deepcharter.layer.StructureSite;
import io.github.pkeppeler.deepcharter.layer.Zones;
import io.github.pkeppeler.deepcharter.ore.HazardBlocks;

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
	/** Far from the layer-2 tests, so nothing makes this chunk full first. */
	private static final int SEAL_CELL = 30;
	private static final int DISK_CELL = 6;
	private static final int CASING_CELL = 7;
	private static final int SEAM_CELL = 8;
	/** The rail line is 65 blocks long and 5 wide (ADR 0025). */
	private static final int RAILS_HALF_U = 32;
	private static final int RAILS_HALF_V = 2;
	/** A Conduit in no cell a test draws. */
	private static final BlockPos FAR_CONDUIT = new BlockPos(-100_000, 64, -100_000);

	/** What a generated site must hold at least: each block is checked for presence, not for a count the blueprint happens to give. */
	private static Map<Block, Integer> minimum(StructureKind kind, int height) {
		return switch (kind) {
			case TOPSOIL_SHAFT, BENCHES_SHAFT, DEEP_SHAFT -> Map.of(Blocks.LADDER, height, Blocks.CANDLE, 1, HandbookRegistry.NOTE, 1);
			case GALLERY -> Map.of(Blocks.CONCRETE.black(), 1, Blocks.RAW_IRON_BLOCK, 1, HandbookRegistry.NOTE, 1);
			case PUNCH_CLOCK -> Map.of(Blocks.BOOKSHELF, 1, Blocks.SEA_LANTERN, 1, Blocks.IRON_BLOCK, 1, HandbookRegistry.NOTE, 1);
			case RAILS -> Map.of(Blocks.OAK_PLANKS, 1, Blocks.OAK_LOG, 1, Blocks.RAIL, 1);
			case WRECK -> Map.of(Blocks.BLACKSTONE, 1, Blocks.DEEPSLATE_TILES, 1, Blocks.RAIL, 1);
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
		BlockPos conduit = conduit(helper);
		ServerLevel[] layers = {level(helper, 1), level(helper, 2)};
		for (ServerLevel level : layers) {
			int layer = LayerChain.layerOf(level.dimensionTypeRegistration().unwrapKey().orElseThrow().identifier()).getAsInt();
			for (StructureKind kind : StructureKind.inLayer(layer)) {
				for (int cellX = -2; cellX <= 1; cellX++) {
					for (int cellZ = -2; cellZ <= 1; cellZ++) {
						StructureSite site = StructureSite.in(level.getSeed(), kind, level.getMinY(), level.getHeight(), cellX, cellZ, conduit);
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
		BlockPos conduit = conduit(helper);
		ServerLevel level = level(helper, 2);
		int differing = 0;
		for (StructureKind kind : StructureKind.inLayer(2)) {
			for (int cell = 0; cell < 8; cell++) {
				StructureSite one = StructureSite.in(11L, kind, level.getMinY(), level.getHeight(), cell, -cell, conduit);
				if (!one.equals(StructureSite.in(11L, kind, level.getMinY(), level.getHeight(), cell, -cell, conduit))) {
					throw failure(helper, "%s in cell %d is not the same when asked twice for the same seed", kind, cell);
				}
				if (!one.equals(StructureSite.in(12L, kind, level.getMinY(), level.getHeight(), cell, -cell, conduit))) {
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
		BlockPos conduit = conduit(helper);
		List<StructureSite> sites = new ArrayList<>();
		for (int layer = 1; layer <= 2; layer++) {
			ServerLevel level = level(helper, layer);
			for (StructureKind kind : StructureKind.inLayer(layer)) {
				StructureSite site = StructureSite.in(level.getSeed(), kind, level.getMinY(), level.getHeight(), CELL, CELL, conduit);
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
				for (Map.Entry<Block, Integer> expected : minimum(site.kind(), site.height()).entrySet()) {
					int count = found.getOrDefault(expected.getKey(), 0);
					if (count < expected.getValue()) {
						throw failure(helper, "%s at %s holds %d of %s, expected at least %d", site.kind(), site.origin().toShortString(),
								count, expected.getKey(), expected.getValue());
					}
				}
				requireNoFluidOrGas(helper, level, site);
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
	public void sitesAvoidTheConduitCasing(GameTestHelper helper) {
		int radius = ColonyTuning.DEFAULT.conduitRadius();
		for (int layer = 1; layer <= 2; layer++) {
			ServerLevel level = level(helper, layer);
			for (StructureKind kind : StructureKind.inLayer(layer)) {
				StructureSite free = StructureSite.in(level.getSeed(), kind, level.getMinY(), level.getHeight(), CELL, CELL, FAR_CONDUIT);
				// A Conduit through the middle of the site it would have had.
				BlockPos blocking = free.origin();
				StructureSite moved = StructureSite.in(level.getSeed(), kind, level.getMinY(), level.getHeight(), CELL, CELL, blocking);
				BoundingBox box = moved.bounds();
				if (box.minX() <= blocking.getX() + radius && box.maxX() >= blocking.getX() - radius
						&& box.minZ() <= blocking.getZ() + radius && box.maxZ() >= blocking.getZ() - radius) {
					throw failure(helper, "%s still meets the casing round %s: %s", kind, blocking.toShortString(), box);
				}
				if (moved.equals(free)) {
					throw failure(helper, "%s did not move off a Conduit through its centre", kind);
				}
				if (!moved.equals(StructureSite.in(level.getSeed(), kind, level.getMinY(), level.getHeight(), CELL, CELL, blocking))) {
					throw failure(helper, "%s is not the same when asked twice for the same Conduit", kind);
				}
				if (box.minX() < CELL * SPACING || box.maxX() >= (CELL + 1) * SPACING || box.minZ() < CELL * SPACING || box.maxZ() >= (CELL + 1) * SPACING) {
					throw failure(helper, "%s moved out of its cell: %s", kind, box);
				}
			}
		}
		helper.succeed();
	}

	@GameTest(maxTicks = 600)
	public void aStructureSealsItsShellOfFluidAndGasBeforeTheCarve(GameTestHelper helper) {
		ServerLevel level = level(helper, 2);
		StructureSite site = StructureSite.in(level.getSeed(), StructureKind.RAILS, level.getMinY(), level.getHeight(), SEAL_CELL, SEAL_CELL, conduit(helper));
		// The chunk of the site's centre, stopped before it is full: the zone fill has run, and the structure is not drawn yet.
		ChunkPos chunkPos = ChunkPos.containing(site.origin());
		ChunkAccess proto = level.getChunk(chunkPos.x(), chunkPos.z(), ChunkStatus.FEATURES);
		if (proto instanceof ImposterProtoChunk) {
			throw failure(helper, "%s: chunk already FULL, another test loaded it; writes would be dropped (got %s)",
					chunkPos, proto.getClass().getSimpleName());
		}
		BlockState lava = Blocks.LAVA.defaultBlockState();
		BlockState gas = HazardBlocks.GAS_POCKET.defaultBlockState();
		// Lava in the wall beside the hollow (the shell, outside the bounds) and gas in its roof, within this chunk.
		int side = chunkPos.equals(ChunkPos.containing(at(site, 0, 1, 3))) ? 3 : -3;
		List<BlockPos> planted = new ArrayList<>();
		for (int u = -3; u <= 3; u++) {
			planted.add(at(site, u, 1, side));
			planted.add(at(site, u, site.height(), 0));
		}
		planted.removeIf(pos -> !chunkPos.equals(ChunkPos.containing(pos)));
		if (planted.size() < 2) {
			throw failure(helper, "only %d of the planted blocks lie in %s", planted.size(), chunkPos);
		}
		for (BlockPos pos : planted) {
			proto.setBlockState(pos, pos.getY() == site.origin().getY() + site.height() ? gas : lava, 0);
		}
		BlockPos control = columnClearOf(site, chunkPos);
		proto.setBlockState(control, lava, 0);
		level.getChunk(chunkPos.x(), chunkPos.z(), ChunkStatus.FULL);
		helper.succeedWhen(() -> {
			for (BlockPos pos : planted) {
				BlockState state = level.getBlockState(pos);
				if (!state.getFluidState().isEmpty() || state.is(HazardBlocks.GAS_POCKET)) {
					throw failure(helper, "%s at %s was not sealed", state, pos.toShortString());
				}
			}
			// The lava away from the structure is still there, so the planted blocks did reach the full chunk.
			if (!level.getBlockState(control).is(Blocks.LAVA)) {
				throw failure(helper, "the lava planted at %s clear of the structure is %s: the plant did not survive the chunk becoming full",
						control.toShortString(), level.getBlockState(control));
			}
			requireNoFluidOrGas(helper, level, site, chunkPos);
		});
	}

	@GameTest(maxTicks = 600)
	public void aChunkLoadedFromDiskIsNotDrawnInto(GameTestHelper helper) {
		ServerLevel level = level(helper, 2);
		StructureSite site = StructureSite.in(level.getSeed(), StructureKind.WRECK, level.getMinY(), level.getHeight(), DISK_CELL, DISK_CELL, conduit(helper));
		LevelChunk chunk = level.getChunk(site.origin().getX() >> 4, site.origin().getZ() >> 4);
		BlockPos floor = site.origin().below();
		helper.succeedWhen(() -> {
			if (level.getBlockState(floor).isAir()) {
				throw failure(helper, "the floor of the wreck bay at %s was not drawn", floor.toShortString());
			}
			// room-carver: tests the draw on a floor block of the wreck bay: one block, not a room
			level.setBlock(floor, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
			ServerChunkEvents.CHUNK_LOAD.invoker().onChunkLoad(level, chunk, false);
			if (!level.getBlockState(floor).isAir()) {
				throw failure(helper, "a chunk that was not newly generated was drawn into: %s at %s", level.getBlockState(floor), floor.toShortString());
			}
			// The same call for a new chunk draws it again, so the check above could have failed.
			ServerChunkEvents.CHUNK_LOAD.invoker().onChunkLoad(level, chunk, true);
			if (level.getBlockState(floor).isAir()) {
				throw failure(helper, "a newly generated chunk was not drawn: the floor at %s is air", floor.toShortString());
			}
		});
	}

	@GameTest(maxTicks = 600)
	public void theConduitCasingInsideASiteSurvivesTheDraw(GameTestHelper helper) {
		ServerLevel level = level(helper, 2);
		StructureSite site = StructureSite.in(level.getSeed(), StructureKind.WRECK, level.getMinY(), level.getHeight(), CASING_CELL, CASING_CELL, conduit(helper));
		LevelChunk chunk = level.getChunk(site.origin().getX() >> 4, site.origin().getZ() >> 4);
		helper.succeedWhen(() -> {
			// A casing block where the draw carves air.
			chunk.setBlockState(site.origin(), ColonyBlocks.CONDUIT.defaultBlockState(), 0);
			ServerChunkEvents.CHUNK_LOAD.invoker().onChunkLoad(level, chunk, true);
			BlockState after = level.getBlockState(site.origin());
			// room-carver: puts back the one air block the draw carved; not a room
			chunk.setBlockState(site.origin(), Blocks.AIR.defaultBlockState(), 0);
			if (!after.is(ColonyBlocks.CONDUIT)) {
				throw failure(helper, "the casing block at %s became %s", site.origin().toShortString(), after);
			}
		});
	}

	@GameTest(maxTicks = 1200)
	public void thePiecesOfAStructureAcrossChunkBordersMeet(GameTestHelper helper) {
		ServerLevel level = level(helper, 2);
		StructureKind kind = StructureKind.RAILS;
		StructureSite site = StructureSite.in(level.getSeed(), kind, level.getMinY(), level.getHeight(), SEAM_CELL, SEAM_CELL, conduit(helper));
		BoundingBox box = site.bounds();
		for (int chunkX = box.minX() >> 4; chunkX <= box.maxX() >> 4; chunkX++) {
			for (int chunkZ = box.minZ() >> 4; chunkZ <= box.maxZ() >> 4; chunkZ++) {
				level.getChunk(chunkX, chunkZ, ChunkStatus.FULL);
			}
		}
		helper.succeedWhen(() -> {
			int seams = 0;
			for (int u = -RAILS_HALF_U; u <= RAILS_HALF_U; u++) {
				if (u > -RAILS_HALF_U && !ChunkPos.containing(at(site, u, 0, 0)).equals(ChunkPos.containing(at(site, u - 1, 0, 0)))) {
					seams++;
				}
				for (int v = -RAILS_HALF_V; v <= RAILS_HALF_V; v++) {
					expect(helper, level, at(site, u, -1, v), Blocks.COARSE_DIRT);
				}
				for (int y = 1; y <= 2; y++) {
					expect(helper, level, at(site, u, y, 0), Blocks.AIR);
				}
				if (Math.floorMod(u, 8) == 0) {
					for (int v : new int[] {-2, 2}) {
						for (int y = 0; y <= 2; y++) {
							expect(helper, level, at(site, u, y, v), Blocks.OAK_LOG);
						}
					}
				}
			}
			// The line crosses a border, so that the check above covered a seam.
			if (seams < 2) {
				throw failure(helper, "the rail line at %s crosses no chunk border", site.origin().toShortString());
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
				StructureSite site = StructureSite.in(level.getSeed(), StructureKind.WRECK, level.getMinY(), level.getHeight(), cellX, cellZ, conduit);
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

	private static BlockPos conduit(GameTestHelper helper) {
		return Colony.anchor(server(helper), ColonyAnchor.CONDUIT).orElseThrow(() -> failure(helper, "the colony was not built"));
	}

	/** The world position of a block given in the structure's own axes (see {@code StructurePlan}). */
	private static BlockPos at(StructureSite site, int u, int y, int v) {
		return site.origin().offset(site.alongZ() ? v : u, y, site.alongZ() ? u : v);
	}

	/** A block of the chunk at the height of the site that is at least 3 blocks clear of the site's bounds. */
	private static BlockPos columnClearOf(StructureSite site, ChunkPos chunk) {
		BoundingBox box = site.bounds();
		for (int x = chunk.getMinBlockX(); x <= chunk.getMaxBlockX(); x++) {
			for (int z = chunk.getMinBlockZ(); z <= chunk.getMaxBlockZ(); z++) {
				if (x < box.minX() - 3 || x > box.maxX() + 3 || z < box.minZ() - 3 || z > box.maxZ() + 3) {
					return new BlockPos(x, site.origin().getY(), z);
				}
			}
		}
		throw new IllegalStateException("every column of " + chunk + " is within 3 blocks of " + box);
	}

	private static void expect(GameTestHelper helper, ServerLevel level, BlockPos pos, Block block) {
		if (!level.getBlockState(pos).is(block)) {
			throw failure(helper, "%s at %s, expected %s", level.getBlockState(pos), pos.toShortString(), block);
		}
	}

	/** No fluid and no gas pocket in the site's bounds and the shell round them. */
	private static void requireNoFluidOrGas(GameTestHelper helper, ServerLevel level, StructureSite site) {
		requireNoFluidOrGas(helper, level, site, null);
	}

	/** As above, limited to the blocks of {@code only} when it is given. */
	private static void requireNoFluidOrGas(GameTestHelper helper, ServerLevel level, StructureSite site, ChunkPos only) {
		BoundingBox box = site.bounds();
		for (BlockPos pos : BlockPos.betweenClosed(box.minX() - 1, box.minY() - 1, box.minZ() - 1, box.maxX() + 1, box.maxY() + 1, box.maxZ() + 1)) {
			if (only != null && !only.equals(ChunkPos.containing(pos))) {
				continue;
			}
			BlockState state = level.getBlockState(pos);
			if (!state.getFluidState().isEmpty() || state.is(HazardBlocks.GAS_POCKET)) {
				throw failure(helper, "%s holds %s at %s", site.kind(), state, pos.toShortString());
			}
		}
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
