package io.github.pkeppeler.deepcharter.test;

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
import net.minecraft.world.phys.Vec3;

import io.github.pkeppeler.deepcharter.layer.LayerChain;
import io.github.pkeppeler.deepcharter.pod.HardLanding;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodFuel;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.pod.PodStats;
import io.github.pkeppeler.deepcharter.pod.PodTuning;
import io.github.pkeppeler.deepcharter.test.support.FarChunks;
import io.github.pkeppeler.deepcharter.test.support.MockPlayer;
import io.github.pkeppeler.deepcharter.test.support.MockPlayers;
import io.github.pkeppeler.deepcharter.test.support.RoomCarver;
import io.github.pkeppeler.deepcharter.wreck.Wrecks;

/**
 * Server GameTests for #319: a pod's landing costs hull by its sink speed, not by the distance it fell. A pod that brakes with its
 * rotor survives the 192-block layer 1 shaft and a free fall of it wrecks the pod; the seated pilot takes no fall damage, the hull does.
 */
public class PodHardLandingTest {
	private static final Input JUMP = new Input(false, false, false, false, true, false, false);
	/** The rotor holds the pod while it sinks faster than this, in blocks per tick; under the pod's hard landing speed of 0.7. */
	private static final double BRAKE_ABOVE_SINK = 0.5;
	private static final int SHAFT_X = 2400;
	private static final int SHAFT_Z = 2400;
	/** Layer 1 spans y 0 to 191. The shaft is open from y 2 to the ceiling, over a floor of stone up to y 1. */
	private static final int SHAFT_FLOOR_TOP_Y = 1;
	private static final int SHAFT_TOP_Y = 191;
	private static final double SHAFT_SPAWN_Y = 189;
	/** Ticks of the pod's own fall or braked descent, on top of the wait for the chunk. */
	private static final int SHAFT_TICKS = 700;

	@GameTest
	public void landingDamageIsZeroAtTheThresholdAndScalesWithSpeedAbove(GameTestHelper helper) {
		PodStats stats = PodStats.base();
		// Independent literals: threshold 0.7 blocks per tick, 70 hull per block per tick above it.
		float[] expected = {0f, 0f, 35f, 70f, 140f};
		double[] speeds = {0, 0.7, 1.2, 1.7, 2.7};
		for (int i = 0; i < speeds.length; i++) {
			float damage = HardLanding.hullDamage(stats, speeds[i], 1f);
			if (Math.abs(damage - expected[i]) > 1e-3f) {
				throw failure(helper, "landing at %s blocks per tick should cost %s hull, costs %s", speeds[i], expected[i], damage);
			}
		}
		if (HardLanding.hullDamage(stats, 1.7, 0.5f) != 35f || HardLanding.sinkSpeed(0.4) != 0 || HardLanding.sinkSpeed(-1.5) != 1.5) {
			throw failure(helper, "the damage multiplier should scale the hull cost, and only a downward speed is a sink speed");
		}
		helper.succeed();
	}

