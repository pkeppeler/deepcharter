package io.github.pkeppeler.deepcharter.test;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.DoubleConsumer;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.Vec3;

import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodEvents;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.pod.PodTuning;
import io.github.pkeppeler.deepcharter.test.support.MockPlayer;
import io.github.pkeppeler.deepcharter.test.support.MockPlayers;

/**
 * Server GameTests for {@link PodEvents}. Fabric events cannot be unregistered, so the listeners
 * here register once and act only on pods a test has put in one of the sets: every other pod in the
 * run sees the defaults.
 */
public class PodEventsTest {
	private static final int FLOOR_Y = 1;
	private static final int FLOOR_RADIUS = 3;
	/** The pod starts at z 3.5 with a 1.9-wide hull, so a wall at z 5 stops it after 0.55 blocks. */
	private static final int WALL_Z = 5;
	private static final Input JUMP = new Input(false, false, false, false, true, false, false);

	private static final Map<UUID, Integer> DEPLETED = new ConcurrentHashMap<>();
	private static final Map<UUID, Integer> TICKS = new ConcurrentHashMap<>();
	private static final Set<UUID> VETO_MOUNT = ConcurrentHashMap.newKeySet();
	private static final Set<UUID> UNPOWERED = ConcurrentHashMap.newKeySet();
	private static final Set<UUID> HEAVY = ConcurrentHashMap.newKeySet();
	private static final Set<UUID> NEGATIVE_MASS = ConcurrentHashMap.newKeySet();
	private static final Set<UUID> NAN_MASS = ConcurrentHashMap.newKeySet();
	private static final Set<UUID> PASS_THROUGH = ConcurrentHashMap.newKeySet();
	private static final Input FORWARD = new Input(true, false, false, false, false, false, false);

	static {
		PodEvents.HULL_DEPLETED.register(pod -> DEPLETED.merge(pod.getUUID(), 1, Integer::sum));
		PodEvents.AFTER_TICK.register(pod -> TICKS.merge(pod.getUUID(), 1, Integer::sum));
		PodEvents.CAN_MOUNT.register((pod, passenger) -> !VETO_MOUNT.contains(pod.getUUID()));
		PodEvents.IS_POWERED.register(pod -> !UNPOWERED.contains(pod.getUUID()));
		PodEvents.EXTRA_MASS.register(pod -> HEAVY.contains(pod.getUUID()) ? PodTuning.DEFAULT.movement().enginePower() : 0f);
		PodEvents.EXTRA_MASS.register(pod -> NEGATIVE_MASS.contains(pod.getUUID()) ? -1f : 0f);
		PodEvents.EXTRA_MASS.register(pod -> NAN_MASS.contains(pod.getUUID()) ? Float.NaN : 0f);
		PodEvents.IGNORES_BLOCK_COLLISION.register(pod -> PASS_THROUGH.contains(pod.getUUID()));
	}

	private static PodEntity spawnOnFloor(GameTestHelper helper) {
		for (int x = 0; x <= 2 * FLOOR_RADIUS; x++) {
			for (int z = 0; z <= 2 * FLOOR_RADIUS; z++) {
				helper.setBlock(new BlockPos(x, FLOOR_Y, z), Blocks.STONE);
			}
		}
		return helper.spawn(PodRegistry.POD, new Vec3(FLOOR_RADIUS + 0.5, FLOOR_Y + 1, FLOOR_RADIUS + 0.5));
	}

	private static void clearFloor(GameTestHelper helper) {
		for (int x = 0; x <= 2 * FLOOR_RADIUS; x++) {
			for (int z = 0; z <= 2 * FLOOR_RADIUS; z++) {
				helper.setBlock(new BlockPos(x, FLOOR_Y, z), Blocks.AIR);
			}
		}
	}

	private static RuntimeException failure(GameTestHelper helper, String format, Object... args) {
		return helper.assertionException(Component.literal(String.format(format, args)));
	}

	@GameTest
	public void withNoListenerEveryHookKeepsTheM1Rule(GameTestHelper helper) {
		PodEntity pod = helper.spawn(PodRegistry.POD, 2, 2, 2);
		MockPlayer mock = MockPlayers.join(helper, "events-default");
		try {
			if (!PodEvents.canMount(pod, mock.player())) {
				throw failure(helper, "canMount should default to true");
			}
			if (!PodEvents.isPowered(pod)) {
				throw failure(helper, "a fresh pod should be powered");
			}
			if (PodEvents.extraMass(pod) != 0f) {
				throw failure(helper, "extra mass should default to 0, got %s", PodEvents.extraMass(pod));
			}
			if (PodEvents.ignoresBlockCollision(pod)) {
				throw failure(helper, "a pod should collide with blocks by default");
			}
			pod.setStranded(true);
			if (PodEvents.isPowered(pod)) {
				throw failure(helper, "a stranded pod is powered off whatever the listeners say");
			}
			pod.setStranded(false);
			InteractionResult result = pod.interact(mock.player(), InteractionHand.MAIN_HAND, Vec3.ZERO);
			if (!result.consumesAction() || mock.player().getVehicle() != pod) {
				throw failure(helper, "using a pod should still board it, got %s", result);
			}
			if (DEPLETED.containsKey(pod.getUUID())) {
				throw failure(helper, "a pod with hull left must not report depletion");
			}
			helper.succeed();
		} finally {
			mock.leave();
			pod.discard();
		}
	}

