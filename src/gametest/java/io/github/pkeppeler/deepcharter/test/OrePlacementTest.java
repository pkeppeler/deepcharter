package io.github.pkeppeler.deepcharter.test;

import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.Vec3;

import io.github.pkeppeler.deepcharter.layer.Depth;
import io.github.pkeppeler.deepcharter.layer.LayerChain;
import io.github.pkeppeler.deepcharter.layer.Zones;
import io.github.pkeppeler.deepcharter.ore.GasHazard;
import io.github.pkeppeler.deepcharter.ore.HazardBlocks;
import io.github.pkeppeler.deepcharter.ore.OreRegistry;
import io.github.pkeppeler.deepcharter.ore.OreTuning;
import io.github.pkeppeler.deepcharter.ore.OreType;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.test.support.FarChunks;
import io.github.pkeppeler.deepcharter.test.support.MockPlayer;
import io.github.pkeppeler.deepcharter.test.support.MockPlayers;

/**
 * Server GameTests for #63: ore and hazards placed per zone, the gas formula and blast, and what hands and drills
 * cannot break. The placement tests generate real chunks of both layers and count.
 */
public class OrePlacementTest {
	private static final Logger LOGGER = LoggerFactory.getLogger(OrePlacementTest.class);

	private static final int MAX_TICKS = FarChunks.AWAIT_BUDGET_TICKS + 1000;
	private static final Input SPRINT = new Input(false, false, false, false, false, false, true);
	private static final int Z = 3000;

	/** Zone names top to bottom, layer 1 then layer 2: the six sampled zones in order. */
	private static final String[] ZONE_NAMES = {"topsoil_claims", "stone_benches", "deep_claim",
			"upper_levels", "shift_change", "prospectors_run"};

	/**
	 * The original's rows for each zone, inclusive (REFERENCE.md section 4): layer 1 is rows 6 to 65 and layer 2 rows 65
	 * to 134, each in thirds.
	 */
	private static final int[][] ROWS = {{6, 25}, {26, 45}, {46, 65}, {65, 88}, {89, 111}, {112, 134}};

	/**
	 * A drill slab is 4 blocks where the original's tile is one, so every chance is the original's times 1/4, which
	 * keeps the chance that a slab holds ore (or a hazard) near the original's chance for a tile.
	 */
	private static final double SLAB_SCALE = 0.25;

	/**
	 * Cicatrium is invented, so it is not in the original's rows: none above layer 2's middle zone, rarer than
	 * platinium below.
	 */
	private static final double[] CICATRIUM = {0, 0, 0, 0, 0.0002, 0.0006};

	/**
	 * Chance that a stone block with no ore becomes rock, lava or gas, per zone. The original's hazard bands are
	 * compressed into the layers: rock from Stone Benches, lava and gas from Deep Claim, each stepping up as the
	 * original's rows 200, 450 and 550 do (8%, 13%, 40% rock of the solid tiles), times {@link #SLAB_SCALE}.
	 */
	private static final double[][] HAZARDS = {
			{0, 0, 0},
			{0.02, 0, 0},
			{0.025, 0.0125, 0.0125},
			{0.0333, 0.0167, 0.0167},
			{0.05, 0.025, 0.025},
			{0.1, 0.05, 0.05}};

	private static final int SIDE = 6;
	private static final int FIRST_CHUNK = 400;

	/** Blocks counted in one zone: stone, our ore, our hazards and lava sources. */
	private record Census(int candidates, Map<Block, Integer> found) {
		int of(Block block) {
			return found.getOrDefault(block, 0);
		}
	}

	private static Census[] census;

	@GameTest(maxTicks = 6000)
	public void oreAndHazardFrequenciesMatchTheTablesPerZone(GameTestHelper helper) {
		Census[] sample = sample(helper);
		for (int zone = 0; zone < ZONE_NAMES.length; zone++) {
			Census counted = sample[zone];
			double oreTotal = 0;
			for (Block block : placedBlocks()) {
				double expected = oreChance(block, zone);
				oreTotal += expected;
				expectFrequency(helper, zone, counted, block, expected);
			}
			double bare = 1 - oreTotal;
			expectFrequency(helper, zone, counted, HazardBlocks.COMPANY_ROCK, bare * HAZARDS[zone][0]);
			expectFrequency(helper, zone, counted, Blocks.LAVA, bare * HAZARDS[zone][1]);
			expectFrequency(helper, zone, counted, HazardBlocks.GAS_POCKET, bare * HAZARDS[zone][2]);
		}
		helper.succeed();
	}