	/** The distance rule cost 30 hull for a 10-block fall (4 free, 5 per block beyond); the speed rule costs about the same. */
	@GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + 200)
	public void aFreeFallOfTenBlocksCostsAboutWhatTheDistanceRuleDid(GameTestHelper helper) {
		int x = SHAFT_X + 200;
		ServerLevel one = shaft(helper, x, SHAFT_Z);
		Vec3 start = new Vec3(x + 0.5, SHAFT_FLOOR_TOP_Y + 1 + 10, SHAFT_Z + 0.5);
		PodEntity[] pod = {null};
		FarChunks.awaitEntityTicking(helper, one, BlockPos.containing(start), () -> pod[0] = spawn(one, start));
		helper.succeedWhen(() -> {
			if (pod[0] == null || !pod[0].onGround() || pod[0].tickCount < 5) {
				throw failure(helper, "the pod has not landed");
			}
			float lost = PodTuning.DEFAULT.shell().fullHull() - pod[0].hull();
			if (lost < 26f || lost > 36f) {
				throw failure(helper, "a free fall of 10 blocks should cost about 30 hull, cost %s", lost);
			}
			pod[0].discard();
		});
	}

	@GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + SHAFT_TICKS)
	public void aPodBrakingWithItsRotorSurvivesTheLayerOneShaftAndBurnsFuel(GameTestHelper helper) {
		ServerLevel one = shaft(helper, SHAFT_X, SHAFT_Z);
		Vec3 start = new Vec3(SHAFT_X + 0.5, SHAFT_SPAWN_Y, SHAFT_Z + 0.5);
		MockPlayer pilot = MockPlayers.join(helper, "shaft-braker");
		pilot.player().setGameMode(GameType.SURVIVAL);
		PodEntity[] pod = {null};
		FarChunks.awaitEntityTicking(helper, one, BlockPos.containing(start), () -> {
			pod[0] = spawn(one, start);
			// The pilot joins once the chunk ticks: a player put in an unloaded column falls out of the layer.
			pilot.teleportTo(one, start, 0f, 0f);
			if (!pilot.player().startRiding(pod[0], true, false)) {
				throw failure(helper, "the pilot could not board the pod");
			}
		});
		helper.onEachTick(() -> {
			if (pod[0] != null) {
				pilot.setInput(HardLanding.sinkSpeed(pod[0].getDeltaMovement().y) > BRAKE_ABOVE_SINK ? JUMP : Input.EMPTY);
			}
		});
		helper.succeedWhen(() -> {
			if (pod[0] == null || !pod[0].onGround() || pod[0].tickCount < 5) {
				throw failure(helper, "the pod has not landed");
			}
			PodEntity landed = pod[0];
			float idleBurn = landed.tickCount * PodFuel.drainPercentPerTick(PodStats.base(), PodFuel.Activity.IDLE);
			float burned = PodTuning.DEFAULT.shell().fullFuel() - landed.fuel();
			if (landed.hull() != PodTuning.DEFAULT.shell().fullHull()) {
				throw failure(helper, "a pod braked down 190 blocks should land unhurt, hull %s", landed.hull());
			}
			if (burned < idleBurn * 1.2f) {
				throw failure(helper, "braking against gravity should burn more fuel than idling (%s over %s ticks), burned %s", idleBurn, landed.tickCount, burned);
			}
			if (pilot.player().getHealth() < pilot.player().getMaxHealth()) {
				throw failure(helper, "the pilot should be unhurt, health %s", pilot.player().getHealth());
			}
			pilot.leave();
			landed.discard();
		});
	}

	@GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + SHAFT_TICKS)
	public void aFreeFallDownTheLayerOneShaftWrecksThePod(GameTestHelper helper) {
		int x = SHAFT_X + 100;
		ServerLevel one = shaft(helper, x, SHAFT_Z);
		Vec3 start = new Vec3(x + 0.5, SHAFT_SPAWN_Y, SHAFT_Z + 0.5);
		PodEntity[] pod = {null};
		FarChunks.awaitEntityTicking(helper, one, BlockPos.containing(start), () -> pod[0] = spawn(one, start));
		helper.succeedWhen(() -> {
			if (pod[0] == null || !pod[0].onGround() || pod[0].tickCount < 5) {
				throw failure(helper, "the pod has not landed");
			}
			if (!Wrecks.isWreck(pod[0]) || pod[0].hull() != 0f) {
				throw failure(helper, "a free fall of 190 blocks should wreck the pod, hull %s", pod[0].hull());
			}
			pod[0].discard();
		});
	}

	@GameTest(maxTicks = 20)
	public void aSeatedPilotTakesNoFallDamageButAPlayerOutsideAPodDoes(GameTestHelper helper) {
		PodEntity pod = helper.spawn(PodRegistry.POD, new Vec3(3.5, 2, 3.5));
		MockPlayer pilot = seat(helper, pod);
		MockPlayer walker = MockPlayers.join(helper, "fall-walker");
		walker.player().setGameMode(GameType.SURVIVAL);
		walker.teleportTo(helper.getLevel(), pod.position().add(5, 0, 0), 0f, 0f);
		ServerLevel level = helper.getLevel();
		boolean seatedHurt = pilot.player().hurtServer(level, level.damageSources().fall(), 5f);
		boolean walkerHurt = walker.player().hurtServer(level, level.damageSources().fall(), 5f);
		if (seatedHurt || pilot.player().getHealth() < pilot.player().getMaxHealth()) {
			throw failure(helper, "a seated pilot should take no fall damage, health %s", pilot.player().getHealth());
		}
		if (!walkerHurt || walker.player().getHealth() != walker.player().getMaxHealth() - 5f) {
			throw failure(helper, "a player outside a pod should take vanilla fall damage of 5, health %s", walker.player().getHealth());
		}
		pilot.leave();
		walker.leave();
		pod.discard();
		helper.succeed();
	}

	@GameTest(maxTicks = 20)
	public void theCrewOfAWreckTakeVanillaFallDamage(GameTestHelper helper) {
		PodEntity pod = helper.spawn(PodRegistry.POD, new Vec3(3.5, 2, 3.5));
		pod.setHull(0f);
		helper.runAfterDelay(2, () -> {
			MockPlayer crew = seat(helper, pod);
			if (!Wrecks.isWreck(pod)) {
				throw failure(helper, "the pod should be a wreck");
			}
			ServerLevel level = helper.getLevel();
			if (!crew.player().hurtServer(level, level.damageSources().fall(), 5f) || crew.player().getHealth() != crew.player().getMaxHealth() - 5f) {
				throw failure(helper, "the crew of a wreck should take vanilla fall damage of 5, health %s", crew.player().getHealth());
			}
			crew.leave();
			pod.discard();
			helper.succeed();
		});
	}

	private static MockPlayer seat(GameTestHelper helper, PodEntity pod) {
		MockPlayer pilot = MockPlayers.join(helper, "landing-pilot");
		pilot.player().setGameMode(GameType.SURVIVAL);
		pilot.teleportTo(helper.getLevel(), pod.position(), 0f, 0f);
		if (!pilot.player().startRiding(pod, true, false)) {
			throw failure(helper, "the pilot could not mount the pod");
		}
		return pilot;
	}

	private static PodEntity spawn(ServerLevel level, Vec3 at) {
		PodEntity pod = PodRegistry.POD.create(level, EntitySpawnReason.COMMAND);
		pod.setPos(at);
		level.addFreshEntity(pod);
		return pod;
	}

	/** A 3 by 3 shaft through the whole of layer 1 over a stone floor, cut through RoomCarver so its shell is sealed. */
	private static ServerLevel shaft(GameTestHelper helper, int x, int z) {
		ServerLevel one = helper.getLevel().getServer().getLevel(LayerChain.dimension(1));
		if (one == null) {
			throw failure(helper, "dimension %s did not load", LayerChain.dimension(1));
		}
		RoomCarver.carve(one, new BlockPos(x - 1, one.getMinY(), z - 1), new BlockPos(x + 1, SHAFT_FLOOR_TOP_Y, z + 1), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
		RoomCarver.carve(one, new BlockPos(x - 1, SHAFT_FLOOR_TOP_Y + 1, z - 1), new BlockPos(x + 1, SHAFT_TOP_Y, z + 1), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
		return one;
	}

	// assertionException(String, Object...) leaves the placeholders unfilled in the report.
	private static RuntimeException failure(GameTestHelper helper, String format, Object... args) {
		return helper.assertionException(Component.literal(String.format(format, args)));
	}
}
