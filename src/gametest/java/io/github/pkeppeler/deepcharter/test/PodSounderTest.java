package io.github.pkeppeler.deepcharter.test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.Vec3;

import io.github.pkeppeler.deepcharter.attachment.Versioned;
import io.github.pkeppeler.deepcharter.layer.Depth;
import io.github.pkeppeler.deepcharter.layer.LayerChain;
import io.github.pkeppeler.deepcharter.ore.GasHazard;
import io.github.pkeppeler.deepcharter.ore.HazardBlocks;
import io.github.pkeppeler.deepcharter.ore.OreRegistry;
import io.github.pkeppeler.deepcharter.ore.OreType;
import io.github.pkeppeler.deepcharter.pod.PodComponents;
import io.github.pkeppeler.deepcharter.pod.PodDrill;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodEvents;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.pod.PodSounder;
import io.github.pkeppeler.deepcharter.pod.PodStats;
import io.github.pkeppeler.deepcharter.test.support.FarChunks;
import io.github.pkeppeler.deepcharter.test.support.MockPlayer;
import io.github.pkeppeler.deepcharter.test.support.MockPlayers;
import io.github.pkeppeler.deepcharter.test.support.RoomCarver;
import io.github.pkeppeler.deepcharter.test.support.ScannerPods;
import io.github.pkeppeler.deepcharter.test.support.UnreadableChecks;
import io.github.pkeppeler.deepcharter.upgrade.ComponentTrack;
import io.github.pkeppeler.deepcharter.upgrade.UpgradeTuning;

/**
 * Server GameTests for the seep sounder (#373), in layer 1. Each test has its own X, so the blocks it changes never touch another test's.
 * The pod stands on a bed of stone with its 2 x 2 footprint over x - 1 to x and Z - 1 to Z, its feet at {@link #FLOOR}; a test puts gas
 * pockets in the bed or beside the pod and reads what the sounder marks.
 */
public class PodSounderTest {
	private static final AtomicInteger OWNERS = new AtomicInteger();
	private static final int Z = 3600;
	private static final int FLOOR = 14;
	private static final int RADIUS = 6;
	private static final int SETTLE_TICKS = 6;
	private static final int DRILL_TICKS = 400;
	private static final Input SPRINT = new Input(false, false, false, false, false, false, true);

	/** A bed of stone with an open room above it. */
	private static void site(ServerLevel level, int x) {
		RoomCarver.carve(level, x - RADIUS, x + RADIUS, FLOOR - 12, FLOOR - 1, Z - RADIUS, Z + RADIUS, Blocks.STONE);
		RoomCarver.carve(level, x - RADIUS, x + RADIUS, FLOOR, FLOOR + 10, Z - RADIUS, Z + RADIUS, Blocks.AIR);
	}

	private static void pocket(ServerLevel level, int x, int y, int z) {
		level.setBlock(new BlockPos(x, y, z), HazardBlocks.GAS_POCKET.defaultBlockState(), 3);
	}

	private static MockPlayer owner(GameTestHelper helper) {
		MockPlayer owner = MockPlayers.join(helper, "Sounder owner " + OWNERS.incrementAndGet());
		owner.player().setGameMode(GameType.SURVIVAL);
		return owner;
	}

	/** A Mole at the middle of the site with a sounder of {@code tier} (0 for none) and no pilot. */
	private static PodEntity spawn(GameTestHelper helper, ServerLevel level, int x, int tier) {
		return spawn(helper, level, x, tier, owner(helper));
	}

	/** As {@link #spawn(GameTestHelper, ServerLevel, int, int)}, owned by {@code owner}, who may then mount it. */
	private static PodEntity spawn(GameTestHelper helper, ServerLevel level, int x, int tier, MockPlayer owner) {
		PodEntity pod = PodRegistry.POD.create(level, EntitySpawnReason.COMMAND);
		pod.setPos(x, FLOOR, Z);
		level.addFreshEntity(pod);
		if (tier > 0) {
			ScannerPods.fit(helper.getLevel().getServer(), owner.player(), pod, ComponentTrack.SOUNDER, tier);
		}
		return pod;
	}

