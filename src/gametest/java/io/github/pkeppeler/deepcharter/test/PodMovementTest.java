package io.github.pkeppeler.deepcharter.test;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodMovement;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.pod.PodStats;
import io.github.pkeppeler.deepcharter.pod.PodTuning;
import io.github.pkeppeler.deepcharter.test.support.MockPlayer;
import io.github.pkeppeler.deepcharter.test.support.MockPlayers;

/** Server GameTests for pod movement and flight rules, driven by a mock pilot's input. */
public class PodMovementTest {
	private static final int FLOOR_Y = 1;
	private static final int FLOOR_RADIUS = 3;
	private static final double EPSILON = 1e-6;
	private static final float SOUTH = 0f;
	private static final float WEST = 90f;

	private static final Input FORWARD = new Input(true, false, false, false, false, false, false);
	private static final Input FORWARD_RIGHT = new Input(true, false, false, true, false, false, false);
	private static final Input RIGHT = new Input(false, false, false, true, false, false, false);
	private static final Input JUMP = new Input(false, false, false, false, true, false, false);
	private static final Input FORWARD_JUMP = new Input(true, false, false, false, true, false, false);

	/** A pod standing on a small stone floor, with a mock pilot seated and looking along {@code yaw}. */
	private record Rig(GameTestHelper helper, PodEntity pod, MockPlayer pilot) {
		static Rig build(GameTestHelper helper, double height, float yaw) {
			PodEntity pod = buildEmpty(helper, height);
			MockPlayer pilot = MockPlayers.join(helper, "pod-pilot");
			pilot.teleportTo(helper.getLevel(), pod.position(), yaw, 0f);
			if (!pilot.player().startRiding(pod)) {
				throw helper.assertionException("the pilot could not mount the pod");
			}
			return new Rig(helper, pod, pilot);
		}

		/** Build a pod with no pilot, high above the floor, to watch a fall. */
		static PodEntity buildEmpty(GameTestHelper helper, double height) {
			fillFloor(helper, Blocks.STONE);
			return helper.spawn(PodRegistry.POD, new Vec3(FLOOR_RADIUS + 0.5, FLOOR_Y + 1 + height, FLOOR_RADIUS + 0.5));
		}

		void finish() {
			pilot.leave();
			pod.discard();
			clearFloor(helper);
		}
	}

	private static void clearFloor(GameTestHelper helper) {
		fillFloor(helper, Blocks.AIR);
	}

	private static void fillFloor(GameTestHelper helper, Block block) {
		for (int x = -FLOOR_RADIUS; x <= FLOOR_RADIUS; x++) {
			for (int z = -FLOOR_RADIUS; z <= FLOOR_RADIUS; z++) {
				helper.setBlock(new BlockPos(x + FLOOR_RADIUS, FLOOR_Y, z + FLOOR_RADIUS), block);
			}
		}
	}

	@GameTest
	public void forwardDrivesTheCameraAxis(GameTestHelper helper) {
		Rig rig = Rig.build(helper, 0, WEST);
		Vec3 start = rig.pod.position();
		rig.pilot.setInput(FORWARD);
		helper.runAfterDelay(10, () -> {
			try {
				Vec3 moved = rig.pod.position().subtract(start);
				if (moved.x > -1.0 || Math.abs(moved.z) > EPSILON) {
					throw helper.assertionException("facing west, forward should drive the pod west along x only, moved %s", moved);
				}
				helper.succeed();
			} finally {
				rig.finish();
			}
		});
	}

	@GameTest
	public void diagonalInputMovesAlongOneAxisOnly(GameTestHelper helper) {
		Rig rig = Rig.build(helper, 0, SOUTH);
		Vec3 start = rig.pod.position();
		rig.pilot.setInput(FORWARD_RIGHT);
		helper.runAfterDelay(10, () -> {
			try {
				Vec3 moved = rig.pod.position().subtract(start);
				boolean alongX = Math.abs(moved.x) > 1.0 && Math.abs(moved.z) < EPSILON;
				boolean alongZ = Math.abs(moved.z) > 1.0 && Math.abs(moved.x) < EPSILON;
				if (!alongX && !alongZ) {
					throw helper.assertionException("forward and right together must move along one axis, moved %s", moved);
				}
				helper.succeed();
			} finally {
				rig.finish();
			}
		});
	}

