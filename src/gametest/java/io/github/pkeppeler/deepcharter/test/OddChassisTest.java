package io.github.pkeppeler.deepcharter.test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import io.github.pkeppeler.deepcharter.hangar.Hangar;
import io.github.pkeppeler.deepcharter.hangar.HangarTuning;
import io.github.pkeppeler.deepcharter.layer.LayerChain;
import io.github.pkeppeler.deepcharter.ore.HazardBlocks;
import io.github.pkeppeler.deepcharter.ore.SlagBrick;
import io.github.pkeppeler.deepcharter.pod.Chassis;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodLining;
import io.github.pkeppeler.deepcharter.pod.PodSounder;
import io.github.pkeppeler.deepcharter.pod.PodStats;
import io.github.pkeppeler.deepcharter.pod.PodTowing;
import io.github.pkeppeler.deepcharter.pod.PodTuning;
import io.github.pkeppeler.deepcharter.scanner.ScanSlice;
import io.github.pkeppeler.deepcharter.scanner.ScanSlice.Cell;
import io.github.pkeppeler.deepcharter.test.support.EarlyRunModel;
import io.github.pkeppeler.deepcharter.test.support.EarlyRunModel.Zone;
import io.github.pkeppeler.deepcharter.test.support.FarChunks;
import io.github.pkeppeler.deepcharter.test.support.MockPlayer;
import io.github.pkeppeler.deepcharter.test.support.MockPlayers;
import io.github.pkeppeler.deepcharter.test.support.OddPods;
import io.github.pkeppeler.deepcharter.test.support.RoomCarver;
import io.github.pkeppeler.deepcharter.test.support.ScannerPods;
import io.github.pkeppeler.deepcharter.upgrade.ComponentTrack;
import io.github.pkeppeler.deepcharter.wreck.Wrecks;

/**
 * Server GameTests for #399: nothing about a pod's size may be assumed, so every size-dependent behaviour runs on {@link OddPods}, a
 * chassis 3.9 wide and 2.9 tall (a 4-wide, 3-tall bore). Each expectation is written from the chassis ({@link Chassis#boreWidth},
 * {@link Chassis#boreHeight}), never as a literal, and the Mole's and Prospector's own tests stay as they were.
 *
 * <p>Each test builds its own site in layer 1, in its own range of X, so the blocks it changes never touch another test's.
 */
public class OddChassisTest {
	private static final Chassis ODD = OddPods.CHASSIS;
	private static final int WIDTH = ODD.boreWidth();
	private static final int HEIGHT = ODD.boreHeight();
	private static final AtomicInteger OWNERS = new AtomicInteger();
	private static final int Z = 9000;
	private static final int FLOOR = 60;
	private static final int RADIUS = 7;
	private static final int ROOM_HEIGHT = 16;
	private static final int TICKS = FarChunks.AWAIT_BUDGET_TICKS + 1500;
	private static final Input SPRINT = new Input(false, false, false, false, false, false, true);
	private static final Input FORWARD = new Input(true, false, false, false, false, false, false);
	private static final float EAST = -90f;

	private static RuntimeException failure(GameTestHelper helper, String format, Object... args) {
		return helper.assertionException(Component.literal(String.format(format, args)));
	}

	private static void expect(GameTestHelper helper, boolean condition, String format, Object... args) {
		if (!condition) {
			throw failure(helper, format, args);
		}
	}

	private static ServerLevel layer(GameTestHelper helper) {
		ServerLevel level = helper.getLevel().getServer().getLevel(LayerChain.dimension(1));
		if (level == null) {
			throw failure(helper, "dimension %s did not load", LayerChain.dimension(1));
		}
		return level;
	}

	/** A bed of stone with an open room above it, {@link #RADIUS} blocks round {@code x}, {@link #Z}. */
	private static void site(ServerLevel level, int x) {
		RoomCarver.carve(level, x - RADIUS, x + RADIUS, FLOOR - 12, FLOOR - 1, Z - RADIUS, Z + RADIUS, Blocks.STONE);
		RoomCarver.carve(level, x - RADIUS, x + RADIUS, FLOOR, FLOOR + ROOM_HEIGHT, Z - RADIUS, Z + RADIUS, Blocks.AIR);
	}

