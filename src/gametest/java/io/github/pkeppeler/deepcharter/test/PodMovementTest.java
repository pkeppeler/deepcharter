package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.Failures.failure;
import static io.github.pkeppeler.deepcharter.test.support.Inputs.BACK;
import static io.github.pkeppeler.deepcharter.test.support.Inputs.BACK_LEFT;
import static io.github.pkeppeler.deepcharter.test.support.Inputs.FORWARD;
import static io.github.pkeppeler.deepcharter.test.support.Inputs.FORWARD_BACK;
import static io.github.pkeppeler.deepcharter.test.support.Inputs.FORWARD_JUMP;
import static io.github.pkeppeler.deepcharter.test.support.Inputs.JUMP;
import static io.github.pkeppeler.deepcharter.test.support.Inputs.LEFT;
import static io.github.pkeppeler.deepcharter.test.support.Inputs.RIGHT;

import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodMovement;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.pod.PodStats;
import io.github.pkeppeler.deepcharter.pod.PodTuning;
import io.github.pkeppeler.deepcharter.test.support.MockPlayer;
import io.github.pkeppeler.deepcharter.test.support.MockPlayers;
import io.github.pkeppeler.deepcharter.test.support.TestLayers;
import io.github.pkeppeler.deepcharter.test.support.TestTicks;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/** Server GameTests for pod movement and flight rules, driven by a mock pilot's input. */
public class PodMovementTest {
    private static final int FLOOR_Y = TestLayers.worldY(TestLayers.SURFACE, 1);
    private static final int FLOOR_RADIUS = 3;
    private static final double EPSILON = 1e-6;
    /** A rig pod thrown down this fast lands hard through the ground cushion (#1432), which brakes a crewed sink. */
    private static final double HARD_SINK = 1.5;

    private static final float SOUTH = 0f;
    private static final float WEST = 90f;

    /** Where the pilot looks: never the pod's facing, because the pilot's view does not steer a pod. */
    private static final float PILOT_LOOKS = 137f;

    /** A pod standing on a small stone floor and facing {@code yaw}, with a mock pilot seated and looking elsewhere. */
    private record Rig(GameTestHelper helper, PodEntity pod, MockPlayer pilot) {
        static Rig build(GameTestHelper helper, double height, float yaw) {
            PodEntity pod = buildEmpty(helper, height);
            MockPlayer pilot = MockPlayers.join(helper, "pod-pilot");
            pod.setYRot(yaw);
            pilot.teleportTo(helper.getLevel(), pod.position(), PILOT_LOOKS, 0f);
            if (!pilot.player().startRiding(pod)) {
                throw failure(helper, "the pilot could not mount the pod");
            }
            return new Rig(helper, pod, pilot);
        }

