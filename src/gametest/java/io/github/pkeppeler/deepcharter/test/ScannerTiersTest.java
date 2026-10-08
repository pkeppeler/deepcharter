package io.github.pkeppeler.deepcharter.test;

import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import io.github.pkeppeler.deepcharter.charter.CharterId;
import io.github.pkeppeler.deepcharter.charter.Charters;
import io.github.pkeppeler.deepcharter.ore.HazardBlocks;
import io.github.pkeppeler.deepcharter.pod.PodComponents;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.scanner.ScanArea;
import io.github.pkeppeler.deepcharter.scanner.ScanSlice;
import io.github.pkeppeler.deepcharter.scanner.ScanSlice.Cell;
import io.github.pkeppeler.deepcharter.scanner.ScannerTuning;
import io.github.pkeppeler.deepcharter.upgrade.ComponentItems;
import io.github.pkeppeler.deepcharter.upgrade.ComponentTrack;

/** Server GameTests for #74: what a scanner slice covers and shows at each tier. */
public class ScannerTiersTest {
	/** Well above the flat test world, so every tier's dive stays inside the build limits. */
	private static final int ORIGIN_Y = 150;
	private static final AtomicInteger CHARTERS = new AtomicInteger();

	private static BlockPos origin(GameTestHelper helper) {
		BlockPos at = helper.absolutePos(new BlockPos(2, 0, 2));
		return new BlockPos(at.getX(), ORIGIN_Y, at.getZ());
	}

	private static void place(ServerLevel level, BlockPos at, Block block) {
		if (!level.setBlock(at, block.defaultBlockState(), 2)) {
			throw new IllegalStateException("could not place " + block + " at " + at);
		}
	}

	private static RuntimeException failure(GameTestHelper helper, String message, Object... args) {
		return helper.assertionException(message.formatted(args));
	}

	@GameTest
	public void areaGrowsWithTier(GameTestHelper helper) {
		// Tier 1 is M1's range; the scanner track is 0.5, 0.75, 1, 1.5 times the stock area at tiers 1 to 4.
		int[][] expected = {{24, 8, 32}, {36, 12, 48}, {48, 16, 64}, {72, 24, 96}};
		for (int tier = 1; tier <= expected.length; tier++) {
			ScanArea actual = ScannerTuning.DEFAULT.area(tier);
			ScanArea wanted = new ScanArea(expected[tier - 1][0], expected[tier - 1][1], expected[tier - 1][2]);
			if (!actual.equals(wanted)) {
				throw failure(helper, "tier %d should cover %s, covers %s", tier, wanted, actual);
			}
		}
		for (int tier : new int[] {0, 5, -1}) {
			try {
				ScannerTuning.DEFAULT.area(tier);
			} catch (IllegalArgumentException expectedRefusal) {
				continue;
			}
			throw failure(helper, "a tier %d scanner has no area and must be refused", tier);
		}
		helper.succeed();
	}

	@GameTest
	public void aHigherTierSeesOreBeyondALowerTiersRange(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		BlockPos origin = origin(helper);
		// Beyond tier 1 on every side, inside tier 2.
		BlockPos far = origin.offset(30, 10, 0);
		BlockPos deep = origin.offset(-30, -40, 0);
		place(level, far, Blocks.GOLD_ORE);
		place(level, deep, Blocks.DIAMOND_ORE);
		try {
			ScanSlice one = ScanSlice.scan(level, origin, Direction.EAST, 1);
			ScanSlice two = ScanSlice.scan(level, origin, Direction.EAST, 2);
			for (int[] cell : new int[][] {{30, 10}, {-30, -40}}) {
				if (one.area().contains(cell[0], cell[1])) {
					throw failure(helper, "cell %s should be outside tier 1", Arrays.toString(cell));
				}
			}
			if (!two.cell(30, 10).equals(new Cell.Ore(Blocks.GOLD_ORE)) || !two.cell(-30, -40).equals(new Cell.Ore(Blocks.DIAMOND_ORE))) {
				throw failure(helper, "tier 2 should see the gold at (30, 10) and the diamond at (-30, -40), saw %s and %s",
						two.cell(30, 10), two.cell(-30, -40));
			}
			helper.succeed();
		} finally {
			place(level, far, Blocks.AIR);
			place(level, deep, Blocks.AIR);
		}
	}

	@GameTest
	public void gasShowsFromTierThreeAndLooksLikeRockBelow(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		BlockPos origin = origin(helper);
		BlockPos gas = origin.offset(3, -2, 0);
		BlockPos ore = origin.offset(5, -2, 0);
		place(level, gas, HazardBlocks.GAS_POCKET);
		place(level, ore, Blocks.GOLD_ORE);
		try {
			for (int tier = 1; tier <= 4; tier++) {
				ScanSlice slice = ScanSlice.scan(level, origin, Direction.EAST, tier);
				Cell expectedGas = tier >= 3 ? Cell.GAS : Cell.ROCK;
				if (!slice.cell(3, -2).equals(expectedGas)) {
					throw failure(helper, "a gas pocket at tier %d should read as %s, read as %s", tier, expectedGas, slice.cell(3, -2));
				}
				if (!slice.cell(5, -2).equals(new Cell.Ore(Blocks.GOLD_ORE))) {
					throw failure(helper, "ore beside gas at tier %d should still be ore, was %s", tier, slice.cell(5, -2));
				}
			}
			helper.succeed();
		} finally {
			place(level, gas, Blocks.AIR);
			place(level, ore, Blocks.AIR);
		}
	}