	@GameTest(maxTicks = 6000)
	public void hazardsStartWhereTheIssueSaysAndCicatriumIsDeep(GameTestHelper helper) {
		Census[] sample = sample(helper);
		for (int zone = 0; zone < ZONE_NAMES.length; zone++) {
			boolean rock = zone >= 1;
			boolean lavaAndGas = zone >= 2;
			expectPresence(helper, zone, sample[zone], HazardBlocks.COMPANY_ROCK, rock);
			expectPresence(helper, zone, sample[zone], Blocks.LAVA, lavaAndGas);
			expectPresence(helper, zone, sample[zone], HazardBlocks.GAS_POCKET, lavaAndGas);
			expectPresence(helper, zone, sample[zone], OreRegistry.block(OreType.CICATRIUM), zone >= 4);
		}
		for (int zone = 4; zone < 6; zone++) {
			int cicatrium = sample[zone].of(OreRegistry.block(OreType.CICATRIUM));
			int platinium = sample[zone].of(OreRegistry.block(OreType.PLATINIUM));
			if (cicatrium >= platinium) {
				throw failure(helper, "%s has %d cicatrium and %d platinium: cicatrium is the rarer", ZONE_NAMES[zone], cicatrium, platinium);
			}
		}
		helper.succeed();
	}

	@GameTest
	public void gasDamageIsDepthTimesRadiator(GameTestHelper helper) {
		float perFoot = OreTuning.DEFAULT.gasDamagePerFoot();
		for (int depth : new int[] {0, 1, 100, 433, 1500, 6000}) {
			for (float radiator : new float[] {0f, 0.5f, 1f, 2.5f}) {
				float expected = depth * radiator * perFoot;
				float actual = GasHazard.damage(depth, radiator);
				if (Math.abs(actual - expected) > 1e-3f * Math.max(1f, expected)) {
					throw failure(helper, "damage(%d ft, radiator %s) is %s, expected depth x radiator x %s = %s", depth, radiator, actual, perFoot, expected);
				}
			}
		}
		if (GasHazard.damage(-50, 1f) != 0f) {
			throw failure(helper, "gas above sea level hurts: %s", GasHazard.damage(-50, 1f));
		}
		for (float bad : new float[] {-1f, Float.NaN, Float.POSITIVE_INFINITY}) {
			try {
				GasHazard.damage(100, bad);
			} catch (IllegalArgumentException expected) {
				continue;
			}
			throw failure(helper, "a radiator factor of %s was accepted", bad);
		}
		helper.succeed();
	}

