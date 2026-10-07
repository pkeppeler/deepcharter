package io.github.pkeppeler.deepcharter.test;

import java.util.List;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import io.github.pkeppeler.deepcharter.layer.Depth;
import io.github.pkeppeler.deepcharter.layer.LayerBlocks;
import io.github.pkeppeler.deepcharter.layer.LayerChain;
import io.github.pkeppeler.deepcharter.pod.PodCargo;
import io.github.pkeppeler.deepcharter.pod.PodDrill;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.pod.PodTuning;
import io.github.pkeppeler.deepcharter.test.support.MockPlayer;
import io.github.pkeppeler.deepcharter.test.support.MockPlayers;

/**
 * Server GameTests for pod drilling, driven by a mock pilot's input in layer_1 (and layer_2, for the
 * last crust). Each test has its own X, so the blocks it changes never touch another test's.
 * Layer 1 is crust at y 0-2, stone at y 3-102, then air up to the ceiling row at y 191.
 */
public class PodDrillTest {
	/**
	 * Far layer chunks generate on worker threads while game ticks run as fast as the CPU allows, so on a slow
	 * runner a pod can sit un-ticked for thousands of ticks. Anything about what a pod does is therefore timed in
	 * the pod's own {@code tickCount}, and the longest bore here is under 700 pod ticks: a test that passes
	 * ends at once, so a large budget costs nothing.
	 */
	private static final int MAX_TICKS = 20000;
	private static final int Z = 3000;
	private static final float EAST = -90f;

	private static final Input SPRINT = new Input(false, false, false, false, false, false, true);
	private static final Input FORWARD = new Input(true, false, false, false, false, false, false);

	/** A pod with a mock pilot seated in it, in {@code level}. */
	private record Rig(PodEntity pod, MockPlayer pilot) {
		static Rig build(GameTestHelper helper, ServerLevel level, Vec3 at, float yaw, String name) {
			MockPlayer pilot = MockPlayers.join(helper, name);
			pilot.teleportTo(level, at, yaw, 0f);
			PodEntity pod = PodRegistry.POD.create(level, EntitySpawnReason.COMMAND);
			pod.setPos(at);
			level.addFreshEntity(pod);
			if (!pilot.player().startRiding(pod)) {
				throw failure(helper, "the pilot could not mount the pod");
			}
			return new Rig(pod, pilot);
		}
	}

	@GameTest(maxTicks = MAX_TICKS)
	public void aDownwardBoreIsExactlyTwoByTwoByN(GameTestHelper helper) {
		int x = 3000;
		int floor = 60;
		int n = 3;
		ServerLevel level = layer(helper, 1);
		room(level, x, floor, 4);
		// Off the block grid on purpose: the pod must centre itself, and bore the nearest 2 x 2.
		Rig rig = Rig.build(helper, level, new Vec3(x + 0.3, floor, Z + 0.8), 0f, "drill-bore");
		rig.pilot.setInput(SPRINT);
		boolean[] released = {false};
		helper.onEachTick(() -> {
			if (!released[0] && count(level, x - 1, x, floor - n, floor - 1, Z, Z + 1, Blocks.AIR) == 4 * n) {
				rig.pilot.releaseInput();
				released[0] = true;
			}
			// Landed: the pod has sunk into the bore and rests on its floor.
			if (released[0] && rig.pod.onGround() && rig.pod.getY() < floor - n + 0.1) {
				// Bore x in {x-1, x}, z in {Z, Z+1}, y in {floor-n .. floor-1}: nothing else may have changed.
				for (int bx = x - 4; bx <= x + 3; bx++) {
					for (int bz = Z - 4; bz <= Z + 4; bz++) {
						for (int by = floor - n - 1; by < floor; by++) {
							boolean inBore = bx >= x - 1 && bx <= x && bz >= Z && bz <= Z + 1 && by >= floor - n;
							boolean air = level.getBlockState(new BlockPos(bx, by, bz)).isAir();
							if (air != inBore) {
								throw failure(helper, "block (%d,%d,%d) is %s, expected %s", bx, by, bz,
										air ? "air" : "solid", inBore ? "air" : "solid");
							}
						}
					}
				}
				if (Math.abs(rig.pod.getX() - x) > 0.01 || Math.abs(rig.pod.getZ() - (Z + 1)) > 0.01) {
					throw failure(helper, "the pod should end centred in its bore at (%d, %d), it is at %s", x, Z + 1, rig.pod.position());
				}
				rig.pod.discard();
				helper.succeed();
			}
		});
	}