	/** Fluids keep the docs/BLOCKERS.md default at every tier until the user decides. */
	@GameTest
	public void fluidsStayOpenSpaceAtEveryTier(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		BlockPos origin = origin(helper);
		BlockPos water = origin.offset(1, 0, 0);
		BlockPos lava = origin.offset(6, 0, 0);
		place(level, water, Blocks.WATER);
		place(level, lava, Blocks.LAVA);
		try {
			for (int tier = 1; tier <= 4; tier++) {
				ScanSlice slice = ScanSlice.scan(level, origin, Direction.EAST, tier);
				if (!slice.cell(1, 0).equals(Cell.AIR) || !slice.cell(6, 0).equals(Cell.AIR)) {
					throw failure(helper, "water and lava should read as air at tier %d, read %s and %s", tier, slice.cell(1, 0), slice.cell(6, 0));
				}
			}
			helper.succeed();
		} finally {
			place(level, water, Blocks.AIR);
			place(level, lava, Blocks.AIR);
		}
	}

	@GameTest
	public void aPodScansByItsEffectiveScannerTier(GameTestHelper helper) {
		CharterId charter = charter(helper);
		CharterId other = charter(helper);
		PodEntity none = ownedPod(helper, charter);
		PodEntity one = ownedPod(helper, charter);
		PodEntity capped = ownedPod(helper, charter);
		PodEntity foreign = ownedPod(helper, charter);
		try {
			install(helper, one, 1, charter);
			install(helper, capped, 4, charter);
			install(helper, foreign, 2, other);
			ServerLevel level = helper.getLevel();
			if (ScanSlice.scan(level, none).isPresent()) {
				throw failure(helper, "a pod with no scanner has no slice");
			}
			if (ScanSlice.scan(level, foreign).isPresent()) {
				throw failure(helper, "another charter's scanner is void and gives no slice");
			}
			expectTier(helper, ScanSlice.scan(level, one), 1);
			// The Mole takes parts to tier 2, so a tier 4 scanner works as tier 2 and shows no gas.
			expectTier(helper, ScanSlice.scan(level, capped), 2);
			helper.succeed();
		} finally {
			none.discard();
			one.discard();
			capped.discard();
			foreign.discard();
		}
	}

	@GameTest
	public void aPodsSliceFollowsItsFootsAndFacing(GameTestHelper helper) {
		CharterId charter = charter(helper);
		PodEntity pod = ownedPod(helper, charter);
		ServerLevel level = helper.getLevel();
		Direction facing = pod.getDirection();
		BlockPos at = pod.blockPosition().relative(facing, 4).above(6);
		place(level, at, Blocks.GOLD_ORE);
		try {
			install(helper, pod, 1, charter);
			ScanSlice slice = ScanSlice.scan(level, pod).orElseThrow();
			if (!slice.cell(4, 6).equals(new Cell.Ore(Blocks.GOLD_ORE))) {
				throw failure(helper, "ore 4 ahead and 6 above the pod's feet should be at (4, 6), was %s", slice.cell(4, 6));
			}
			helper.succeed();
		} finally {
			place(level, at, Blocks.AIR);
			pod.discard();
		}
	}

	private static void expectTier(GameTestHelper helper, Optional<ScanSlice> slice, int tier) {
		if (slice.isEmpty() || slice.get().tier() != tier) {
			throw failure(helper, "expected a tier %d slice, got %s", tier, slice.map(ScanSlice::tier));
		}
	}

	private static CharterId charter(GameTestHelper helper) {
		UUID founder = UUID.randomUUID();
		if (Charters.found(helper.getLevel().getServer(), founder, "Scanner tiers " + CHARTERS.incrementAndGet()).isPresent()) {
			throw failure(helper, "could not found a charter");
		}
		return Charters.charterOf(helper.getLevel().getServer(), founder).orElseThrow().id();
	}

	private static PodEntity ownedPod(GameTestHelper helper, CharterId owner) {
		PodEntity pod = helper.spawn(PodRegistry.POD, 2, 2, 2);
		PodComponents.register(pod, owner);
		return pod;
	}

	private static void install(GameTestHelper helper, PodEntity pod, int tier, CharterId charter) {
		PodComponents.install(pod, ComponentItems.mint(helper.getLevel().getServer(), ComponentTrack.SCANNER, tier, charter));
	}
}