	@GameTest
	public void switchingAxisLeavesNoDiagonalDrift(GameTestHelper helper) {
		Rig rig = Rig.build(helper, 0, SOUTH);
		rig.pilot.setInput(FORWARD);
		helper.runAfterDelay(5, () -> {
			Vec3 mid = rig.pod.position();
			rig.pilot.setInput(RIGHT);
			helper.runAfterDelay(5, () -> {
				try {
					Vec3 moved = rig.pod.position().subtract(mid);
					if (Math.abs(moved.z) > EPSILON || Math.abs(moved.x) < 0.5) {
						throw helper.assertionException("after switching to strafe the pod should move along x only, moved %s", moved);
					}
					helper.succeed();
				} finally {
					rig.finish();
				}
			});
		});
	}

	@GameTest
	public void releasedInputStopsThePodOnTheGround(GameTestHelper helper) {
		Rig rig = Rig.build(helper, 0, SOUTH);
		rig.pilot.setInput(FORWARD);
		helper.runAfterDelay(5, () -> {
			rig.pilot.releaseInput();
			helper.runAfterDelay(2, () -> {
				Vec3 stopped = rig.pod.position();
				helper.runAfterDelay(5, () -> {
					try {
						if (rig.pod.position().distanceTo(stopped) > EPSILON) {
							throw helper.assertionException("the pod should stand still with no input, drifted from %s to %s",
									stopped, rig.pod.position());
						}
						helper.succeed();
					} finally {
						rig.finish();
					}
				});
			});
		});
	}

	@GameTest(maxTicks = 60)
	public void climbsWhileJumpIsHeldAndFallsWhenReleased(GameTestHelper helper) {
		Rig rig = Rig.build(helper, 0, SOUTH);
		double startY = rig.pod.getY();
		rig.pilot.setInput(JUMP);
		helper.runAfterDelay(15, () -> {
			double peak = rig.pod.getY();
			if (peak - startY < 1.0 || !rig.pod.flying()) {
				rig.finish();
				throw helper.assertionException("holding jump should climb at least a block with the rotor on, rose %s, flying %s",
						peak - startY, rig.pod.flying());
			}
			rig.pilot.releaseInput();
			helper.runAfterDelay(8, () -> {
				try {
					if (rig.pod.getY() >= peak - 0.2 || rig.pod.flying()) {
						throw helper.assertionException("releasing jump should let the pod fall, peak %s now %s, flying %s",
								peak, rig.pod.getY(), rig.pod.flying());
					}
					helper.succeed();
				} finally {
					rig.finish();
				}
			});
		});
	}

	@GameTest
	public void cargoMassCutsLift(GameTestHelper helper) {
		Rig rig = Rig.build(helper, 0, SOUTH);
		rig.pod.setCargoMass(PodTuning.DEFAULT.movement().enginePower());
		double startY = rig.pod.getY();
		rig.pilot.setInput(JUMP);
		helper.runAfterDelay(10, () -> {
			try {
				if (rig.pod.getY() - startY > EPSILON || rig.pod.flying()) {
					throw helper.assertionException("a pod loaded to its engine power should not lift, rose %s", rig.pod.getY() - startY);
				}
				helper.succeed();
			} finally {
				rig.finish();
			}
		});
	}