	@GameTest
	public void aGasBlastClearsOnlyNaturalRock(GameTestHelper helper) {
		ServerLevel level = layer(helper, 1);
		BlockPos centre = new BlockPos(7000, 60, Z);
		stoneCube(level, centre, 3);
		Map<BlockPos, Block> kept = new LinkedHashMap<>();
		Map<BlockPos, Block> cleared = new LinkedHashMap<>();
		kept.put(centre.east(), Blocks.OAK_PLANKS);
		kept.put(centre.above(), Blocks.STONE_BRICKS);
		kept.put(centre.below(), Blocks.GOLD_BLOCK);
		kept.put(centre.west(), HazardBlocks.COMPANY_ROCK);
		kept.put(centre.south(), Blocks.LAVA);
		kept.put(centre.east().north().below(), Blocks.DIRT);
		cleared.put(centre.east().above(), OreRegistry.block(OreType.IRONIUM));
		cleared.put(centre.west().above(), HazardBlocks.GAS_POCKET);
		cleared.put(centre.north(), Blocks.STONE);
		cleared.put(centre.east().south().above(), Blocks.DEEPSLATE);
		for (Map.Entry<BlockPos, Block> entry : kept.entrySet()) {
			level.setBlock(entry.getKey(), entry.getValue().defaultBlockState(), 2);
		}
		for (Map.Entry<BlockPos, Block> entry : cleared.entrySet()) {
			level.setBlock(entry.getKey(), entry.getValue().defaultBlockState(), 2);
		}
		level.setBlock(centre, HazardBlocks.GAS_POCKET.defaultBlockState(), 2);
		level.destroyBlock(centre, false);

		for (BlockPos pos : BlockPos.betweenClosed(centre.offset(-3, -3, -3), centre.offset(3, 3, 3))) {
			BlockState state = level.getBlockState(pos);
			boolean inside = Math.abs(pos.getX() - centre.getX()) <= 1 && Math.abs(pos.getY() - centre.getY()) <= 1
					&& Math.abs(pos.getZ() - centre.getZ()) <= 1;
			Block wasKept = kept.get(pos);
			if (wasKept != null) {
				if (!state.is(wasKept)) {
					throw failure(helper, "the blast removed %s at %s, which is not natural rock", wasKept, pos.toShortString());
				}
			} else if (inside && !state.isAir()) {
				throw failure(helper, "the blast left %s at %s inside the 3 x 3 x 3", state.getBlock(), pos.toShortString());
			} else if (!inside && !state.is(Blocks.STONE)) {
				throw failure(helper, "the blast reached %s, outside the 3 x 3 x 3: found %s", pos.toShortString(), state.getBlock());
			}
		}
		helper.succeed();
	}

	@GameTest
	public void theBlastIsNaturalRockByTagAndCompanyRockIsNot(GameTestHelper helper) {
		BlockState[] natural = {Blocks.STONE.defaultBlockState(), Blocks.DEEPSLATE.defaultBlockState(),
				OreRegistry.block(OreType.EINSTEINIUM).defaultBlockState(), HazardBlocks.GAS_POCKET.defaultBlockState()};
		BlockState[] other = {Blocks.OAK_PLANKS.defaultBlockState(), Blocks.STONE_BRICKS.defaultBlockState(),
				HazardBlocks.COMPANY_ROCK.defaultBlockState(), Blocks.LAVA.defaultBlockState(), Blocks.AIR.defaultBlockState()};
		for (BlockState state : natural) {
			if (!state.is(HazardBlocks.NATURAL_ROCK)) {
				throw failure(helper, "%s should be natural rock", state.getBlock());
			}
		}
		for (BlockState state : other) {
			if (state.is(HazardBlocks.NATURAL_ROCK)) {
				throw failure(helper, "%s should not be natural rock", state.getBlock());
			}
		}
		helper.succeed();
	}

	@GameTest
	public void aGasBlastCostsAPodDepthTimesRadiator(GameTestHelper helper) {
		ServerLevel level = layer(helper, 1);
		BlockPos centre = new BlockPos(7100, 60, Z);
		stoneCube(level, centre, 3);
		PodEntity near = pod(level, Vec3.atBottomCenterOf(centre.above()));
		PodEntity far = pod(level, Vec3.atBottomCenterOf(centre.offset(10, 1, 0)));
		float before = near.hull();
		level.setBlock(centre, HazardBlocks.GAS_POCKET.defaultBlockState(), 2);
		// A second pocket in the blast: the blast is one event, so it is one payment.
		level.setBlock(centre.east(), HazardBlocks.GAS_POCKET.defaultBlockState(), 2);
		level.destroyBlock(centre, false);

		float damage = GasHazard.damage(Depth.feet(Depth.of(level, centre.getY())), OreTuning.DEFAULT.stockRadiator());
		if (damage <= 0f || damage >= before) {
			throw failure(helper, "the test depth gives a damage of %s on a hull of %s", damage, before);
		}
		if (Math.abs(near.hull() - (before - damage)) > 1e-3f) {
			throw failure(helper, "the pod's hull is %s after the blast, expected %s - %s = %s", near.hull(), before, damage, before - damage);
		}
		if (far.hull() != before) {
			throw failure(helper, "a pod 10 blocks away was hurt: hull %s", far.hull());
		}
		near.discard();
		far.discard();
		helper.succeed();
	}