	/** Builds the site, lets {@code build} add its pockets, spawns the pod, and runs {@code check} on the pod after it has settled for a few ticks. */
	private void reads(GameTestHelper helper, int x, int tier, Consumer<ServerLevel> build, Consumer<PodEntity> check) {
		ServerLevel level = layer(helper);
		site(level, x);
		build.accept(level);
		PodEntity[] pod = {null};
		int[] ticks = {0};
		FarChunks.awaitEntityTicking(helper, level, new BlockPos(x, FLOOR, Z), () -> pod[0] = spawn(helper, level, x, tier));
		helper.onEachTick(() -> {
			if (pod[0] == null || ++ticks[0] < SETTLE_TICKS) {
				return;
			}
			if (ticks[0] == SETTLE_TICKS) {
				check.accept(pod[0]);
				pod[0].discard();
				helper.succeed();
			}
		});
	}

	private static PodSounder.State state(GameTestHelper helper, PodEntity pod) {
		return PodSounder.reading(pod).orElseThrow(() -> failure(helper, "the pod has a sounder and so a reading"));
	}

	private static void expect(GameTestHelper helper, PodEntity pod, int down, Direction... marked) {
		PodSounder.State state = state(helper, pod);
		for (Direction side : Direction.Plane.HORIZONTAL) {
			boolean wanted = Arrays.asList(marked).contains(side);
			if (state.marks(side) != wanted) {
				throw failure(helper, "the sounder should mark %s %s, it reads %s", side, wanted ? "yes" : "no", state);
			}
		}
		if (state.down() != down) {
			throw failure(helper, "the sounder should read a pocket %s slabs down, it reads %s", down, state);
		}
	}

	// ---- what a tier 1 sounder marks: the footprint, 2 slabs down ----