        /** Build a pod with no pilot, high above the floor, to watch a fall. */
        static PodEntity buildEmpty(GameTestHelper helper, double height) {
            fillFloor(helper, Blocks.STONE);
            return helper.spawn(
                    PodRegistry.POD, new Vec3(FLOOR_RADIUS + 0.5, FLOOR_Y + 1 + height, FLOOR_RADIUS + 0.5));
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
    public void forwardDrivesAlongThePodsFacingNotThePilotsView(GameTestHelper helper) {
        Rig rig = Rig.build(helper, 0, WEST);
        Vec3 start = rig.pod.position();
        rig.pilot.setInput(FORWARD);
        // Five ticks keep the pod on the small floor at the cruise speed.
        helper.runAfterDelay(5, () -> {
            try {
                Vec3 moved = rig.pod.position().subtract(start);
                if (moved.x > -1.0 || Math.abs(moved.z) > EPSILON) {
                    throw failure(
                            helper,
                            "the pod faces west, so forward drives it west along x only, whatever the pilot looks at; moved %s",
                            moved);
                }
                if (rig.pod.getYRot() != WEST) {
                    throw failure(helper, "driving forward must not turn the pod, its yaw is %s", rig.pod.getYRot());
                }
                helper.succeed();
            } finally {
                rig.finish();
            }
        });
    }

    @GameTest
    public void backReversesSlowlyWithoutTurning(GameTestHelper helper) {
        Rig rig = Rig.build(helper, 0, SOUTH);
        Vec3 start = rig.pod.position();
        rig.pilot.setInput(BACK);
        helper.runAfterDelay(10, () -> {
            try {
                Vec3 moved = rig.pod.position().subtract(start);
                double topSpeed = PodTuning.DEFAULT.movement().horizontalSpeed() * 10;
                if (moved.z > -0.5 || Math.abs(moved.x) > EPSILON) {
                    throw failure(helper, "the pod faces south, so back drives it north along z only, moved %s", moved);
                }
                if (-moved.z > topSpeed * 0.6) {
                    throw failure(
                            helper,
                            "reversing is slower than driving forward (%s in 10 ticks), moved %s",
                            topSpeed,
                            moved);
                }
                if (rig.pod.getYRot() != SOUTH) {
                    throw failure(helper, "reversing must not turn the pod, its yaw is %s", rig.pod.getYRot());
                }
                helper.succeed();
            } finally {
                rig.finish();
            }
        });
    }

    @GameTest(maxTicks = 100)
    public void leftAndRightSteerTheYawLikeABoat(GameTestHelper helper) {
        Rig rig = Rig.build(helper, 0, SOUTH);
        rig.pilot.setInput(LEFT);
        helper.runAfterDelay(10, () -> {
            float left = Mth.wrapDegrees(rig.pod.getYRot() - SOUTH);
            rig.pilot.setInput(RIGHT);
            helper.runAfterDelay(20, () -> {
                try {
                    float right = Mth.wrapDegrees(rig.pod.getYRot() - SOUTH);
                    if (left > -10f || left < -90f) {
                        throw failure(
                                helper, "ten ticks of A should turn the pod left (a lower yaw), yaw change %s", left);
                    }
                    if (right <= left + 20f) {
                        throw failure(
                                helper,
                                "D should turn the pod back and on, yaw change %s after A gave %s",
                                right,
                                left);
                    }
                    helper.succeed();
                } finally {
                    rig.finish();
                }
            });
        });
    }

    @GameTest(maxTicks = 100)
    public void steeringSpeedsUpToTheBoatsTurnRate(GameTestHelper helper) {
        Rig rig = Rig.build(helper, 0, SOUTH);
        rig.pilot.setInput(RIGHT);
        float[] last = {SOUTH};
        float[] first = {0f};
        int[] ticks = {0};
        TestTicks.each(helper, () -> {
            float turned = Mth.wrapDegrees(rig.pod.getYRot() - last[0]);
            last[0] = rig.pod.getYRot();
            if (++ticks[0] == 3) {
                first[0] = turned;
            }
            if (ticks[0] < 50) {
                return;
            }
            try {
                // Vanilla's boat adds 1 degree a tick of turn rate and keeps 0.9 of it: it settles at 10 degrees a
                // tick.
                if (first[0] > 4f || turned < 8f || turned > 10.5f) {
                    throw failure(
                            helper,
                            "the turn rate should build up (%s on the third tick) to about 10 degrees a tick, it is %s",
                            first[0],
                            turned);
                }
                helper.succeed();
            } finally {
                rig.finish();
            }
        });
    }

    @GameTest
    public void drivingFollowsTheHeadingAfterATurn(GameTestHelper helper) {
        Rig rig = Rig.build(helper, 0, SOUTH);
        // A bare D turns the pod while it creeps, so the pod stays on the small floor at the cruise speed.
        rig.pilot.setInput(RIGHT);
        helper.runAfterDelay(6, () -> {
            rig.pilot.setInput(FORWARD);
            helper.runAfterDelay(2, () -> {
                Vec3 before = rig.pod.position();
                helper.runAfterDelay(2, () -> {
                    try {
                        float turned = Mth.wrapDegrees(rig.pod.getYRot() - SOUTH);
                        Vec3 step = rig.pod.position().subtract(before).multiply(1, 0, 1);
                        Vec3 facing = Vec3.directionFromRotation(0, rig.pod.getYRot());
                        if (turned < 10f || step.normalize().dot(facing) < 0.99) {
                            throw failure(
                                    helper,
                                    "after turning right (%s degrees) the pod drives along its new facing %s, it moved %s",
                                    turned,
                                    facing,
                                    step);
                        }
                        helper.succeed();
                    } finally {
                        rig.finish();
                    }
                });
            });
        });
    }

    /**
     * The boat reverses with S whatever A or D do, and turns while it does: so does the pod (unlike W with S, which
     * cancels).
     */
    @GameTest(maxTicks = 100)
    public void backWithLeftReversesWhileTurning(GameTestHelper helper) {
        Rig rig = Rig.build(helper, 0, SOUTH);
        Vec3 start = rig.pod.position();
        rig.pilot.setInput(BACK_LEFT);
        helper.runAfterDelay(10, () -> {
            try {
                Vec3 moved = rig.pod.position().subtract(start);
                float turned = Mth.wrapDegrees(rig.pod.getYRot() - SOUTH);
                if (moved.z > -0.2 || turned > -10f) {
                    throw failure(
                            helper,
                            "S with A backs the pod up (north) and turns it left, moved %s, turned %s",
                            moved,
                            turned);
                }
                helper.succeed();
            } finally {
                rig.finish();
            }
        });
    }

    @GameTest
    public void forwardAndBackTogetherCancel(GameTestHelper helper) {
        Rig rig = Rig.build(helper, 0, SOUTH);
        Vec3 start = rig.pod.position();
        rig.pilot.setInput(FORWARD_BACK);
        helper.runAfterDelay(10, () -> {
            try {
                if (rig.pod.position().distanceTo(start) > EPSILON) {
                    throw failure(
                            helper,
                            "W with S is no throttle, the pod moved %s",
                            rig.pod.position().subtract(start));
                }
                helper.succeed();
            } finally {
                rig.finish();
            }
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
                            throw failure(
                                    helper,
                                    "the pod should stand still with no input, drifted from %s to %s",
                                    stopped,
                                    rig.pod.position());
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
                throw failure(
                        helper,
                        "holding jump should climb at least a block with the rotor on, rose %s, flying %s",
                        peak - startY,
                        rig.pod.flying());
            }
            rig.pilot.releaseInput();
            helper.runAfterDelay(8, () -> {
                try {
                    if (rig.pod.getY() >= peak - 0.2 || rig.pod.flying()) {
                        throw failure(
                                helper,
                                "releasing jump should let the pod fall, peak %s now %s, flying %s",
                                peak,
                                rig.pod.getY(),
                                rig.pod.flying());
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
                    throw failure(
                            helper,
                            "a pod loaded to its engine power should not lift, rose %s",
                            rig.pod.getY() - startY);
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
        rig.pod.setDeltaMovement(0, -HARD_SINK, 0);
        float hullBefore = rig.pod.hull();
        float healthBefore = rig.pilot.player().getHealth();
        helper.runAfterDelay(50, () -> {
            try {
                if (!rig.pod.onGround()) {
                    throw failure(helper, "the pod should have landed by now, at y %s", rig.pod.getY());
                }
                if (rig.pod.hull() >= hullBefore) {
                    throw failure(helper, "a fall of about 12 blocks should damage the hull, hull %s", rig.pod.hull());
                }
                if (rig.pilot.player().getHealth() != healthBefore) {
                    throw failure(
                            helper,
                            "the seated pilot should take no fall damage, health %s",
                            rig.pilot.player().getHealth());
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
                    throw failure(
                            helper,
                            "a landed pod should rest with no vertical speed, onGround %s, velocity %s",
                            pod.onGround(),
                            pod.getDeltaMovement());
                }
                helper.succeed();
            } finally {
                pod.discard();
                clearFloor(helper);
            }
        });
    }

    @GameTest(maxTicks = 100)
    public void noGravityPodStaysWhereItIsHeld(GameTestHelper helper) {
        PodEntity pod = Rig.buildEmpty(helper, 3);
        pod.setNoGravity(true);
        double startY = pod.getY();
        helper.runAfterDelay(30, () -> {
            try {
                if (pod.getY() != startY || pod.getDeltaMovement().y != 0) {
                    throw failure(
                            helper,
                            "a pod with no gravity should hold at y %s with no vertical speed, got y %s, velocity %s",
                            startY,
                            pod.getY(),
                            pod.getDeltaMovement());
                }
                helper.succeed();
            } finally {
                pod.discard();
                clearFloor(helper);
            }
        });
    }

    /** A no-gravity pod's launch speed decays by the drag each tick, and the pod ends well clear of the floor. */
    @GameTest(maxTicks = 100)
    public void noGravityPodCoastsOnItsSpeedAndDragAlone(GameTestHelper helper) {
        PodEntity pod = Rig.buildEmpty(helper, 6);
        pod.setNoGravity(true);
        double launch = -0.1;
        pod.setDeltaMovement(0, launch, 0);
        double startY = pod.getY();
        double drag = PodTuning.Movement.DEFAULT.verticalDrag();
        int coastTicks = 40;
        int[] ticks = {0};
        TestTicks.each(helper, () -> {
            if (++ticks[0] != coastTicks) {
                return;
            }
            try {
                double expected = launch * Math.pow(drag, coastTicks);
                double speed = pod.getDeltaMovement().y;
                double dropped = startY - pod.getY();
                if (Math.abs(speed - expected) > 0.002) {
                    throw failure(
                            helper,
                            "after %s ticks the speed should have decayed from %s to about %s, it is %s",
                            coastTicks,
                            launch,
                            expected,
                            speed);
                }
                // The distance a coast of this length covers is the launch speed summed with the drag, 2.8 blocks here.
                if (dropped < 2.5 || dropped > 3.1 || pod.getY() - (FLOOR_Y + 1) < 2) {
                    throw failure(
                            helper,
                            "a coast should drop the pod about 2.8 blocks and leave it in the air, it dropped %s to y %s",
                            dropped,
                            pod.getY());
                }
                helper.succeed();
            } finally {
                if (ticks[0] == coastTicks) {
                    pod.discard();
                    clearFloor(helper);
                }
            }
        });
    }

    @GameTest(maxTicks = 100)
    public void podWithGravityStillFalls(GameTestHelper helper) {
        PodEntity pod = Rig.buildEmpty(helper, 3);
        double startY = pod.getY();
        helper.runAfterDelay(5, () -> {
            try {
                if (pod.getY() >= startY) {
                    throw failure(helper, "a pod with gravity should fall from y %s, got y %s", startY, pod.getY());
                }
                helper.succeed();
            } finally {
                pod.discard();
                clearFloor(helper);
            }
        });
    }

    @GameTest(maxTicks = 100)
    public void noGravityPodStillClimbsOnThrust(GameTestHelper helper) {
        Rig rig = Rig.build(helper, 3, SOUTH);
        rig.pod.setNoGravity(true);
        double startY = rig.pod.getY();
        rig.pilot.setInput(JUMP);
        helper.runAfterDelay(10, () -> {
            try {
                if (rig.pod.getY() <= startY) {
                    throw failure(
                            helper,
                            "a no-gravity pod should still climb on thrust from y %s, got y %s",
                            startY,
                            rig.pod.getY());
                }
                helper.succeed();
            } finally {
                rig.finish();
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
                if (rig.pod.getY() > limit + EPSILON
                        || rig.pod.getY() < limit - 0.2
                        || rig.pod.getDeltaMovement().y > 0) {
                    throw failure(
                            helper,
                            "the pod should stop against the ceiling at y %s, y %s, velocity %s",
                            limit,
                            rig.pod.getY(),
                            rig.pod.getDeltaMovement());
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
            throw failure(helper, "a negative cargo mass must throw, not add lift");
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
                    throw failure(helper, "the pod should have landed by now, at y %s", pod.getY());
                }
                if (pod.hull() != hullBefore) {
                    throw failure(helper, "a drop of about 2 blocks should not damage the hull, hull %s", pod.hull());
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
                    throw failure(
                            helper,
                            "a stranded pod should ignore input, moved from %s to %s",
                            start,
                            rig.pod.position());
                }
                helper.succeed();
            } finally {
                rig.finish();
            }
        });
    }
}