	/** The drill removes the pocket like any block, so nothing in {@code PodDrill} knows about gas. */
	@GameTest(maxTicks = MAX_TICKS)
	public void aPodDrillingIntoGasPaysTheBlast(GameTestHelper helper) {
		int x = 9000;
		int floor = 60;
		ServerLevel level = layer(helper, 1);
		room(level, x, floor);
		BlockPos gas = new BlockPos(x - 1, floor - 1, Z - 1);
		level.setBlock(gas, HazardBlocks.GAS_POCKET.defaultBlockState(), 2);
		Rig rig = Rig.await(helper, level, new Vec3(x, floor, Z), "gas-driller");
		float[] before = {Float.NaN};
		helper.onEachTick(() -> {
			if (!rig.ready()) {
				return;
			}
			if (Float.isNaN(before[0])) {
				before[0] = rig.pod.hull();
			}
			if (level.getBlockState(gas).isAir()) {
				rig.pilot.releaseInput();
				float damage = GasHazard.damage(Depth.feet(Depth.of(level, gas.getY())), OreTuning.DEFAULT.stockRadiator());
				if (damage <= 0f) {
					throw failure(helper, "the test depth gives no gas damage");
				}
				if (Math.abs(rig.pod.hull() - (before[0] - damage)) > 1e-3f) {
					throw failure(helper, "the drilled pod has hull %s, expected %s - %s", rig.pod.hull(), before[0], damage);
				}
				rig.pod.discard();
				helper.succeed();
			}
		});
	}

	/** SPEC section 9 and the note from #54: a mod ore in layer 2 is deep rock like any other. */
	@GameTest
	public void handsCannotBreakAModOreInLayerTwo(GameTestHelper helper) {
		MockPlayer mock = MockPlayers.join(helper, "ore-hands");
		ServerLevel one = layer(helper, 1);
		ServerLevel two = layer(helper, 2);
		BlockPos pos = new BlockPos(7200, 80, Z);
		Block ironium = OreRegistry.block(OreType.IRONIUM);
		mock.teleportTo(two, Vec3.atBottomCenterOf(pos.above(4)), 0, 0);
		mock.player().setGameMode(GameType.SURVIVAL);
		two.setBlock(pos, ironium.defaultBlockState(), 3);
		if (mock.player().gameMode.destroyBlock(pos) || !two.getBlockState(pos).is(ironium)) {
			throw failure(helper, "a survival player broke ironium ore by hand in layer_2");
		}
		for (OreType type : OreType.values()) {
			BlockPos other = pos.east(1 + type.ordinal());
			two.setBlock(other, OreRegistry.block(type).defaultBlockState(), 3);
			if (mock.player().gameMode.destroyBlock(other) || !two.getBlockState(other).is(OreRegistry.block(type))) {
				throw failure(helper, "a survival player broke %s by hand in layer_2", type.blockId());
			}
		}
		mock.player().setGameMode(GameType.ADVENTURE);
		if (mock.player().gameMode.destroyBlock(pos) || !two.getBlockState(pos).is(ironium)) {
			throw failure(helper, "an adventure player broke ironium ore by hand in layer_2");
		}
		mock.teleportTo(one, Vec3.atBottomCenterOf(pos.above(4)), 0, 0);
		mock.player().setGameMode(GameType.SURVIVAL);
		one.setBlock(pos, ironium.defaultBlockState(), 3);
		if (!mock.player().gameMode.destroyBlock(pos)) {
			throw failure(helper, "a survival player could not break ironium ore by hand in layer_1");
		}
		helper.succeed();
	}

