package io.github.pkeppeler.deepcharter.test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import com.mojang.brigadier.exceptions.CommandSyntaxException;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
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
import io.github.pkeppeler.deepcharter.test.support.MockPlayer;
import io.github.pkeppeler.deepcharter.test.support.MockPlayers;
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

	/** {@code hasOre} is the cheap form of "the slice holds an ore": it agrees with {@code scan} for every pod, with ore in and out of reach. */
	@GameTest
	public void hasOreAgreesWithTheScanForAFittedScanner(GameTestHelper helper) {
		CharterId charter = charter(helper);
		CharterId other = charter(helper);
		PodEntity none = ownedPod(helper, charter);
		PodEntity one = ownedPod(helper, charter);
		PodEntity two = ownedPod(helper, charter);
		PodEntity foreign = ownedPod(helper, charter);
		ServerLevel level = helper.getLevel();
		BlockPos feet = one.blockPosition();
		Direction facing = one.getDirection();
		// Past the reach of tier 1 and inside tier 2: above the 8 blocks that tier 1 sees up.
		BlockPos beyondOne = feet.relative(facing, 3).above(ScannerTuning.DEFAULT.tierOneArea().up() + 2);
		BlockPos near = feet.relative(facing, 2).above(1);
		try {
			install(helper, one, 1, charter);
			install(helper, two, 2, charter);
			install(helper, foreign, 2, other);
			List<PodEntity> pods = List.of(none, one, two, foreign);
			expectAgreement(helper, level, pods, "no ore around", List.of(false, false, false, false));
			place(level, beyondOne, Blocks.GOLD_ORE);
			expectAgreement(helper, level, pods, "ore past tier 1's reach", List.of(false, false, true, false));
			place(level, near, Blocks.GOLD_ORE);
			expectAgreement(helper, level, pods, "ore in reach", List.of(false, true, true, false));
			level.setBlock(beyondOne, Blocks.AIR.defaultBlockState(), 2);
			level.setBlock(near, Blocks.AIR.defaultBlockState(), 2);
			expectAgreement(helper, level, pods, "the ore removed", List.of(false, false, false, false));
			helper.succeed();
		} finally {
			level.setBlock(beyondOne, Blocks.AIR.defaultBlockState(), 2);
			level.setBlock(near, Blocks.AIR.defaultBlockState(), 2);
			none.discard();
			one.discard();
			two.discard();
			foreign.discard();
		}
	}

	/** Each pod's {@code hasOre} is what {@code expected} says, and is what a walk over its {@code scan} finds. */
	private static void expectAgreement(GameTestHelper helper, ServerLevel level, List<PodEntity> pods, String when, List<Boolean> expected) {
		for (int index = 0; index < pods.size(); index++) {
			PodEntity pod = pods.get(index);
			boolean scanned = ScanSlice.scan(level, pod).map(ScannerTiersTest::holdsOre).orElse(false);
			boolean quick = ScanSlice.hasOre(level, pod);
			if (quick != scanned || quick != expected.get(index)) {
				throw failure(helper, "%s: pod %d hasOre was %s, the scan holds ore: %s, expected %s", when, index, quick, scanned, expected.get(index));
			}
		}
	}

	private static boolean holdsOre(ScanSlice slice) {
		ScanArea area = slice.area();
		for (int ahead = -area.halfWidth(); ahead <= area.halfWidth(); ahead++) {
			for (int up = -area.down(); up <= area.up(); up++) {
				if (slice.cell(ahead, up) instanceof Cell.Ore) {
					return true;
				}
			}
		}
		return false;
	}

	@GameTest
	public void spawnCommandFitsATierOneScannerForAPlayerOnACharter(GameTestHelper helper) {
		MockPlayer member = MockPlayers.join(helper, "scanner-member");
		MockPlayer loner = MockPlayers.join(helper, "scanner-loner");
		List<PodEntity> spawned = new ArrayList<>();
		try {
			MinecraftServer server = helper.getLevel().getServer();
			if (Charters.found(server, member.player().getUUID(), "Spawn scanner " + CHARTERS.incrementAndGet()).isPresent()) {
				throw failure(helper, "could not found a charter");
			}
			CharterId charter = Charters.charterOfOrThrow(server, member.player().getUUID()).orElseThrow().id();
			PodEntity fitted = spawnBy(helper, member, spawned);
			PodEntity bare = spawnBy(helper, loner, spawned);
			if (!PodComponents.registration(fitted).map(PodComponents.Registration::owner).equals(Optional.of(charter))
					|| PodComponents.effectiveTier(fitted, ComponentTrack.SCANNER) != 1) {
				throw failure(helper, "a spawned pod should belong to the member's charter and scan at tier 1, owner %s tier %d",
						PodComponents.registration(fitted), PodComponents.effectiveTier(fitted, ComponentTrack.SCANNER));
			}
			if (PodComponents.registration(bare).isPresent() || PodComponents.effectiveTier(bare, ComponentTrack.SCANNER) != 0) {
				throw failure(helper, "a pod spawned by a player with no charter stays bare, owner %s", PodComponents.registration(bare));
			}
			helper.succeed();
		} finally {
			spawned.forEach(Entity::discard);
			member.leave();
			loner.leave();
		}
	}

	private static PodEntity spawnBy(GameTestHelper helper, MockPlayer player, List<PodEntity> spawned) {
		Vec3 at = helper.absoluteVec(new Vec3(2, 2, 2));
		player.player().setPos(at);
		try {
			helper.getLevel().getServer().getCommands().getDispatcher().execute("deepcharter pod spawn",
					player.player().createCommandSourceStack().withPermission(LevelBasedPermissionSet.GAMEMASTER));
		} catch (CommandSyntaxException e) {
			throw failure(helper, "spawn should run for an op: %s", e.getMessage());
		}
		PodEntity pod = helper.getLevel().getEntities(PodRegistry.POD, new AABB(at, at).inflate(1), found -> !spawned.contains(found)).getFirst();
		spawned.add(pod);
		return pod;
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
		return Charters.charterOfOrThrow(helper.getLevel().getServer(), founder).orElseThrow().id();
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