	@GameTest(maxTicks = MAX_TICKS)
	public void drillingSidewaysNeedsGroundUnderThePod(GameTestHelper helper) {
		int x = 3064;
		int floor = 60;
		ServerLevel level = layer(helper, 1);
		room(level, x, floor, 4);
		wall(level, x + 2, x + 5, Z, floor, floor + 10);
		// A wall of stone two blocks east, and a pod that starts four blocks up: it meets the wall while falling.
		Rig rig = Rig.build(helper, level, new Vec3(x + 0.3, floor + 4, Z + 0.3), EAST, "drill-ground");
		rig.pilot.setInput(FORWARD);
		int[] airborneAgainstWall = {0};
		helper.onEachTick(() -> {
			PodEntity pod = rig.pod;
			if (!pod.onGround() && pod.horizontalCollision) {
				airborneAgainstWall[0]++;
				if (pod.drilling()) {
					throw failure(helper, "the pod is drilling sideways while airborne at %s", pod.position());
				}
				if (count(level, x + 2, x + 2, floor, floor + 10, Z - 3, Z + 3, Blocks.AIR) != 0) {
					throw failure(helper, "the wall was broken while the pod was airborne at %s", pod.position());
				}
			}
			// The slab it bores: x+2, z in {Z-1, Z}, y in {floor, floor+1}.
			if (count(level, x + 2, x + 2, floor, floor + 1, Z - 1, Z, Blocks.AIR) == 4) {
				if (airborneAgainstWall[0] == 0) {
					throw failure(helper, "the pod never pressed against the wall in the air, so the test proved nothing");
				}
				if (count(level, x + 2, x + 2, floor, floor + 10, Z - 3, Z + 3, Blocks.AIR) != 4
						|| count(level, x + 3, x + 5, floor, floor + 10, Z - 3, Z + 3, Blocks.AIR) != 0) {
					throw failure(helper, "the sideways bore is not exactly the 2 x 2 in front of the pod");
				}
				rig.pod.discard();
				helper.succeed();
			}
		});
	}

	@GameTest(maxTicks = MAX_TICKS)
	public void aWallUnderTheCeilingIsNeverBored(GameTestHelper helper) {
		ServerLevel level = layer(helper, 1);
		int ceiling = level.getMaxY();
		int x = 3128;
		int floor = ceiling - 1;
		room(level, x, floor, 4);
		wall(level, x + 2, x + 3, Z, floor, ceiling);
		Rig rig = Rig.build(helper, level, new Vec3(x + 0.3, floor, Z + 0.3), EAST, "drill-ceiling");
		rig.pilot.setInput(FORWARD);
		helper.onEachTick(() -> {
			if (rig.pod.drilling()) {
				throw failure(helper, "the pod started drilling a slab that reaches the ceiling row");
			}
			if (rig.pod.tickCount < 150) {
				return;
			}
			if (!rig.pod.horizontalCollision) {
				throw failure(helper, "the pod is not pressing against the wall, so the test proved nothing: %s", rig.pod.position());
			}
			if (count(level, x + 2, x + 3, floor, ceiling, Z - 3, Z + 3, Blocks.AIR) != 0) {
				throw failure(helper, "a block at or under the ceiling was broken");
			}
			rig.pod.discard();
			helper.succeed();
		});
	}