	@GameTest
	public void handsCannotBreakCompanyRockInAnyLayer(GameTestHelper helper) {
		MockPlayer mock = MockPlayers.join(helper, "company-hands");
		BlockPos pos = new BlockPos(7300, 80, Z);
		for (int layer = 1; layer <= 2; layer++) {
			ServerLevel level = layer(helper, layer);
			mock.teleportTo(level, Vec3.atBottomCenterOf(pos.above(4)), 0, 0);
			level.setBlock(pos, HazardBlocks.COMPANY_ROCK.defaultBlockState(), 3);
			for (GameType mode : new GameType[] {GameType.SURVIVAL, GameType.ADVENTURE}) {
				mock.player().setGameMode(mode);
				if (mock.player().gameMode.destroyBlock(pos) || !level.getBlockState(pos).is(HazardBlocks.COMPANY_ROCK)) {
					throw failure(helper, "a %s player broke company rock by hand in layer_%d", mode, layer);
				}
			}
			if (!level.getBlockState(pos).is(HazardBlocks.UNDIGGABLE)) {
				throw failure(helper, "company rock is not in the undiggable tag");
			}
		}
		helper.succeed();
	}

	/**
	 * Company rock is the undiggable tag's one block, and a drill refuses anything in the tag whatever its hardness: the
	 * test data pack tags coarse dirt too, which is soft, so only the tag can be what stops the drill.
	 */
	@GameTest(maxTicks = MAX_TICKS)
	public void aPodDrillCannotBoreCompanyRockOrAnyUndiggableBlock(GameTestHelper helper) {
		int floor = 60;
		Block[] blockers = {HazardBlocks.COMPANY_ROCK, Blocks.COARSE_DIRT};
		ServerLevel level = layer(helper, 1);
		Rig[] refused = new Rig[blockers.length];
		BlockPos[] blocked = new BlockPos[blockers.length];
		for (int i = 0; i < blockers.length; i++) {
			int x = 9100 + 32 * i;
			room(level, x, floor);
			blocked[i] = new BlockPos(x - 1, floor - 1, Z - 1);
			level.setBlock(blocked[i], blockers[i].defaultBlockState(), 2);
			refused[i] = Rig.await(helper, level, new Vec3(x, floor, Z), "undiggable-" + i);
		}
		int controlX = 9100 + 32 * blockers.length;
		room(level, controlX, floor);
		Rig control = Rig.await(helper, level, new Vec3(controlX, floor, Z), "undiggable-control");
		boolean[] controlDrilled = {false};
		helper.onEachTick(() -> {
			if (!control.ready()) {
				return;
			}
			for (Rig rig : refused) {
				if (!rig.ready()) {
					return;
				}
			}
			controlDrilled[0] |= control.pod.drilling();
			for (int i = 0; i < blockers.length; i++) {
				if (refused[i].pod.drilling() || !level.getBlockState(blocked[i]).is(blockers[i])) {
					throw failure(helper, "the drill started on, or removed, %s", blockers[i]);
				}
			}
			if (control.pod.tickCount < 120) {
				return;
			}
			if (!controlDrilled[0]) {
				throw failure(helper, "the control pod never drilled the plain stone, so the test proved nothing");
			}
			for (int i = 0; i < blockers.length; i++) {
				if (!refused[i].pod.onGround()) {
					throw failure(helper, "pod %d is not standing on the floor of its room: %s", i, refused[i].pod.position());
				}
				// The pod's whole 2 x 2 slab is refused, so the plain stone beside the blocker stays too.
				for (int dx = 0; dx <= 1; dx++) {
					for (int dz = 0; dz <= 1; dz++) {
						BlockPos cell = blocked[i].offset(dx, 0, dz);
						if (level.getBlockState(cell).isAir()) {
							throw failure(helper, "the drill bored %s next to %s", cell.toShortString(), blockers[i]);
						}
					}
				}
			}
			for (Rig rig : refused) {
				rig.pod.discard();
			}
			control.pod.discard();
			helper.succeed();
		});
	}

	// Sampling and counting.

	private static Block[] placedBlocks() {
		OreType[] types = OreType.values();
		Block[] blocks = new Block[types.length];
		for (int i = 0; i < types.length; i++) {
			blocks[i] = OreRegistry.block(types[i]);
		}
		return blocks;
	}