	private static PodEntity spawn(ServerLevel level, Vec3 at) {
		PodEntity pod = OddPods.TYPE.create(level, EntitySpawnReason.COMMAND);
		pod.setPos(at);
		level.addFreshEntity(pod);
		return pod;
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

	/** Builds the site, then spawns an odd pod on its floor, centred on {@code x}, {@link #Z} (a whole-block centre, the middle of its bore), and runs {@code body} once the chunk ticks. */
	private static void onSite(GameTestHelper helper, int x, Consumer<PodEntity> body) {
		ServerLevel level = layer(helper);
		site(level, x);
		FarChunks.awaitEntityTicking(helper, level, new BlockPos(x, FLOOR, Z), () -> body.accept(spawn(level, new Vec3(x, FLOOR, Z))));
	}

	// ---- bores ----

	@GameTest(maxTicks = TICKS)
	public void anOddPodBoresDownTheWholeOfItsFootprintAndNothingMore(GameTestHelper helper) {
		int x = 9000;
		int n = 3;
		ServerLevel level = layer(helper);
		site(level, x);
		MockPlayer pilot = MockPlayers.join(helper, "odd-bore-down");
		PodEntity[] pod = {null};
		// Off the block grid on purpose: the pod must centre itself in its bore.
		Vec3 start = new Vec3(x + 0.3, FLOOR, Z + 0.8);
		pilot.teleportTo(level, start, 0f, 0f);
		FarChunks.awaitEntityTicking(helper, level, BlockPos.containing(start), () -> {
			pod[0] = spawn(level, start);
			expect(helper, pilot.player().startRiding(pod[0]), "the pilot could not mount the pod");
			pilot.setInput(SPRINT);
		});
		// The bore: the WIDTH x WIDTH square the pod centres on, x - WIDTH / 2 .. x + WIDTH / 2 - 1, and z + 1 as its middle.
		int lowX = x - WIDTH / 2;
		int lowZ = Z + 1 - WIDTH / 2;
		boolean[] released = {false};
		helper.onEachTick(() -> {
			if (pod[0] == null) {
				return;
			}
			if (!released[0] && count(level, lowX, lowX + WIDTH - 1, FLOOR - n, FLOOR - 1, lowZ, lowZ + WIDTH - 1, Blocks.AIR) == WIDTH * WIDTH * n) {
				pilot.releaseInput();
				released[0] = true;
			}
			if (released[0] && pod[0].onGround() && pod[0].getY() < FLOOR - n + 0.1) {
				for (int bx = x - RADIUS + 1; bx <= x + RADIUS - 1; bx++) {
					for (int bz = Z - RADIUS + 1; bz <= Z + RADIUS - 1; bz++) {
						for (int by = FLOOR - n - 1; by < FLOOR; by++) {
							boolean inBore = bx >= lowX && bx < lowX + WIDTH && bz >= lowZ && bz < lowZ + WIDTH && by >= FLOOR - n;
							expect(helper, level.getBlockState(new BlockPos(bx, by, bz)).isAir() == inBore, "block (%d,%d,%d) should be %s", bx, by, bz, inBore ? "air" : "solid");
						}
					}
				}
				expect(helper, Math.abs(pod[0].getX() - x) < 0.01 && Math.abs(pod[0].getZ() - (Z + 1)) < 0.01, "the pod should end centred in its bore at (%d, %d), it is at %s", x, Z + 1, pod[0].position());
				pod[0].discard();
				helper.succeed();
			}
		});
	}

	@GameTest(maxTicks = TICKS)
	public void anOddPodBoresSidewaysTheWholeOfItsHeightAndWidth(GameTestHelper helper) {
		int x = 9048;
		ServerLevel level = layer(helper);
		site(level, x);
		// A wall of stone the pod meets: it stops when its east face touches it, then centres on its bore.
		int wallX = x + 4;
		RoomCarver.carve(level, wallX, wallX + 3, FLOOR, FLOOR + ROOM_HEIGHT, Z - RADIUS, Z + RADIUS, Blocks.STONE);
		MockPlayer pilot = MockPlayers.join(helper, "odd-bore-side");
		Vec3 start = new Vec3(x + 0.3, FLOOR, Z + 0.3);
		pilot.teleportTo(level, start, EAST, 0f);
		PodEntity[] pod = {null};
		FarChunks.awaitEntityTicking(helper, level, BlockPos.containing(start), () -> {
			pod[0] = spawn(level, start);
			expect(helper, pilot.player().startRiding(pod[0]), "the pilot could not mount the pod");
			pilot.setInput(FORWARD);
		});
		helper.onEachTick(() -> {
			if (pod[0] == null) {
				return;
			}
			// The slab it bores: the wall's first column, WIDTH wide round the pod's middle and HEIGHT tall from its feet.
			if (count(level, wallX, wallX, FLOOR, FLOOR + HEIGHT - 1, Z - RADIUS, Z + RADIUS, Blocks.AIR) == WIDTH * HEIGHT) {
				expect(helper, count(level, wallX, wallX, FLOOR + HEIGHT, FLOOR + ROOM_HEIGHT, Z - RADIUS, Z + RADIUS, Blocks.AIR) == 0, "the bore is taller than the pod");
				expect(helper, count(level, wallX + 1, wallX + 3, FLOOR, FLOOR + ROOM_HEIGHT, Z - RADIUS, Z + RADIUS, Blocks.AIR) == 0, "the bore went deeper than one slab");
				pod[0].discard();
				helper.succeed();
			}
		});
	}

	// ---- lining ----

	@GameTest(maxTicks = TICKS + 1500)
	public void anOddPodLinesTheRingOfItsWholeFootprintAndBoxHeight(GameTestHelper helper) {
		int x = 9096;
		ServerLevel level = layer(helper);
		site(level, x);
		MockPlayer pilot = MockPlayers.join(helper, "odd-lining");
		pilot.player().setGameMode(GameType.SURVIVAL);
		Vec3 start = new Vec3(x, FLOOR, Z);
		pilot.teleportTo(level, start, 0f, 0f);
		PodEntity[] pod = {null};
		int ring = 4 * WIDTH * HEIGHT;
		FarChunks.awaitEntityTicking(helper, level, BlockPos.containing(start), () -> {
			pod[0] = spawn(level, start);
			expect(helper, pilot.player().startRiding(pod[0]), "the pilot could not mount the pod");
			pilot.player().getInventory().add(new ItemStack(SlagBrick.item(), ring));
		});
		boolean[] begun = {false};
		helper.onEachTick(() -> {
			if (pod[0] == null || !pod[0].onGround()) {
				return;
			}
			if (!begun[0]) {
				// The floor is stone, so the ring is the four sides of the footprint, for the pod's whole height.
				int cells = PodLining.cellsToLine(pod[0]).size();
				expect(helper, cells == ring, "the ring of a %d x %d footprint, %d tall, is %d cells, the plan holds %d", WIDTH, WIDTH, HEIGHT, ring, cells);
				PodLining.toggle(pilot.player());
				expect(helper, PodLining.working(pod[0]), "the pilot should be lining");
				begun[0] = true;
				return;
			}
			if (!PodLining.working(pod[0])) {
				expect(helper, count(level, x - WIDTH / 2 - 1, x + WIDTH / 2, FLOOR, FLOOR + HEIGHT - 1, Z - WIDTH / 2 - 1, Z + WIDTH / 2, SlagBrick.BLOCK) == ring,
						"the ring should be %d bricks", ring);
				expect(helper, PodLining.cellsToLine(pod[0]).isEmpty(), "nothing is left to line");
				pod[0].discard();
				helper.succeed();
			}
		});
	}

	// ---- sounding ----

	private static void sounds(GameTestHelper helper, int x, int tier, Consumer<ServerLevel> build, Consumer<PodEntity> check) {
		ServerLevel level = layer(helper);
		site(level, x);
		build.accept(level);
		MockPlayer owner = MockPlayers.join(helper, "odd-sounder-" + OWNERS.incrementAndGet());
		owner.player().setGameMode(GameType.SURVIVAL);
		PodEntity[] pod = {null};
		int[] ticks = {0};
		FarChunks.awaitEntityTicking(helper, level, new BlockPos(x, FLOOR, Z), () -> {
			pod[0] = spawn(level, new Vec3(x, FLOOR, Z));
			ScannerPods.fit(helper.getLevel().getServer(), owner.player(), pod[0], ComponentTrack.SOUNDER, tier);
		});
		helper.onEachTick(() -> {
			if (pod[0] != null && ++ticks[0] == 6) {
				check.accept(pod[0]);
				pod[0].discard();
				helper.succeed();
			}
		});
	}

	private static void pocket(ServerLevel level, int x, int y, int z) {
		level.setBlock(new BlockPos(x, y, z), HazardBlocks.GAS_POCKET.defaultBlockState(), 3);
	}

	@GameTest(maxTicks = TICKS)
	public void anOddPodsSounderHearsAPocketInTheFarCornerOfItsFootprint(GameTestHelper helper) {
		int x = 9144;
		int lowX = x - WIDTH / 2;
		int lowZ = Z - WIDTH / 2;
		sounds(helper, x, 1, level -> pocket(level, lowX + WIDTH - 1, FLOOR - 1, lowZ + WIDTH - 1), pod -> {
			PodSounder.State state = PodSounder.reading(pod).orElseThrow(() -> failure(helper, "the pod has a sounder and so a reading"));
			expect(helper, state.down() == 1, "a pocket in the far corner of the footprint is 1 slab down, the sounder reads %s", state);
		});
	}

	@GameTest(maxTicks = TICKS)
	public void anOddPodsSounderMarksTheSideOfAPocketAtTheTopOfItsBox(GameTestHelper helper) {
		int x = 9192;
		// The first column east of the footprint, level with the pod's top row (its height is HEIGHT, so the top row is HEIGHT - 1 above its feet).
		sounds(helper, x, 2, level -> pocket(level, x + WIDTH / 2, FLOOR + HEIGHT - 1, Z), pod -> {
			PodSounder.State state = PodSounder.reading(pod).orElseThrow(() -> failure(helper, "the pod has a sounder and so a reading"));
			for (Direction side : Direction.Plane.HORIZONTAL) {
				expect(helper, state.marks(side) == (side == Direction.EAST), "the sounder should mark %s %s, it reads %s", side, side == Direction.EAST ? "yes" : "no", state);
			}
		});
	}

	// ---- scanning ----

	@GameTest(maxTicks = TICKS)
	public void anOddPodsThermalScannerMarksLavaAMarginBeyondItsWholeBore(GameTestHelper helper) {
		int x = 9240;
		ServerLevel level = layer(helper);
		site(level, x);
		MockPlayer owner = MockPlayers.join(helper, "odd-scanner");
		owner.player().setGameMode(GameType.SURVIVAL);
		FarChunks.awaitEntityTicking(helper, level, new BlockPos(x, FLOOR, Z), () -> {
			PodEntity pod = spawn(level, new Vec3(x, FLOOR, Z));
			ScannerPods.fit(helper.getLevel().getServer(), owner.player(), pod, 2);
			// The pod faces south, so the plane is its own X. Its bore reaches WIDTH / 2 to one side and WIDTH / 2 - 1 to the other, and the
			// thermal tier looks a block beyond: lava that far out on either side is near, and a block further is not.
			int reach = WIDTH / 2 + 1;
			int[] offsets = {reach, -reach, reach + 1, -(reach + 1)};
			Cell[] expected = {Cell.LAVA_NEAR, Cell.LAVA_NEAR, Cell.ROCK, Cell.ROCK};
			for (int i = 0; i < offsets.length; i++) {
				level.setBlock(new BlockPos(x + offsets[i], FLOOR - 1, Z + 3 + 2 * i), Blocks.LAVA.defaultBlockState(), 3);
			}
			ScanSlice slice = ScanSlice.scan(level, pod).orElseThrow(() -> failure(helper, "the pod has a working scanner"));
			for (int i = 0; i < offsets.length; i++) {
				Cell got = slice.cell(3 + 2 * i, -1);
				expect(helper, got.equals(expected[i]), "lava %d blocks beside the plane should read as %s, reads %s", offsets[i], expected[i], got);
			}
			pod.discard();
			helper.succeed();
		});
	}

	// ---- landing ----

	@GameTest(maxTicks = TICKS)
	public void anOddPodLandsHardByItsSinkSpeedAndTheHullTakesIt(GameTestHelper helper) {
		int x = 9288;
		ServerLevel level = layer(helper);
		site(level, x);
		Vec3 start = new Vec3(x, FLOOR + 10, Z);
		PodEntity[] pod = {null};
		FarChunks.awaitEntityTicking(helper, level, BlockPos.containing(start), () -> pod[0] = spawn(level, start));
		helper.succeedWhen(() -> {
			expect(helper, pod[0] != null && pod[0].onGround() && pod[0].tickCount >= 5, "the pod has not landed");
			// The same fall of 10 blocks costs the Mole 28 to 34 hull (PodHardLandingTest): the cost reads sink speed and stats, not the hitbox.
			float lost = PodTuning.DEFAULT.shell().fullHull() - pod[0].hull();
			expect(helper, lost >= 28f && lost <= 34f, "a free fall of 10 blocks should cost 28 to 34 hull, cost %s", lost);
			pod[0].discard();
		});
	}

	// ---- towing ----

	@GameTest(maxTicks = TICKS)
	public void anOddPodTowedByAnotherTrailsWithoutTheTwoHullsOverlapping(GameTestHelper helper) {
		int x = 9336;
		ServerLevel level = layer(helper);
		site(level, x);
		Vec3 start = new Vec3(x - 4, FLOOR + 6, Z);
		PodEntity[] pods = {null, null};
		FarChunks.awaitEntityTicking(helper, level, BlockPos.containing(start), () -> {
			pods[0] = spawn(level, start);
			pods[1] = spawn(level, start.add(-3, 0, 0));
			PodTowing.attach(pods[0], pods[1]);
		});
		int[] step = {0};
		helper.onEachTick(() -> {
			if (pods[0] == null) {
				return;
			}
			// The tower is pinned in the air and walks 6 blocks east, so the towed pod's sinking could only be its own.
			step[0] = Math.min(step[0] + 1, 24);
			pods[0].setPos(start.x + 0.25 * step[0], start.y, start.z);
			pods[0].setDeltaMovement(Vec3.ZERO);
			if (step[0] == 24 && pods[1].tickCount > 40) {
				expect(helper, !pods[0].getBoundingBox().intersects(pods[1].getBoundingBox()), "the towed pod's hull overlaps its tower's: %s and %s", pods[0].getBoundingBox(), pods[1].getBoundingBox());
				double gap = pods[0].position().distanceTo(pods[1].position());
				expect(helper, gap <= ODD.width() + 1.5, "the towed pod should trail close behind its tower, it is %s away", gap);
				pods[0].discard();
				pods[1].discard();
				helper.succeed();
			}
		});
	}

	// ---- the economy ----

	/** Ore per slab is the cells a slab holds times the zone's chance (docs/design/mechanics.md, "Bore size and the economy"): 16 cells for the odd pod, 4 for the Mole. */
	@GameTest
	public void anOddBoreHoldsOreInProportionToTheCellsOfItsSlab(GameTestHelper helper) {
		Zone zone = Zone.load("topsoil_claims");
		PodStats stats = PodStats.base();
		EarlyRunModel.Run mole = EarlyRunModel.run(Chassis.MOLE, zone, stats, 0, 0f);
		EarlyRunModel.Run odd = EarlyRunModel.run(ODD, zone, stats, 0, 0f);
		double perSlabMole = mole.ores() / mole.slabs();
		double perSlabOdd = odd.ores() / odd.slabs();
		double wanted = (double) ODD.slabCells() / Chassis.MOLE.slabCells();
		expect(helper, ODD.slabCells() == WIDTH * WIDTH && Chassis.MOLE.slabCells() == 4, "a slab holds the cells of its square: %s and %s", ODD.slabCells(), Chassis.MOLE.slabCells());
		expect(helper, Math.abs(perSlabOdd / perSlabMole - wanted) < 1e-9, "ore per slab should scale with the cells, %s over %s is not %s", perSlabOdd, perSlabMole, wanted);
		helper.succeed();
	}

	// ---- wrecks ----

	@GameTest
	public void anOddPodWithNoHullLeftIsAWreckThatEjectsItsPilot(GameTestHelper helper) {
		PodEntity pod = helper.spawn(OddPods.TYPE, new Vec3(3.5, 2, 3.5));
		MockPlayer pilot = MockPlayers.join(helper, "odd-wreck");
		try {
			expect(helper, pilot.player().startRiding(pod, true, false), "the pilot could not board the pod");
			expect(helper, !Wrecks.isWreck(pod), "a new pod is no wreck");
			pod.damageHull(pod.maxHull());
			expect(helper, Wrecks.isWreck(pod), "a pod at hull 0 is a wreck");
			expect(helper, pod.getPassengers().isEmpty(), "a wreck holds no crew");
			expect(helper, pod.getBbWidth() == ODD.width() && pod.getBbHeight() == ODD.height(), "the wreck keeps its chassis hitbox, it is %s x %s", pod.getBbWidth(), pod.getBbHeight());
			helper.succeed();
		} finally {
			pilot.leave();
			pod.discard();
		}
	}

	// ---- the hangar ----

	/**
	 * The bay's places are a bore width and the gap apart, so a pod of any chassis has a place its own size, and two pods never share one.
	 * Run on an open site: the colony's hangar is a hall 13 blocks across that holds one 4-wide pod beside the derelict at best.
	 */
	@GameTest(maxTicks = TICKS)
	public void theBayParksOddPodsInPlacesThatFitThemAndNeverOverlap(GameTestHelper helper) {
		int x = 9384;
		ServerLevel level = layer(helper);
		site(level, x);
		BlockPos anchor = new BlockPos(x, FLOOR, Z);
		FarChunks.awaitEntityTicking(helper, level, anchor, () -> {
			List<PodEntity> parked = new ArrayList<>();
			for (Optional<Vec3> slot = Hangar.freeSlot(level, anchor, ODD); slot.isPresent() && parked.size() < 20; slot = Hangar.freeSlot(level, anchor, ODD)) {
				parked.add(spawn(level, slot.get()));
			}
			// A line of places each way from the anchor, as far as the bay's radius reaches.
			int perLine = 2 * (HangarTuning.DEFAULT.bayRadius() / (WIDTH + HangarTuning.DEFAULT.slotGap())) + 1;
			expect(helper, parked.size() == perLine * perLine, "the bay should hold %s odd pods, it held %s", perLine * perLine, parked.size());
			for (int a = 0; a < parked.size(); a++) {
				expect(helper, level.noCollision(parked.get(a).getBoundingBox()), "the odd pod in place %s is inside blocks", a);
				for (int b = a + 1; b < parked.size(); b++) {
					expect(helper, !parked.get(a).getBoundingBox().intersects(parked.get(b).getBoundingBox()), "the odd pods in places %s and %s overlap", a, b);
				}
			}
			parked.forEach(PodEntity::discard);
			helper.succeed();
		});
	}

	@GameTest
	public void everyChassisFitsTheHangarBayDoor(GameTestHelper helper) {
		// The hangar's bay is a door 4 wide and 4 high (tools/colony/town.py HANGAR_BAY, Door "hangar bay"): the Prospector passes with a block to spare.
		List<Chassis> all = new ArrayList<>(Chassis.all());
		all.add(ODD);
		for (Chassis chassis : all) {
			expect(helper, chassis.width() <= 4f && chassis.height() <= 4f, "the chassis %s is %s x %s, which does not pass the hangar's 4 x 4 bay door", chassis.id(), chassis.width(), chassis.height());
		}
		helper.succeed();
	}
}