	/** The control for the test above: the same wall one row lower is bored, so the ceiling rule is not a blanket refusal. */
	@GameTest(maxTicks = MAX_TICKS)
	public void aWallOneRowBelowTheCeilingIsBored(GameTestHelper helper) {
		ServerLevel level = layer(helper, 1);
		int top = level.getMaxY() - 1;
		int x = 3192;
		int floor = top - 1;
		room(level, x, floor, 4);
		wall(level, x + 2, x + 3, Z, floor, top);
		Rig rig = Rig.build(helper, level, new Vec3(x + 0.3, floor, Z + 0.3), EAST, "drill-below-ceiling");
		rig.pilot.setInput(FORWARD);
		helper.succeedWhen(() -> {
			if (count(level, x + 2, x + 2, floor, top, Z - 1, Z, Blocks.AIR) != 4) {
				throw failure(helper, "the wall one row below the ceiling was not bored");
			}
			rig.pod.discard();
		});
	}

	@GameTest(maxTicks = MAX_TICKS)
	public void drillingIsSlowerDeeperDown(GameTestHelper helper) {
		ServerLevel level = layer(helper, 1);
		int shallowFloor = 150;
		int deepFloor = 20;
		int shallowX = 3256;
		int deepX = 3320;
		stoneBed(level, shallowX, shallowFloor);
		stoneBed(level, deepX, deepFloor);
		Rig shallow = Rig.build(helper, level, new Vec3(shallowX, shallowFloor, Z), 0f, "drill-shallow");
		Rig deep = Rig.build(helper, level, new Vec3(deepX, deepFloor, Z), 0f, "drill-deep");
		shallow.pilot.setInput(SPRINT);
		deep.pilot.setInput(SPRINT);
		int[] shallowTicks = {-1};
		int[] deepTicks = {-1};
		helper.onEachTick(() -> {
			if (shallowTicks[0] < 0 && level.getBlockState(new BlockPos(shallowX, shallowFloor - 1, Z)).isAir()) {
				shallowTicks[0] = shallow.pod.tickCount;
				shallow.pilot.releaseInput();
			}
			if (deepTicks[0] < 0 && level.getBlockState(new BlockPos(deepX, deepFloor - 1, Z)).isAir()) {
				deepTicks[0] = deep.pod.tickCount;
				deep.pilot.releaseInput();
			}
			if (shallowTicks[0] < 0 || deepTicks[0] < 0) {
				return;
			}
			if (deepTicks[0] <= shallowTicks[0]) {
				throw failure(helper, "drilling should be slower deeper down: %d ticks at y=%d, %d ticks at y=%d",
						shallowTicks[0], shallowFloor, deepTicks[0], deepFloor);
			}
			expectNear(helper, shallowTicks[0], expectedTicks(level, shallowFloor - 1), "shallow");
			expectNear(helper, deepTicks[0], expectedTicks(level, deepFloor - 1), "deep");
			shallow.pod.discard();
			deep.pod.discard();
			helper.succeed();
		});
	}

	@GameTest
	public void drillTimeIsHardnessTimesDepthFactor(GameTestHelper helper) {
		// Stone is 1.5: the original's 1.2 s a tile at the surface, doubling at 1,000 ft.
		expectTicks(helper, PodDrill.drillTicks(1.5f, 0), 24);
		expectTicks(helper, PodDrill.drillTicks(1.5f, 1000), 48);
		expectTicks(helper, PodDrill.drillTicks(1.5f, 3000), 96);
		// Above sea level the depth is negative: the drill gets no faster for it.
		expectTicks(helper, PodDrill.drillTicks(1.5f, -500), 24);
		// Crust is hardness 5: slower than stone at the same depth.
		if (PodDrill.drillTicks(5f, 1000) <= PodDrill.drillTicks(1.5f, 1000)) {
			throw failure(helper, "crust must drill slower than stone");
		}
		helper.succeed();
	}