	/** Counts the blocks of {@link #SIDE} x {@link #SIDE} chunks of each layer, once for all the tests of the class. */
	private static Census[] sample(GameTestHelper helper) {
		if (census != null) {
			return census;
		}
		Census[] result = new Census[ZONE_NAMES.length];
		for (int layer = 1; layer <= 2; layer++) {
			ServerLevel level = layer(helper, layer);
			int minY = level.getMinY();
			int height = level.getHeight();
			Map<Block, Integer>[] found = newCounters(3);
			int[] candidates = new int[3];
			long start = System.nanoTime();
			for (int cx = FIRST_CHUNK; cx < FIRST_CHUNK + SIDE; cx++) {
				for (int cz = FIRST_CHUNK; cz < FIRST_CHUNK + SIDE; cz++) {
					level.getChunk(cx, cz, ChunkStatus.FULL);
					for (int x = cx << 4; x < (cx << 4) + 16; x++) {
						for (int z = cz << 4; z < (cz << 4) + 16; z++) {
							for (int y = minY; y < minY + height; y++) {
								BlockState state = level.getBlockState(new BlockPos(x, y, z));
								Block block = state.getBlock();
								if (isCandidate(state)) {
									int zone = Zones.index(minY, height, y);
									candidates[zone]++;
									found[zone].merge(block, 1, Integer::sum);
								}
							}
						}
					}
				}
			}
			LOGGER.info("Sampled layer_{}: {} chunks in {} ms", layer, SIDE * SIDE, Math.round((System.nanoTime() - start) / 1e6));
			for (int index = 0; index < 3; index++) {
				// Zone 0 of a layer is its top third.
				result[(layer - 1) * 3 + index] = new Census(candidates[index], found[index]);
			}
		}
		census = result;
		return census;
	}

	@SuppressWarnings("unchecked")
	private static Map<Block, Integer>[] newCounters(int count) {
		Map<Block, Integer>[] counters = new Map[count];
		for (int i = 0; i < count; i++) {
			counters[i] = new LinkedHashMap<>();
		}
		return counters;
	}

	/** Blocks that a zone fill may have decided: stone, what it puts in stone, and lava sources (flowing lava is not its). */
	private static boolean isCandidate(BlockState state) {
		if (state.is(Blocks.LAVA)) {
			return state.getFluidState().isSource();
		}
		return state.is(Blocks.STONE) || state.is(HazardBlocks.COMPANY_ROCK) || state.is(HazardBlocks.GAS_POCKET)
				|| OreRegistry.typeOf(state.getBlock()).isPresent();
	}

	/**
	 * The original's chance for this ore on one tile, averaged over the zone's rows, given the tile is solid (REFERENCE.md
	 * section 4): 20% of tiles are ore, 80% of those of the base tier {@code 6 + random(row / 65 + 2)}, 16% one tier up and
	 * 4% two up (a quarter of those become artifacts below row 80, which have no block here). Times {@link #SLAB_SCALE}.
	 */
	private static double oreChance(Block block, int zone) {
		OreType type = OreRegistry.typeOf(block).orElseThrow();
		if (type == OreType.CICATRIUM) {
			return CICATRIUM[zone];
		}
		int tier = 6 + type.ordinal();
		double sum = 0;
		int rows = 0;
		for (int row = ROWS[zone][0]; row <= ROWS[zone][1]; row++) {
			int n = row / 65 + 2;
			double artifacts = row > 80 ? 0.25 : 0;
			sum += 0.2 * (0.8 * share(tier, 6, n) + 0.16 * share(tier, 7, n) + 0.04 * (1 - artifacts) * share(tier, 8, n));
			rows++;
		}
		return SLAB_SCALE * sum / rows;
	}

	/** Chance that {@code 'first' + random(n)} is {@code tier}. */
	private static double share(int tier, int first, int n) {
		return tier >= first && tier < first + n ? 1.0 / n : 0;
	}

