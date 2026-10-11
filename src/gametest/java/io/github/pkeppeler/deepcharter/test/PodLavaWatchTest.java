package io.github.pkeppeler.deepcharter.test;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.Vec3;

import io.github.pkeppeler.deepcharter.attachment.Versioned;
import io.github.pkeppeler.deepcharter.layer.LayerChain;
import io.github.pkeppeler.deepcharter.ore.SlagBrick;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodEvents;
import io.github.pkeppeler.deepcharter.pod.PodLavaWatch;
import io.github.pkeppeler.deepcharter.pod.PodLavaWatchTuning;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.test.support.FarChunks;
import io.github.pkeppeler.deepcharter.test.support.MockPlayer;
import io.github.pkeppeler.deepcharter.test.support.MockPlayers;
import io.github.pkeppeler.deepcharter.test.support.RoomCarver;
import io.github.pkeppeler.deepcharter.test.support.UnreadableChecks;

/**
 * Server GameTests for the lava watch (#454), in layer 1. Each test has its own X. The pod stands on a bed of stone with its 2 x 2 footprint over
 * x - 1 to x and Z - 1 to Z, its feet at {@link #FLOOR}, in a shaft of air that goes up from it, or beside a one-block corridor that goes east from
 * it. The staged flow is the point: lava put where the liner cannot see it runs down the shaft, and the watch must speak before the hull burns.
 */
public class PodLavaWatchTest {
	private static final Logger LOGGER = LoggerFactory.getLogger(PodLavaWatchTest.class);
	private static final AtomicInteger OWNERS = new AtomicInteger();
	private static final int Z = 5400;
	private static final int FLOOR = 14;
	/** Air cells above the pod's feet in the shaft; the box takes the lowest 3. */
	private static final int SHAFT = 19;
	private static final int REACH = PodLavaWatchTuning.DEFAULT.reach();
	/** Past two looks of the watch, so a reading is current. */
	private static final int SETTLE_TICKS = 2 * PodLavaWatchTuning.DEFAULT.scanEveryTicks() + 2;
	private static final int FLOW_TICKS = 1800;
	private static final int QUIET_TICKS = 400;

	/** A bed of stone with a shaft of air in the pod's footprint, up to {@code shaft} cells over its feet, and a corridor of {@code corridor} cells east at its feet. */
	private static void site(ServerLevel level, int x, int shaft, int corridor) {
		RoomCarver.carve(level, x - 3, x + 20, FLOOR - 3, FLOOR + shaft + 3, Z - 3, Z + 3, Blocks.STONE);
		RoomCarver.carve(level, x - 1, x, FLOOR, FLOOR + shaft - 1, Z - 1, Z, Blocks.AIR);
		if (corridor > 0) {
			RoomCarver.carve(level, x + 1, x + corridor, FLOOR, FLOOR, Z, Z, Blocks.AIR);
		}
	}

	private static PodEntity spawn(ServerLevel level, int x) {
		PodEntity pod = PodRegistry.POD.create(level, EntitySpawnReason.COMMAND);
		pod.setPos(x, FLOOR, Z);
		level.addFreshEntity(pod);
		return pod;
	}

	/** Spawns the pod with a pilot in the seat once its chunk ticks, or with none, and runs {@code then} with it. */
	private static void pod(GameTestHelper helper, ServerLevel level, int x, boolean piloted, Consumer<PodEntity> then) {
		MockPlayer pilot = MockPlayers.join(helper, "Watch pilot " + OWNERS.incrementAndGet());
		pilot.player().setGameMode(GameType.SURVIVAL);
		pilot.teleportTo(level, new Vec3(x, FLOOR, Z), 0f, 0f);
		FarChunks.awaitEntityTicking(helper, level, new BlockPos(x, FLOOR, Z), () -> {
			PodEntity pod = spawn(level, x);
			if (piloted && !pilot.player().startRiding(pod)) {
				throw failure(helper, "the pilot could not mount the pod");
			}
			then.accept(pod);
		});
	}

	private static Optional<Integer> reading(PodEntity pod) {
		return PodLavaWatch.blocksOff(pod);
	}