	@GameTest(maxTicks = MAX_TICKS)
	public void oreGoesToCargoAndStoneIsDestroyed(GameTestHelper helper) {
		int x = 3384;
		int floor = 60;
		ServerLevel level = layer(helper, 1);
		room(level, x, floor, 4);
		level.setBlock(new BlockPos(x - 1, floor - 1, Z - 1), Blocks.IRON_ORE.defaultBlockState(), 3);
		level.setBlock(new BlockPos(x, floor - 1, Z), Blocks.DIAMOND_ORE.defaultBlockState(), 3);
		Rig rig = Rig.build(helper, level, new Vec3(x, floor, Z), 0f, "drill-ore");
		rig.pilot.setInput(SPRINT);
		helper.onEachTick(() -> {
			if (count(level, x - 1, x, floor - 1, floor - 1, Z - 1, Z, Blocks.AIR) != 4) {
				return;
			}
			rig.pilot.releaseInput();
			List<Block> kept = rig.pod.cargo().entries().stream().map(PodCargo.Entry::ore).toList();
			if (kept.size() != 2 || !kept.contains(Blocks.IRON_ORE) || !kept.contains(Blocks.DIAMOND_ORE)) {
				throw failure(helper, "cargo should hold exactly the iron and the diamond ore, it holds %s", kept);
			}
			if (rig.pod.cargoUsed() != 2) {
				throw failure(helper, "the synced cargo count is %d, expected 2", rig.pod.cargoUsed());
			}
			rig.pod.discard();
			helper.succeed();
		});
	}

	@GameTest(maxTicks = MAX_TICKS)
	public void aFullBayLosesTheOreAndDrillingGoesOn(GameTestHelper helper) {
		int x = 3448;
		int floor = 60;
		ServerLevel level = layer(helper, 1);
		room(level, x, floor, 4);
		level.setBlock(new BlockPos(x, floor - 1, Z), Blocks.DIAMOND_ORE.defaultBlockState(), 3);
		Rig rig = Rig.build(helper, level, new Vec3(x, floor, Z), 0f, "drill-full");
		int slots = PodTuning.DEFAULT.cargo().slots();
		for (int i = 0; i < slots; i++) {
			if (!rig.pod.cargo().tryAdd(rig.pod, Blocks.COAL_ORE)) {
				throw failure(helper, "could not fill slot %d of %d", i, slots);
			}
		}
		rig.pilot.setInput(SPRINT);
		helper.onEachTick(() -> {
			if (count(level, x - 1, x, floor - 1, floor - 1, Z - 1, Z, Blocks.AIR) != 4) {
				return;
			}
			rig.pilot.releaseInput();
			List<Block> kept = rig.pod.cargo().entries().stream().map(PodCargo.Entry::ore).toList();
			if (rig.pod.cargoUsed() != slots || kept.size() != slots || kept.contains(Blocks.DIAMOND_ORE)) {
				throw failure(helper, "a full bay must keep its %d coal and lose the diamond, it holds %s", slots, kept);
			}
			rig.pod.discard();
			helper.succeed();
		});
	}

	/** The hitbox straddles three columns but the bore is two wide: a pod held up by the third column alone must still get on with it. */
	@GameTest(maxTicks = MAX_TICKS)
	public void aPodOnALedgeInTheThirdColumnCentresAndDrillsDown(GameTestHelper helper) {
		int x = 3640;
		int floor = 60;
		ServerLevel level = layer(helper, 1);
		room(level, x, floor, 4);
		// Only column x+1 reaches the pod; under columns x-1 and x the ground is three blocks lower.
		box(level, x - 1, x, floor - 3, floor - 1, Z - 4, Z + 4, Blocks.AIR);
		Rig rig = Rig.build(helper, level, new Vec3(x + 0.3, floor, Z), 0f, "drill-ledge");
		rig.pilot.setInput(SPRINT);
		helper.succeedWhen(() -> {
			if (count(level, x - 1, x, floor - 4, floor - 4, Z - 1, Z, Blocks.AIR) != 4) {
				throw failure(helper, "the pod on the ledge did not bore the 2 x 2 below it, it is at %s", rig.pod.position());
			}
			if (count(level, x + 1, x + 1, floor - 1, floor - 1, Z - 1, Z, Blocks.STONE) != 2) {
				throw failure(helper, "the bore took the ledge column too");
			}
			rig.pilot.releaseInput();
			rig.pod.discard();
		});
	}