	/**
	 * Each block is an independent roll, so a count is binomial: it must be within five standard deviations of
	 * {@code candidates x chance}, plus one block for a chance so small that the count is mostly 0 or 1. Five sigma is a
	 * failure about once in two million per check, and the zone tables have 54 of them. A chance of 0 must give a count of 0.
	 */
	private static void expectFrequency(GameTestHelper helper, int zone, Census counted, Block block, double chance) {
		int n = counted.candidates();
		int actual = counted.of(block);
		double expected = n * chance;
		double tolerance = chance == 0 ? 0 : 5 * Math.sqrt(n * chance * (1 - chance)) + 1;
		LOGGER.info("{} {}: {} of {} blocks ({} %), expected {} ({} %), tolerance {}", ZONE_NAMES[zone], block.getName().getString(),
				actual, n, String.format("%.4f", 100.0 * actual / n), Math.round(expected), String.format("%.4f", 100 * chance),
				Math.round(tolerance));
		if (Math.abs(actual - expected) > tolerance) {
			throw failure(helper, "%s has %d of %s in %d blocks, expected %.0f +- %.0f", ZONE_NAMES[zone], actual, block, n, expected, tolerance);
		}
	}

	private static void expectPresence(GameTestHelper helper, int zone, Census counted, Block block, boolean present) {
		int actual = counted.of(block);
		if (present != (actual > 0)) {
			throw failure(helper, "%s has %d of %s, expected %s", ZONE_NAMES[zone], actual, block, present ? "some" : "none");
		}
	}

	// World building.

	/** A cube of stone, {@code radius} blocks out from {@code centre} on every axis. */
	private static void stoneCube(ServerLevel level, BlockPos centre, int radius) {
		for (BlockPos pos : BlockPos.betweenClosed(centre.offset(-radius, -radius, -radius), centre.offset(radius, radius, radius))) {
			level.setBlock(pos, Blocks.STONE.defaultBlockState(), 2);
		}
	}

	/** Stone up to and including y=floor-1 under a 9 x 9 around (x, Z), and air for 10 blocks above it. */
	private static void room(ServerLevel level, int x, int floor) {
		for (int bx = x - 4; bx <= x + 4; bx++) {
			for (int bz = Z - 4; bz <= Z + 4; bz++) {
				for (int y = floor - 8; y <= floor + 10; y++) {
					level.setBlock(new BlockPos(bx, y, bz), (y < floor ? Blocks.STONE : Blocks.AIR).defaultBlockState(), 2);
				}
			}
		}
	}

	private static PodEntity pod(ServerLevel level, Vec3 at) {
		PodEntity pod = PodRegistry.POD.create(level, EntitySpawnReason.COMMAND);
		pod.setPos(at);
		level.addFreshEntity(pod);
		return pod;
	}

	/** A mock pilot, and the pod it sits in once the far chunk ticks entities. */
	private static final class Rig {
		private final MockPlayer pilot;
		private PodEntity pod;

		private Rig(MockPlayer pilot) {
			this.pilot = pilot;
		}

		boolean ready() {
			return pod != null;
		}

		/** Must be called from the test method; the pod sprints (bores down) from the moment it exists. */
		static Rig await(GameTestHelper helper, ServerLevel level, Vec3 at, String name) {
			MockPlayer pilot = MockPlayers.join(helper, name);
			pilot.teleportTo(level, at, 0f, 0f);
			Rig rig = new Rig(pilot);
			FarChunks.awaitEntityTicking(helper, level, BlockPos.containing(at), () -> {
				PodEntity pod = pod(level, at);
				if (!pilot.player().startRiding(pod)) {
					throw failure(helper, "the pilot could not mount the pod");
				}
				pilot.setInput(SPRINT);
				rig.pod = pod;
			});
			return rig;
		}
	}

	// assertionException(String, Object...) leaves the placeholders unfilled in the report.
	private static RuntimeException failure(GameTestHelper helper, String format, Object... args) {
		return helper.assertionException(Component.literal(String.format(format, args)));
	}

	private static ServerLevel layer(GameTestHelper helper, int layer) {
		ServerLevel level = helper.getLevel().getServer().getLevel(LayerChain.dimension(layer));
		if (level == null) {
			throw failure(helper, "dimension %s did not load", LayerChain.dimension(layer));
		}
		return level;
	}
}