	@GameTest
	public void hullDepletedFiresOnceWhenTheHullRunsOut(GameTestHelper helper) {
		PodEntity pod = helper.spawn(PodRegistry.POD, 2, 2, 2);
		try {
			pod.setHull(50f);
			if (DEPLETED.containsKey(pod.getUUID())) {
				throw failure(helper, "damage that leaves hull must not fire the event");
			}
			pod.setHull(0f);
			pod.setHull(0f);
			if (DEPLETED.getOrDefault(pod.getUUID(), 0) != 1) {
				throw failure(helper, "reaching 0 hull should fire once, fired %s", DEPLETED.get(pod.getUUID()));
			}
			helper.succeed();
		} finally {
			pod.discard();
		}
	}

	@GameTest
	public void loadingAPodWithNoHullIsNotADepletion(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		PodEntity pod = helper.spawn(PodRegistry.POD, 2, 2, 2);
		pod.setHull(0f);
		UUID id = pod.getUUID();
		TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, level.registryAccess());
		pod.saveWithoutId(output);
		pod.discard();
		Entity loaded = EntityType.create(PodRegistry.POD,
				TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), output.buildResult()),
				level, EntitySpawnReason.LOAD).orElseThrow(() -> failure(helper, "the saved pod did not load"));
		try {
			if (((PodEntity) loaded).hull() != 0f) {
				throw failure(helper, "the loaded pod should still have no hull");
			}
			if (DEPLETED.get(id) != 1) {
				throw failure(helper, "loading must not fire the event again, fired %s", DEPLETED.get(id));
			}
			helper.succeed();
		} finally {
			loaded.discard();
		}
	}

	@GameTest
	public void aMountVetoStopsBoardingUntilItIsLifted(GameTestHelper helper) {
		PodEntity pod = helper.spawn(PodRegistry.POD, 2, 2, 2);
		MockPlayer mock = MockPlayers.join(helper, "events-veto");
		VETO_MOUNT.add(pod.getUUID());
		try {
			InteractionResult result = pod.interact(mock.player(), InteractionHand.MAIN_HAND, Vec3.ZERO);
			if (result.consumesAction() || mock.player().getVehicle() != null) {
				throw failure(helper, "a vetoed pod must not board the player, got %s", result);
			}
			if (mock.player().startRiding(pod)) {
				throw failure(helper, "startRiding must respect the veto");
			}
			VETO_MOUNT.remove(pod.getUUID());
			if (!pod.interact(mock.player(), InteractionHand.MAIN_HAND, Vec3.ZERO).consumesAction() || mock.player().getVehicle() != pod) {
				throw failure(helper, "the pod should board the player once the veto is lifted");
			}
			helper.succeed();
		} finally {
			VETO_MOUNT.remove(pod.getUUID());
			mock.leave();
			pod.discard();
		}
	}

	@GameTest
	public void anUnpoweredPodIgnoresItsPilotAndBurnsNoFuel(GameTestHelper helper) {
		PodEntity pod = spawnOnFloor(helper);
		MockPlayer pilot = MockPlayers.join(helper, "events-unpowered");
		pilot.teleportTo(helper.getLevel(), pod.position(), 0f, 0f);
		if (!pilot.player().startRiding(pod)) {
			throw failure(helper, "the pilot could not mount the pod");
		}
		pod.setFuel(50f);
		double startY = pod.getY();
		UNPOWERED.add(pod.getUUID());
		pilot.setInput(JUMP);
		helper.runAfterDelay(10, () -> {
			try {
				if (pod.getY() - startY > 1e-6 || pod.flying()) {
					throw failure(helper, "an unpowered pod must not lift, rose %s", pod.getY() - startY);
				}
				if (pod.fuel() != 50f) {
					throw failure(helper, "an unpowered pod must burn no fuel, fuel is %s", pod.fuel());
				}
				UNPOWERED.remove(pod.getUUID());
			} catch (RuntimeException e) {
				UNPOWERED.remove(pod.getUUID());
				pilot.leave();
				pod.discard();
				clearFloor(helper);
				throw e;
			}
			helper.runAfterDelay(10, () -> {
				try {
					if (pod.getY() - startY < 0.5 || pod.fuel() >= 50f) {
						throw failure(helper, "the pod should lift and burn fuel once powered, rose %s, fuel %s", pod.getY() - startY, pod.fuel());
					}
					helper.succeed();
				} finally {
					pilot.leave();
					pod.discard();
					clearFloor(helper);
				}
			});
		});
	}

	@GameTest
	public void extraMassCutsLiftLikeCargoDoes(GameTestHelper helper) {
		PodEntity pod = spawnOnFloor(helper);
		MockPlayer pilot = MockPlayers.join(helper, "events-heavy");
		pilot.teleportTo(helper.getLevel(), pod.position(), 0f, 0f);
		if (!pilot.player().startRiding(pod)) {
			throw failure(helper, "the pilot could not mount the pod");
		}
		HEAVY.add(pod.getUUID());
		double startY = pod.getY();
		pilot.setInput(JUMP);
		helper.runAfterDelay(10, () -> {
			try {
				if (pod.getY() - startY > 1e-6 || pod.flying()) {
					throw failure(helper, "a pod with extra mass equal to its engine power must not lift, rose %s", pod.getY() - startY);
				}
				helper.succeed();
			} finally {
				HEAVY.remove(pod.getUUID());
				pilot.leave();
				pod.discard();
				clearFloor(helper);
			}
		});
	}

	@GameTest
	public void aNegativeExtraMassFailsLoudly(GameTestHelper helper) {
		assertExtraMassRefused(helper, NEGATIVE_MASS, "a negative extra mass must be refused");
	}

	@GameTest
	public void aNaNExtraMassFailsLoudly(GameTestHelper helper) {
		assertExtraMassRefused(helper, NAN_MASS, "a NaN extra mass must be refused");
	}

	private static void assertExtraMassRefused(GameTestHelper helper, Set<UUID> badListener, String message) {
		PodEntity pod = helper.spawn(PodRegistry.POD, 2, 2, 2);
		badListener.add(pod.getUUID());
		try {
			try {
				PodEvents.extraMass(pod);
			} catch (IllegalStateException expected) {
				helper.succeed();
				return;
			}
			throw failure(helper, message);
		} finally {
			badListener.remove(pod.getUUID());
			pod.discard();
		}
	}

	@GameTest
	public void aPodStopsAtAWallByDefault(GameTestHelper helper) {
		driveIntoWall(helper, false, moved -> {
			if (moved > 1.0) {
				throw failure(helper, "a pod must stop at the wall by default, moved %s south", moved);
			}
		});
	}

	@GameTest
	public void aListenerLetsAPodPassThroughABlock(GameTestHelper helper) {
		driveIntoWall(helper, true, moved -> {
			if (moved < 1.5) {
				throw failure(helper, "a pod told to ignore block collision should pass through the wall, moved only %s south", moved);
			}
		});
	}

	/** Drives a pod south into a wall for ten ticks, then hands the distance it moved to {@code check}. */
	private static void driveIntoWall(GameTestHelper helper, boolean passThrough, DoubleConsumer check) {
		PodEntity pod = spawnOnFloor(helper);
		setWall(helper, Blocks.STONE);
		MockPlayer pilot = MockPlayers.join(helper, "events-wall");
		pilot.teleportTo(helper.getLevel(), pod.position(), 0f, 0f);
		if (!pilot.player().startRiding(pod)) {
			throw failure(helper, "the pilot could not mount the pod");
		}
		double startZ = pod.getZ();
		if (passThrough) {
			PASS_THROUGH.add(pod.getUUID());
		}
		pilot.setInput(FORWARD);
		helper.runAfterDelay(10, () -> {
			try {
				check.accept(pod.getZ() - startZ);
				helper.succeed();
			} finally {
				PASS_THROUGH.remove(pod.getUUID());
				pilot.leave();
				pod.discard();
				clearFloor(helper);
				setWall(helper, Blocks.AIR);
			}
		});
	}

	/** A wall across the floor's south half, which a pod driving south from the middle hits after about half a block. */
	private static void setWall(GameTestHelper helper, Block block) {
		for (int x = 0; x <= 2 * FLOOR_RADIUS; x++) {
			for (int y = FLOOR_Y + 1; y <= FLOOR_Y + 3; y++) {
				helper.setBlock(new BlockPos(x, y, WALL_Z), block);
			}
		}
	}

	@GameTest
	public void afterTickFiresOncePerPodTick(GameTestHelper helper) {
		PodEntity pod = helper.spawn(PodRegistry.POD, 2, 2, 2);
		helper.runAfterDelay(10, () -> {
			try {
				int fired = TICKS.getOrDefault(pod.getUUID(), 0);
				if (fired == 0 || fired != pod.tickCount) {
					throw failure(helper, "AFTER_TICK should fire once per tick: fired %s times in %s ticks", fired, pod.tickCount);
				}
				helper.succeed();
			} finally {
				pod.discard();
			}
		});
	}
}