	/** Lava flowing down the shaft from far above: the watch speaks while the lava is still some blocks off, and before the hull loses a point. */
	@GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + FLOW_TICKS)
	public void lavaFlowingDownTheShaftFromOutsideTheLinersViewWarnsBeforeTheHullBurns(GameTestHelper helper) {
		int x = 7000;
		ServerLevel level = layer(helper);
		site(level, x, SHAFT, 0);
		int[] ticks = {0};
		int[] warnedAt = {-1};
		int[] warnedBlocksOff = {0};
		PodEntity[] podRef = {null};
		float[] startHull = {0f};
		pod(helper, level, x, true, pod -> {
			podRef[0] = pod;
			startHull[0] = pod.hull();
			level.setBlock(new BlockPos(x, FLOOR + SHAFT - 1, Z), Blocks.LAVA.defaultBlockState(), 3);
		});
		helper.onEachTick(() -> {
			PodEntity pod = podRef[0];
			if (pod == null) {
				return;
			}
			ticks[0]++;
			if (warnedAt[0] < 0 && reading(pod).isPresent()) {
				warnedAt[0] = ticks[0];
				warnedBlocksOff[0] = reading(pod).orElseThrow();
			}
			if (pod.hull() < startHull[0]) {
				if (warnedAt[0] < 0) {
					throw failure(helper, "the hull burned at tick %s and the watch had said nothing", ticks[0]);
				}
				if (warnedBlocksOff[0] < 2) {
					throw failure(helper, "the watch spoke only when the lava was %s block off, no time to act", warnedBlocksOff[0]);
				}
				LOGGER.info("[lava-watch] warned at tick {} with the lava {} blocks off, hull burned at tick {}: {} ticks to act",
						warnedAt[0], warnedBlocksOff[0], ticks[0], ticks[0] - warnedAt[0]);
				pod.discard();
				helper.succeed();
			}
		});
	}

	/** A pilot, a shaft and no lava anywhere: the watch stays quiet. */
	@GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + QUIET_TICKS + 100)
	public void aPodWithNoLavaAboutIsNotWarned(GameTestHelper helper) {
		int x = 7064;
		ServerLevel level = layer(helper);
		site(level, x, SHAFT, 0);
		quietFor(helper, level, x, QUIET_TICKS, pod -> {
		}, level2 -> {
		});
	}

	/**
	 * Lava runs down the shaft, but a plug of stone in it cuts the flow off from the pod: the lava is within the watch's reach as the crow flies, and joined
	 * to nothing. The watch stays quiet and the hull stays whole, and the lava is shown above the plug.
	 */
	@GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + QUIET_TICKS + 100)
	public void lavaSealedOffByARockPlugInTheShaftIsNotWarned(GameTestHelper helper) {
		int x = 7128;
		ServerLevel level = layer(helper);
		site(level, x, SHAFT, 0);
		int plug = FLOOR + 8;
		RoomCarver.carve(level, x - 1, x, plug, plug, Z - 1, Z, Blocks.STONE);
		BlockPos flowing = new BlockPos(x, plug + 1, Z);
		quietFor(helper, level, x, QUIET_TICKS,
				pod -> level.setBlock(new BlockPos(x, plug + 4, Z), Blocks.LAVA.defaultBlockState(), 3),
				level2 -> {
					if (!level2.getFluidState(flowing).is(FluidTags.LAVA)) {
						throw failure(helper, "the lava should have run down to the plug, so that the test shows a flow the watch does not warn of");
					}
				});
	}

	/** Runs the pod with a pilot for {@code ticks}, failing on any reading or any lost hull, then runs {@code after}. */
	private static void quietFor(GameTestHelper helper, ServerLevel level, int x, int ticks, Consumer<PodEntity> stage, Consumer<ServerLevel> after) {
		int[] counted = {0};
		PodEntity[] podRef = {null};
		float[] startHull = {0f};
		pod(helper, level, x, true, pod -> {
			podRef[0] = pod;
			startHull[0] = pod.hull();
			stage.accept(pod);
		});
		helper.onEachTick(() -> {
			PodEntity pod = podRef[0];
			if (pod == null) {
				return;
			}
			if (reading(pod).isPresent() || pod.hull() < startHull[0]) {
				throw failure(helper, "tick %s: the watch reads %s and the hull is %s of %s, and neither lava nor a warning was due", counted[0], reading(pod), pod.hull(), startHull[0]);
			}
			if (++counted[0] == ticks) {
				after.accept(level);
				pod.discard();
				helper.succeed();
			}
		});
	}

	// ---- how far the watch reaches, and what stops it ----

	/** Flowing lava {@code at} blocks along a corridor (a brick in the corridor at {@code brick}, or 0 for none): what the watch reads, or empty for nothing. */
	private void corridor(GameTestHelper helper, int x, int at, int brick, Optional<Integer> expected, boolean piloted) {
		ServerLevel level = layer(helper);
		site(level, x, 3, REACH + 2);
		int[] ticks = {0};
		PodEntity[] podRef = {null};
		pod(helper, level, x, piloted, pod -> {
			podRef[0] = pod;
			if (brick > 0) {
				level.setBlock(new BlockPos(x + brick, FLOOR, Z), SlagBrick.BLOCK.defaultBlockState(), 3);
			}
			level.setBlock(new BlockPos(x + at, FLOOR, Z), Blocks.LAVA.defaultBlockState().setValue(LiquidBlock.LEVEL, 7), 3);
		});
		helper.onEachTick(() -> {
			if (podRef[0] == null || ++ticks[0] < SETTLE_TICKS) {
				return;
			}
			Optional<Integer> read = reading(podRef[0]);
			if (!read.equals(expected)) {
				throw failure(helper, "flowing lava %s blocks along the corridor (brick at %s, pilot %s) should read %s, it reads %s", at, brick, piloted, expected, read);
			}
			podRef[0].discard();
			helper.succeed();
		});
	}

	@GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + 100)
	public void lavaAtTheEdgeOfTheReachIsRead(GameTestHelper helper) {
		corridor(helper, 7192, REACH, 0, Optional.of(REACH), true);
	}

	@GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + 100)
	public void lavaOneBlockPastTheReachIsNotRead(GameTestHelper helper) {
		corridor(helper, 7256, REACH + 1, 0, Optional.empty(), true);
	}

	@GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + 100)
	public void lavaBesideTheBoxReadsOneBlockOff(GameTestHelper helper) {
		corridor(helper, 7320, 1, 0, Optional.of(1), true);
	}

	/** Lining the corridor is the pilot's answer: the same lava with a brick between it and the pod is not read. */
	@GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + 100)
	public void aBrickBetweenTheLavaAndThePodSilencesTheWarning(GameTestHelper helper) {
		corridor(helper, 7384, 5, 2, Optional.empty(), true);
	}

	@GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + 100)
	public void aPodWithNoPilotIsNotWarned(GameTestHelper helper) {
		corridor(helper, 7448, 3, 0, Optional.empty(), false);
	}

	// ---- saving ----

	@GameTest
	public void theWatchsStateSurvivesASaveAndALoad(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		PodEntity pod = PodRegistry.POD.create(level, EntitySpawnReason.COMMAND);
		pod.setAttached(PodLavaWatch.STATE, Versioned.of(new PodLavaWatch.State(7)));
		TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, level.registryAccess());
		pod.saveWithoutId(output);
		PodEntity loaded = PodRegistry.POD.create(level, EntitySpawnReason.COMMAND);
		loaded.load(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), output.buildResult()));
		if (!reading(loaded).equals(Optional.of(7))) {
			throw failure(helper, "the watch's reading must survive a save and a load, got %s", reading(loaded));
		}
		helper.succeed();
	}

	@GameTest
	public void aWatchStateOfAnotherVersionIsKeptAndNeverThrows(GameTestHelper helper) {
		PodEntity pod = PodRegistry.POD.create(helper.getLevel(), EntitySpawnReason.COMMAND);
		UnreadableChecks.makeUnreadable(pod, PodLavaWatch.STATE);
		UnreadableChecks.assertNoThrow(helper, "pod lava watch", Map.of(
				"the reading", () -> PodLavaWatch.blocksOff(pod),
				"the tick", () -> PodEvents.AFTER_TICK.invoker().afterTick(pod)));
		if (!(pod.getAttached(PodLavaWatch.STATE) instanceof Versioned.Unreadable<PodLavaWatch.State>)) {
			throw failure(helper, "unreadable watch state is left as it was read");
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