	@GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + 100)
	public void aTierOneSounderMarksAPocketTheNextSlabDown(GameTestHelper helper) {
		int x = 6000;
		reads(helper, x, 1, level -> pocket(level, x, FLOOR - 1, Z), pod -> expect(helper, pod, 1));
	}

	@GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + 100)
	public void aTierOneSounderMarksAPocketInEitherColumnOfTheFootprintTwoSlabsDown(GameTestHelper helper) {
		int x = 6048;
		reads(helper, x, 1, level -> pocket(level, x - 1, FLOOR - 2, Z - 1), pod -> expect(helper, pod, 2));
	}

	@GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + 100)
	public void aTierOneSounderReadsTheNearestOfSeveralPockets(GameTestHelper helper) {
		int x = 6096;
		reads(helper, x, 1, level -> {
			pocket(level, x, FLOOR - 2, Z);
			pocket(level, x - 1, FLOOR - 1, Z - 1);
		}, pod -> expect(helper, pod, 1));
	}

	@GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + 100)
	public void aTierOneSounderHearsNothingThreeSlabsDownOrBeside(GameTestHelper helper) {
		int x = 6144;
		reads(helper, x, 1, level -> {
			pocket(level, x, FLOOR - 3, Z);
			pocket(level, x + 1, FLOOR, Z);
			pocket(level, x + 2, FLOOR - 1, Z);
			pocket(level, x - 2, FLOOR - 2, Z - 1);
		}, pod -> {
			expect(helper, pod, 0);
			if (!state(helper, pod).isClear()) {
				throw failure(helper, "a tier 1 sounder hears only the footprint, 2 slabs down: %s", state(helper, pod));
			}
		});
	}

	// ---- tier 2: 4 slabs down and 2 blocks to each side ----

	@GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + 100)
	public void aTierTwoSounderMarksAPocketFourSlabsDown(GameTestHelper helper) {
		int x = 6192;
		reads(helper, x, 2, level -> pocket(level, x, FLOOR - 4, Z), pod -> expect(helper, pod, 4));
	}

	@GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + 100)
	public void aTierTwoSounderHearsNothingFiveSlabsDown(GameTestHelper helper) {
		int x = 6240;
		reads(helper, x, 2, level -> pocket(level, x, FLOOR - 5, Z), pod -> expect(helper, pod, 0));
	}

	@GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + 100)
	public void aTierTwoSounderMarksTheSideAPocketTwoBlocksOutLiesOn(GameTestHelper helper) {
		int x = 6288;
		// East of the footprint (x - 1 and x): a step of one or two blocks bores x + 1 and x + 2.
		reads(helper, x, 2, level -> pocket(level, x + 2, FLOOR + 1, Z), pod -> expect(helper, pod, 0, Direction.EAST));
	}

	@GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + 100)
	public void aTierTwoSounderMarksTheSlabATwoBlockStepLandsOn(GameTestHelper helper) {
		int x = 6336;
		reads(helper, x, 2, level -> pocket(level, x - 3, FLOOR - 1, Z - 1), pod -> expect(helper, pod, 0, Direction.WEST));
	}

	@GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + 100)
	public void aTierTwoSounderIgnoresASideBeyondTwoBlocksAboveTheTopAndBelowTheLanding(GameTestHelper helper) {
		int x = 6384;
		reads(helper, x, 2, level -> {
			pocket(level, x + 3, FLOOR, Z);
			pocket(level, x - 4, FLOOR, Z);
			pocket(level, x + 1, FLOOR + 2, Z);
			pocket(level, x - 3, FLOOR - 2, Z);
			pocket(level, x, FLOOR, Z + 3);
		}, pod -> expect(helper, pod, 0));
	}

	@GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + 100)
	public void aTierTwoSounderMarksNorthAndSouthToo(GameTestHelper helper) {
		int x = 6432;
		reads(helper, x, 2, level -> {
			pocket(level, x, FLOOR, Z + 2);
			pocket(level, x - 1, FLOOR + 1, Z - 3);
			pocket(level, x, FLOOR - 3, Z);
		}, pod -> expect(helper, pod, 3, Direction.SOUTH, Direction.NORTH));
	}

	// ---- no part, no power ----

	@GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + 100)
	public void aPodWithoutASounderReadsNothingAndKeepsAnEmptyState(GameTestHelper helper) {
		int x = 6480;
		reads(helper, x, 0, level -> pocket(level, x, FLOOR - 1, Z), pod -> {
			if (PodSounder.reading(pod).isPresent() || !Versioned.readable(pod, PodSounder.STATE).orElseThrow().equals(PodSounder.State.EMPTY)) {
				throw failure(helper, "a pod with no sounder has no reading and an empty state, got %s", PodSounder.reading(pod));
			}
		});
	}

	@GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + 100)
	public void aPodWhosePartIsGoneLosesItsStaleMarks(GameTestHelper helper) {
		int x = 6912;
		ServerLevel level = layer(helper);
		site(level, x);
		PodEntity[] pod = {null};
		int[] ticks = {0};
		FarChunks.awaitEntityTicking(helper, level, new BlockPos(x, FLOOR, Z), () -> {
			pod[0] = spawn(helper, level, x, 0);
			pod[0].setAttached(PodSounder.STATE, Versioned.of(new PodSounder.State(2, 0b0100)));
		});
		helper.onEachTick(() -> {
			if (pod[0] == null || ++ticks[0] != SETTLE_TICKS) {
				return;
			}
			if (!Versioned.readable(pod[0], PodSounder.STATE).orElseThrow().isClear()) {
				throw failure(helper, "a pod with no sounder keeps no marks, it holds %s", Versioned.readable(pod[0], PodSounder.STATE));
			}
			pod[0].discard();
			helper.succeed();
		});
	}

	private static final Set<UUID> UNPOWERED = ConcurrentHashMap.newKeySet();
	private static boolean unpoweredListener;

	@GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + 100)
	public void anUnpoweredPodsSounderMarksNothing(GameTestHelper helper) {
		if (!unpoweredListener) {
			unpoweredListener = true;
			PodEvents.IS_POWERED.register(pod -> !UNPOWERED.contains(pod.getUUID()));
		}
		int x = 6528;
		ServerLevel level = layer(helper);
		site(level, x);
		pocket(level, x, FLOOR - 1, Z);
		PodEntity[] pod = {null};
		int[] ticks = {0};
		FarChunks.awaitEntityTicking(helper, level, new BlockPos(x, FLOOR, Z), () -> {
			pod[0] = spawn(helper, level, x, 1);
			UNPOWERED.add(pod[0].getUUID());
		});
		helper.onEachTick(() -> {
			if (pod[0] == null || ++ticks[0] != SETTLE_TICKS) {
				return;
			}
			if (!state(helper, pod[0]).isClear()) {
				throw failure(helper, "a pod with no power hears nothing, it reads %s", state(helper, pod[0]));
			}
			pod[0].discard();
			helper.succeed();
		});
	}

	@GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + 100)
	public void theMarksFollowAPocketThatIsMinedAway(GameTestHelper helper) {
		int x = 6576;
		ServerLevel level = layer(helper);
		site(level, x);
		pocket(level, x, FLOOR - 1, Z);
		PodEntity[] pod = {null};
		int[] ticks = {0};
		FarChunks.awaitEntityTicking(helper, level, new BlockPos(x, FLOOR, Z), () -> pod[0] = spawn(helper, level, x, 1));
		helper.onEachTick(() -> {
			if (pod[0] == null) {
				return;
			}
			ticks[0]++;
			if (ticks[0] == SETTLE_TICKS) {
				expect(helper, pod[0], 1);
				level.setBlock(new BlockPos(x, FLOOR - 1, Z), Blocks.STONE.defaultBlockState(), 3);
			}
			if (ticks[0] == SETTLE_TICKS + 2) {
				expect(helper, pod[0], 0);
				pod[0].discard();
				helper.succeed();
			}
		});
	}

	// ---- the hiss ----

	@GameTest
	public void theHissQuickensAsThePocketNears(GameTestHelper helper) {
		int[] every = new int[4];
		for (int slabs = 1; slabs <= 4; slabs++) {
			every[slabs - 1] = PodSounder.hissEveryTicks(slabs);
		}
		if (every[0] != 12 || every[1] != 24 || every[2] != 36 || every[3] != 48) {
			throw failure(helper, "the hiss repeats every 12 ticks for each slab away: 12, 24, 36, 48; it is %s", Arrays.toString(every));
		}
		helper.succeed();
	}

	// ---- the drill ----

	@GameTest
	public void aSounderSlowsTheDrillByItsTiersShareAndAPodWithoutOneIsStock(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		PodEntity stock = PodRegistry.POD.create(level, EntitySpawnReason.COMMAND);
		PodEntity one = spawn(helper, level, 0, 1);
		PodEntity two = spawn(helper, level, 0, 2);
		float stockTicks = PodStats.of(stock).ticksPerHardness();
		float tierOne = PodStats.of(one).ticksPerHardness();
		float tierTwo = PodStats.of(two).ticksPerHardness();
		// A tier 1 sounder takes 5% of the drill's speed, a tier 2 sounder 10%: each hardness takes 1/0.95 and 1/0.90 of the stock ticks.
		if (Math.abs(tierOne - stockTicks / 0.95f) > 1e-3 || Math.abs(tierTwo - stockTicks / 0.90f) > 1e-3) {
			throw failure(helper, "stock %s ticks a hardness; tier 1 should take %s and tier 2 %s, got %s and %s", stockTicks, stockTicks / 0.95f, stockTicks / 0.90f, tierOne, tierTwo);
		}
		one.discard();
		two.discard();
		helper.succeed();
	}

	/**
	 * What a drilled pocket cost: the ticks from the first tick of the pilot's hold to the vent, the hull lost to the blast, the ticks the pod's own
	 * stats say the slab takes to bore, and the blast the pod's radiator would let through whole.
	 */
	private record Bleed(int ticks, float hull, int slabTicks, float whole, float maxHull, float bystanderHull) {
	}

	/** What a bled blast costs: the larger of 45% of the hull and half the blast, and never more than the blast (the numbers are pinned here on purpose). */
	private static float bledCost(float whole, float maxHull) {
		return Math.min(whole, Math.max(0.45f * maxHull, 0.5f * whole));
	}

	/**
	 * A pilot holds the drill down over a bed whose first slab holds one pocket; the test notes when the pocket goes (the drill vented it) and
	 * what the blast cost the hull. {@code tier} 0 is a pod with no sounder.
	 */
	private void drillOverAPocket(GameTestHelper helper, int x, int tier, Consumer<Bleed> check) {
		drillOverAPocket(helper, x, tier, false, check);
	}

	/** As above; with {@code bystander}, a second pod with a tier 2 sounder of its own stands within the blast's reach and drills nothing, and its loss is noted too. */
	private void drillOverAPocket(GameTestHelper helper, int x, int tier, boolean bystander, Consumer<Bleed> check) {
		ServerLevel level = layer(helper);
		site(level, x);
		BlockPos pocket = new BlockPos(x, FLOOR - 1, Z);
		pocket(level, x, FLOOR - 1, Z);
		MockPlayer pilot = owner(helper);
		pilot.teleportTo(level, new Vec3(x, FLOOR, Z), 0f, 0f);
		PodEntity[] pod = {null};
		int[] ticks = {0};
		int[] slabTicks = {0};
		PodEntity[] other = {null};
		FarChunks.awaitEntityTicking(helper, level, new BlockPos(x, FLOOR, Z), () -> {
			pod[0] = spawn(helper, level, x, tier, pilot);
			if (bystander) {
				other[0] = spawn(helper, level, x, 2);
				other[0].setPos(x + 2.5, FLOOR, Z);
			}
			if (!pilot.player().startRiding(pod[0])) {
				throw failure(helper, "the pilot could not mount the pod");
			}
			slabTicks[0] = slabTicks(level, PodStats.of(pod[0]), x);
			pilot.setInput(SPRINT);
		});
		float[] startHull = {-1f, -1f};
		helper.onEachTick(() -> {
			if (pod[0] == null) {
				return;
			}
			if (startHull[0] < 0) {
				startHull[0] = pod[0].hull();
				startHull[1] = other[0] == null ? 0f : other[0].hull();
			}
			ticks[0]++;
			if (!level.getBlockState(pocket).is(HazardBlocks.GAS_POCKET)) {
				float lost = startHull[0] - pod[0].hull();
				float whole = GasHazard.damage(Depth.feet(Depth.of(level, FLOOR - 1)), PodComponents.radiatorRatio(pod[0]));
				float otherLost = other[0] == null ? Float.NaN : startHull[1] - other[0].hull();
				pilot.releaseInput();
				pilot.leave();
				pod[0].discard();
				if (other[0] != null) {
					other[0].discard();
				}
				check.accept(new Bleed(ticks[0], lost, slabTicks[0], whole, startHull[0], otherLost));
				helper.succeed();
			}
		});
	}

	/** The ticks the drill needs for the slab under the pod: its hardest cell at the slab's depth, with the pod's own stats. */
	private static int slabTicks(ServerLevel level, PodStats stats, int x) {
		float hardness = 0;
		for (int cx = x - 1; cx <= x; cx++) {
			for (int cz = Z - 1; cz <= Z; cz++) {
				BlockPos pos = new BlockPos(cx, FLOOR - 1, cz);
				hardness = Math.max(hardness, level.getBlockState(pos).getDestroySpeed(level, pos));
			}
		}
		return PodDrill.drillTicks(stats, hardness, Depth.feet(Depth.of(level, FLOOR - 1)));
	}

	/** The pod bored the slab in about {@code expected} ticks: the hold starts a tick or two before the drill does, and the pod must centre. */
	private static boolean about(int ticks, int expected) {
		return ticks >= expected - 1 && ticks <= expected + 8;
	}

	@GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + DRILL_TICKS)
	public void aPodWithoutASounderBoresAPocketAtOnceAndTakesTheWholeBlast(GameTestHelper helper) {
		drillOverAPocket(helper, 6624, 0, bleed -> {
			if (bleed.whole() < 2f || Math.abs(bleed.hull() - bleed.whole()) > 1e-3) {
				throw failure(helper, "a pod with no sounder takes the whole blast of %s, it lost %s", bleed.whole(), bleed.hull());
			}
			if (!about(bleed.ticks(), bleed.slabTicks())) {
				throw failure(helper, "a pod with no sounder bores the slab in %s ticks, it took %s", bleed.slabTicks(), bleed.ticks());
			}
		});
	}

	@GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + DRILL_TICKS)
	public void aTierOneSounderDoesNotBleedAPocket(GameTestHelper helper) {
		drillOverAPocket(helper, 6672, 1, bleed -> {
			if (Math.abs(bleed.hull() - bleed.whole()) > 1e-3 || !about(bleed.ticks(), bleed.slabTicks())) {
				throw failure(helper, "tier 1 only hears: the whole blast of %s after %s ticks, got %s hull after %s ticks", bleed.whole(), bleed.slabTicks(), bleed.hull(), bleed.ticks());
			}
		});
	}

	@GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + DRILL_TICKS)
	public void aTierTwoSounderWaitsThreeSecondsAndTakesTheLargerOfAHullShareAndHalfTheBlast(GameTestHelper helper) {
		drillOverAPocket(helper, 6720, 2, bleed -> {
			float cost = bledCost(bleed.whole(), bleed.maxHull());
			if (cost >= bleed.whole() - 1f || Math.abs(bleed.hull() - cost) > 1e-3) {
				throw failure(helper, "the blast of %s on %s hull should cost the bled %s (less than the whole, or the test shows nothing); it cost %s", bleed.whole(), bleed.maxHull(), cost, bleed.hull());
			}
			if (!about(bleed.ticks(), bleed.slabTicks() + 60)) {
				throw failure(helper, "the drill waits 60 ticks before it bores a marked pocket: %s ticks in all, it took %s", bleed.slabTicks() + 60, bleed.ticks());
			}
		});
	}

	@GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + DRILL_TICKS)
	public void aPodInTheBlastThatDoesNotDrillTakesTheWholeBlastEvenWithATierTwoSounder(GameTestHelper helper) {
		drillOverAPocket(helper, 6768, 2, true, bleed -> {
			if (Math.abs(bleed.bystanderHull() - bleed.whole()) > 1e-3 || Math.abs(bleed.hull() - bledCost(bleed.whole(), bleed.maxHull())) > 1e-3) {
				throw failure(helper, "the driller takes the bled %s and the pod beside it the whole blast of %s; they lost %s and %s", bledCost(bleed.whole(), bleed.maxHull()), bleed.whole(),
						bleed.hull(), bleed.bystanderHull());
			}
		});
	}

	@GameTest
	public void aBledBlastCostsTheBlastWhenItIsUnderTheBledCostAndTheBledCostWhenItIsOver(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		PodEntity stock = spawn(helper, level, 0, 0);
		PodEntity one = spawn(helper, level, 0, 1);
		PodEntity two = spawn(helper, level, 0, 2);
		// A tier 2 pod of 100 hull: 45 is the hull share, and half of a blast of 100 is 50, which is the larger. A blast of 5 costs 5.
		float[] got = {PodSounder.drilledBlast(stock, 40f), PodSounder.drilledBlast(one, 40f), PodSounder.drilledBlast(two, 5f), PodSounder.drilledBlast(two, 40f),
				PodSounder.drilledBlast(two, 100f), PodSounder.drilledBlast(two, 60f)};
		if (two.maxHull() != 100f || !Arrays.equals(got, new float[] {40f, 40f, 5f, 40f, 50f, 45f})) {
			throw failure(helper, "a stock pod and a tier 1 sounder take 40 whole; a tier 2 sounder on 100 hull takes 5 for 5, 40 for 40, 50 for 100 and 45 for 60, got %s", Arrays.toString(got));
		}
		stock.discard();
		one.discard();
		two.discard();
		helper.succeed();
	}

	/**
	 * A tier 2 pilot holds the drill over a pocket whose slab also holds three ore blocks; {@code act} is called each tick with the ticks since the hold
	 * began, and decides what happens in the pause. It returns true when the test is over.
	 */
	private interface PauseScript {
		boolean tick(int ticks, int slabTicks, PodEntity pod, MockPlayer pilot, BlockPos pocket, List<BlockPos> ores);
	}

	private void inThePause(GameTestHelper helper, int x, PauseScript script) {
		ServerLevel level = layer(helper);
		site(level, x);
		BlockPos pocket = new BlockPos(x, FLOOR - 1, Z);
		pocket(level, x, FLOOR - 1, Z);
		List<BlockPos> ores = List.of(new BlockPos(x - 1, FLOOR - 1, Z - 1), new BlockPos(x, FLOOR - 1, Z - 1), new BlockPos(x - 1, FLOOR - 1, Z));
		ores.forEach(ore -> level.setBlock(ore, OreRegistry.block(OreType.IRONIUM).defaultBlockState(), 3));
		MockPlayer pilot = owner(helper);
		pilot.teleportTo(level, new Vec3(x, FLOOR, Z), 0f, 0f);
		PodEntity[] pod = {null};
		int[] slabTicks = {0};
		int[] ticks = {0};
		FarChunks.awaitEntityTicking(helper, level, new BlockPos(x, FLOOR, Z), () -> {
			pod[0] = spawn(helper, level, x, 2, pilot);
			if (!pilot.player().startRiding(pod[0])) {
				throw failure(helper, "the pilot could not mount the pod");
			}
			slabTicks[0] = slabTicks(level, PodStats.of(pod[0]), x);
			pilot.setInput(SPRINT);
		});
		helper.onEachTick(() -> {
			if (pod[0] == null) {
				return;
			}
			ticks[0]++;
			if (script.tick(ticks[0], slabTicks[0], pod[0], pilot, pocket, ores)) {
				pilot.releaseInput();
				pilot.leave();
				pod[0].discard();
				helper.succeed();
			}
		});
	}

	@GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + DRILL_TICKS)
	public void aPodThatStopsDrillingInThePauseWaitsTheWholePauseAgain(GameTestHelper helper) {
		int x = 6816;
		int[] back = {0};
		inThePause(helper, x, (ticks, slabTicks, pod, pilot, pocket, ores) -> {
			if (ticks == slabTicks + 30) {
				// The pilot lets go of the drill for a few ticks: the slab's progress, and the pause with it, is lost.
				pilot.releaseInput();
			} else if (ticks == slabTicks + 34) {
				pilot.setInput(SPRINT);
				back[0] = ticks;
			}
			boolean vented = !pod.level().getBlockState(pocket).is(HazardBlocks.GAS_POCKET);
			if (vented && back[0] == 0) {
				throw failure(helper, "the pocket vented at tick %s, before the pilot let go at %s", ticks, slabTicks + 30);
			}
			if (vented) {
				int waited = ticks - back[0];
				if (waited < slabTicks + 55) {
					throw failure(helper, "a pod that lets go of the drill in the pause starts it again: it took up the hold at tick %s and vented %s ticks later, with the slab at %s ticks", back[0], waited, slabTicks);
				}
			}
			return vented;
		});
	}

	@GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + DRILL_TICKS)
	public void aPocketVentedByHandInThePauseLeavesASlabThatBoresAtItsOwnDrillTicks(GameTestHelper helper) {
		int x = 6864;
		int[] ventedAt = {0};
		int[] needed = {0};
		inThePause(helper, x, (ticks, slabTicks, pod, pilot, pocket, ores) -> {
			ServerLevel level = (ServerLevel) pod.level();
			if (ticks == slabTicks + 30) {
				// A player mines the pocket from outside the pod: it blasts, and clears the natural rock round it, but not the ore.
				level.destroyBlock(pocket, false);
				GasHazard.vent(level, pocket);
				ventedAt[0] = ticks;
				needed[0] = slabTicks(level, PodStats.of(pod), x);
				return false;
			}
			if (ventedAt[0] == 0) {
				return false;
			}
			boolean bored = ores.stream().noneMatch(ore -> level.getBlockState(ore).is(OreRegistry.block(OreType.IRONIUM)));
			int allowed = Math.max(0, needed[0] - ventedAt[0]) + 4;
			if (bored) {
				if (allowed >= 56) {
					throw failure(helper, "the slab needs %s ticks and the hold had run %s, so a bore within %s ticks of the vent is no pause", needed[0], ventedAt[0], allowed);
				}
				return true;
			}
			if (ticks - ventedAt[0] > allowed) {
				throw failure(helper, "with the pocket gone the slab bores at its own %s ticks, within %s of the vent at %s; it had not bored at %s", needed[0], allowed, ventedAt[0], ticks);
			}
			return false;
		});
	}

	// ---- saving ----

	@GameTest
	public void theSoundersStateSurvivesASaveAndALoad(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		PodEntity pod = PodRegistry.POD.create(level, EntitySpawnReason.COMMAND);
		PodSounder.State state = new PodSounder.State(3, 0b1010);
		pod.setAttached(PodSounder.STATE, Versioned.of(state));
		TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, level.registryAccess());
		pod.saveWithoutId(output);
		PodEntity loaded = PodRegistry.POD.create(level, EntitySpawnReason.COMMAND);
		loaded.load(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), output.buildResult()));
		if (!Versioned.readable(loaded, PodSounder.STATE).orElseThrow().equals(state)) {
			throw failure(helper, "the sounder's marks must survive a save and a load, got %s", Versioned.readable(loaded, PodSounder.STATE));
		}
		helper.succeed();
	}

	@GameTest
	public void aSounderStateOfAnotherVersionIsKeptAndNeverThrows(GameTestHelper helper) {
		PodEntity pod = spawn(helper, helper.getLevel(), 0, 2);
		UnreadableChecks.makeUnreadable(pod, PodSounder.STATE);
		UnreadableChecks.assertNoThrow(helper, "pod sounder", Map.of(
				"the reading", () -> PodSounder.reading(pod),
				"the pause", () -> PodSounder.bleedPauseTicks(pod),
				"the tick", () -> PodEvents.AFTER_TICK.invoker().afterTick(pod),
				"the stats", () -> PodStats.of(pod)));
		if (!(pod.getAttached(PodSounder.STATE) instanceof Versioned.Unreadable<PodSounder.State>)) {
			throw failure(helper, "unreadable sounder state is left as it was read");
		}
		pod.discard();
		helper.succeed();
	}

	@GameTest
	public void theTiersHaveTheirPrices(GameTestHelper helper) {
		long one = UpgradeTuning.DEFAULT.price(ComponentTrack.SOUNDER, 1);
		long two = UpgradeTuning.DEFAULT.price(ComponentTrack.SOUNDER, 2);
		if (one != 400 || two != 1000 || ComponentTrack.SOUNDER.maxTier() != 2) {
			throw failure(helper, "the sounder costs $400 and $1,000 over 2 tiers, it costs $%s and $%s over %s", one, two, ComponentTrack.SOUNDER.maxTier());
		}
		helper.succeed();
	}

	private static RuntimeException failure(GameTestHelper helper, String format, Object... args) {
		return helper.assertionException(Component.literal(String.format(format, args)));
	}

	private static ServerLevel layer(GameTestHelper helper) {
		ServerLevel level = helper.getLevel().getServer().getLevel(LayerChain.dimension(1));
		if (level == null) {
			throw failure(helper, "dimension %s did not load", LayerChain.dimension(1));
		}
		return level;
	}
}