	@GameTest(maxTicks = MAX_TICKS)
	public void aPodAgainstAWallInTheThirdColumnCentresAndDrillsSideways(GameTestHelper helper) {
		int x = 3704;
		int floor = 60;
		ServerLevel level = layer(helper, 1);
		room(level, x, floor, 4);
		// A one-column wall the pod's hitbox touches but its 2 x 2 bore does not, then a full wall behind it.
		box(level, x + 2, x + 2, floor, floor + 10, Z + 1, Z + 1, Blocks.STONE);
		wall(level, x + 4, x + 5, Z, floor, floor + 10);
		Rig rig = Rig.build(helper, level, new Vec3(x + 0.3, floor, Z + 0.3), EAST, "drill-edge");
		rig.pilot.setInput(FORWARD);
		helper.succeedWhen(() -> {
			if (count(level, x + 4, x + 4, floor, floor + 1, Z - 1, Z, Blocks.AIR) != 4) {
				throw failure(helper, "the pod did not bore the full wall behind the one-column wall, it is at %s", rig.pod.position());
			}
			if (!level.getBlockState(new BlockPos(x + 2, floor, Z + 1)).is(Blocks.STONE)
					|| !level.getBlockState(new BlockPos(x + 4, floor, Z + 1)).is(Blocks.STONE)) {
				throw failure(helper, "the bore reached a column outside the pod's 2 x 2");
			}
			rig.pilot.releaseInput();
			rig.pod.discard();
		});
	}

	@GameTest(maxTicks = MAX_TICKS)
	public void theLastCrustRowCrossesThePodAndItsPilotIntoTheNextLayer(GameTestHelper helper) {
		int x = 3512;
		ServerLevel one = layer(helper, 1);
		// Whatever an earlier run left, three crust rows; then clear the top two and the stone above in a 5 x 5, so one is left under the pod.
		box(one, x - 2, x + 2, 0, 2, Z - 2, Z + 2, LayerBlocks.BREACH_CRUST);
		box(one, x - 2, x + 2, 1, 8, Z - 2, Z + 2, Blocks.AIR);
		Rig rig = Rig.build(helper, one, new Vec3(x, 1, Z), 0f, "drill-crust");
		float hullBefore = rig.pod.hull();
		rig.pilot.setInput(SPRINT);
		helper.onEachTick(() -> {
			if (!rig.pilot.player().level().dimension().equals(LayerChain.dimension(2))) {
				return;
			}
			rig.pilot.releaseInput();
			if (!(rig.pilot.player().getVehicle() instanceof PodEntity crossed) || crossed.level() != rig.pilot.player().level()) {
				throw failure(helper, "the pilot crossed without the pod: riding %s", rig.pilot.player().getVehicle());
			}
			float expectedHull = hullBefore - 8f;
			if (Math.abs(crossed.hull() - expectedHull) > 0.01f) {
				throw failure(helper, "boring one crust row should cost 8 hull, the pod has %s of %s",
						crossed.hull(), hullBefore);
			}
			if (Math.abs(crossed.getX() - x) > 1 || Math.abs(crossed.getZ() - Z) > 1) {
				throw failure(helper, "the pod arrived at %s, expected near (%d, %d)", crossed.position(), x, Z);
			}
			if (count(one, x - 1, x, 0, 0, Z - 1, Z, Blocks.AIR) != 4) {
				throw failure(helper, "the crust under the pod was not broken");
			}
			if (!one.getBlockState(new BlockPos(x + 1, 0, Z)).is(LayerBlocks.BREACH_CRUST)) {
				throw failure(helper, "the bore took crust outside the pod's 2 x 2");
			}
			crossed.discard();
			helper.succeed();
		});
	}