	@GameTest(maxTicks = 100)
	public void hardLandingDamagesHullAndNotThePilot(GameTestHelper helper) {
		Rig rig = Rig.build(helper, 12, SOUTH);
		float hullBefore = rig.pod.hull();
		float healthBefore = rig.pilot.player().getHealth();
		helper.runAfterDelay(50, () -> {
			try {
				if (!rig.pod.onGround()) {
					throw helper.assertionException("the pod should have landed by now, at y %s", rig.pod.getY());
				}
				if (rig.pod.hull() >= hullBefore) {
					throw helper.assertionException("a fall of about 12 blocks should damage the hull, hull %s", rig.pod.hull());
				}
				if (rig.pilot.player().getHealth() != healthBefore) {
					throw helper.assertionException("the seated pilot should take no fall damage, health %s", rig.pilot.player().getHealth());
				}
				helper.succeed();
			} finally {
				rig.finish();
			}
		});
	}

	@GameTest(maxTicks = 100)
	public void landingStopsTheFallSpeed(GameTestHelper helper) {
		PodEntity pod = Rig.buildEmpty(helper, 3);
		helper.runAfterDelay(30, () -> {
			try {
				if (!pod.onGround() || pod.getDeltaMovement().y != 0) {
					throw helper.assertionException("a landed pod should rest with no vertical speed, onGround %s, velocity %s",
							pod.onGround(), pod.getDeltaMovement());
				}
				helper.succeed();
			} finally {
				pod.discard();
				clearFloor(helper);
			}
		});
	}

	@GameTest(maxTicks = 100)
	public void ceilingStopsTheClimb(GameTestHelper helper) {
		Rig rig = Rig.build(helper, 0, SOUTH);
		BlockPos ceiling = new BlockPos(FLOOR_RADIUS, FLOOR_Y + 5, FLOOR_RADIUS);
		helper.setBlock(ceiling, Blocks.STONE);
		double limit = helper.absolutePos(ceiling).getY() - rig.pod.getBbHeight();
		rig.pilot.setInput(JUMP);
		helper.runAfterDelay(40, () -> {
			try {
				if (rig.pod.getY() > limit + EPSILON || rig.pod.getY() < limit - 0.2 || rig.pod.getDeltaMovement().y > 0) {
					throw helper.assertionException("the pod should stop against the ceiling at y %s, y %s, velocity %s",
							limit, rig.pod.getY(), rig.pod.getDeltaMovement());
				}
				helper.succeed();
			} finally {
				helper.setBlock(ceiling, Blocks.AIR);
				rig.finish();
			}
		});
	}

	@GameTest
	public void negativeCargoMassFailsLoud(GameTestHelper helper) {
		PodEntity pod = Rig.buildEmpty(helper, 0);
		try {
			pod.setCargoMass(-1f);
			try {
				PodMovement.tick(pod, PodStats.of(pod));
			} catch (IllegalStateException expected) {
				helper.succeed();
				return;
			} finally {
				pod.setCargoMass(0f);
			}
			throw helper.assertionException("a negative cargo mass must throw, not add lift");
		} finally {
			pod.discard();
			clearFloor(helper);
		}
	}

	@GameTest(maxTicks = 100)
	public void softLandingLeavesHullIntact(GameTestHelper helper) {
		PodEntity pod = Rig.buildEmpty(helper, 2);
		float hullBefore = pod.hull();
		helper.runAfterDelay(30, () -> {
			try {
				if (!pod.onGround()) {
					throw helper.assertionException("the pod should have landed by now, at y %s", pod.getY());
				}
				if (pod.hull() != hullBefore) {
					throw helper.assertionException("a drop of about 2 blocks should not damage the hull, hull %s", pod.hull());
				}
				helper.succeed();
			} finally {
				pod.discard();
				clearFloor(helper);
			}
		});
	}

	@GameTest
	public void strandedPodIgnoresInput(GameTestHelper helper) {
		Rig rig = Rig.build(helper, 0, SOUTH);
		rig.pod.setStranded(true);
		Vec3 start = rig.pod.position();
		rig.pilot.setInput(FORWARD_JUMP);
		helper.runAfterDelay(10, () -> {
			try {
				if (rig.pod.position().distanceTo(start) > EPSILON || rig.pod.flying()) {
					throw helper.assertionException("a stranded pod should ignore input, moved from %s to %s", start, rig.pod.position());
				}
				helper.succeed();
			} finally {
				rig.finish();
			}
		});
	}
}