	@GameTest(maxTicks = MAX_TICKS)
	public void theLastLayersCrustIsNotDrilled(GameTestHelper helper) {
		int x = 3576;
		ServerLevel two = layer(helper, 2);
		box(two, x - 2, x + 2, 0, 2, Z - 2, Z + 2, LayerBlocks.BREACH_CRUST);
		box(two, x - 2, x + 2, 3, 10, Z - 2, Z + 2, Blocks.AIR);
		Rig rig = Rig.build(helper, two, new Vec3(x, 3, Z), 0f, "drill-last-crust");
		rig.pilot.setInput(SPRINT);
		helper.onEachTick(() -> {
			if (rig.pod.drilling()) {
				throw failure(helper, "the pod drills the floor of the last layer, which leads nowhere");
			}
			if (rig.pod.tickCount < 100) {
				return;
			}
			if (!rig.pod.onGround() || !two.getBlockState(new BlockPos(x, 2, Z)).is(LayerBlocks.BREACH_CRUST)) {
				throw failure(helper, "the pod should be standing on intact crust, it is at %s", rig.pod.position());
			}
			rig.pod.discard();
			helper.succeed();
		});
	}

	private static int expectedTicks(ServerLevel level, int y) {
		return PodDrill.drillTicks(Blocks.STONE.defaultBlockState().getDestroySpeed(level, BlockPos.ZERO), Depth.feet(Depth.of(level, y)));
	}

	private static void expectNear(GameTestHelper helper, int actual, int expected, String label) {
		if (Math.abs(actual - expected) > 3) {
			throw failure(helper, "%s bore took %d ticks, expected about %d", label, actual, expected);
		}
	}

	private static void expectTicks(GameTestHelper helper, int actual, int expected) {
		if (actual != expected) {
			throw failure(helper, "drill time is %d ticks, expected %d", actual, expected);
		}
	}

	/** Stone up to and including y=floor-1 under a 9 x 9 around (x, Z), and air for 10 blocks above it. */
	private static void room(ServerLevel level, int x, int floor, int radius) {
		box(level, x - radius, x + radius + 1, floor - 8, floor - 1, Z - radius, Z + radius, Blocks.STONE);
		box(level, x - radius, x + radius + 1, floor, floor + 10, Z - radius, Z + radius, Blocks.AIR);
	}

	/** Only the stone bed, for pods that drill straight down in open air. */
	private static void stoneBed(ServerLevel level, int x, int floor) {
		box(level, x - 4, x + 3, floor - 8, floor - 1, Z - 4, Z + 3, Blocks.STONE);
		box(level, x - 4, x + 3, floor, floor + 10, Z - 4, Z + 3, Blocks.AIR);
	}

	/** A stone wall across the room, from xFrom to xTo, floor to top, z within three of {@code z}. */
	private static void wall(ServerLevel level, int xFrom, int xTo, int z, int floor, int top) {
		box(level, xFrom, xTo, floor, top, z - 3, z + 3, Blocks.STONE);
	}

	private static void box(ServerLevel level, int x1, int x2, int y1, int y2, int z1, int z2, Block block) {
		for (int x = x1; x <= x2; x++) {
			for (int y = y1; y <= y2; y++) {
				for (int z = z1; z <= z2; z++) {
					level.setBlock(new BlockPos(x, y, z), block.defaultBlockState(), 2);
				}
			}
		}
	}

	private static int count(ServerLevel level, int x1, int x2, int y1, int y2, int z1, int z2, Block block) {
		int found = 0;
		for (int x = x1; x <= x2; x++) {
			for (int y = y1; y <= y2; y++) {
				for (int z = z1; z <= z2; z++) {
					if (level.getBlockState(new BlockPos(x, y, z)).is(block)) {
						found++;
					}
				}
			}
		}
		return found;
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
