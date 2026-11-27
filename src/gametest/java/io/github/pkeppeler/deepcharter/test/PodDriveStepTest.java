package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.Failures.failure;
import static io.github.pkeppeler.deepcharter.test.support.Inputs.FORWARD;
import static io.github.pkeppeler.deepcharter.test.support.Inputs.FORWARD_SPRINT;

import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.pod.PodSteeringTuning;
import io.github.pkeppeler.deepcharter.pod.PodTuning;
import io.github.pkeppeler.deepcharter.test.support.Arrival;
import io.github.pkeppeler.deepcharter.test.support.FarChunks;
import io.github.pkeppeler.deepcharter.test.support.MockPlayer;
import io.github.pkeppeler.deepcharter.test.support.MockPlayers;
import io.github.pkeppeler.deepcharter.test.support.RoomCarver;
import io.github.pkeppeler.deepcharter.test.support.TestEntities;
import io.github.pkeppeler.deepcharter.test.support.TestLayers;
import io.github.pkeppeler.deepcharter.test.support.TestTicks;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * Guard (#1041): a drilling pod never moves further in one server tick than its speed, at any gap between it and the wall it meets.
 * The drive's move and the drill's centring slide could add up on one axis; they do not, because a clipped move leaves the pod flush
 * on the bore's centre. Ten pods that cruise start at offsets along the drive that cover one tick of their travel,
 * so every final-approach gap from 0 to the speed; ten that sprint do the same at the sprint's speed, their bound
 * (#1276).
 */
public class PodDriveStepTest {
    private static final int MAX_TICKS = FarChunks.AWAIT_BUDGET_TICKS + 700;
    /** Layer 1 columns x 4260-4269, z 2996-3308, all in chunk column 266. */
    private static final int X = 4264;

    private static final int Z = 3000;
    private static final int FLOOR = TestLayers.worldY(1, 60);
    private static final float EAST = -90f;
    private static final int SLABS = 3;
    /** Pods of each drive, one per start offset along it, in rows of their own. */
    private static final int PODS = 10;

    private static final int ROW = 16;
    private static final double SPEED = PodTuning.DEFAULT.movement().horizontalSpeed();
    private static final double SPRINT_SPEED = SPEED * PodSteeringTuning.DEFAULT.sprintBoost();

    /** Rows 0 to {@link #PODS} - 1 cruise, the rest sprint. */
    private static boolean sprints(int i) {
        return i >= PODS;
    }

    /** The most a pod in row {@code i} may move in a tick: its drive's speed. */
    private static double bound(int i) {
        return sprints(i) ? SPRINT_SPEED : SPEED;
    }

    @GameTest(maxTicks = MAX_TICKS)
    public void aDrillingPodNeverMovesFurtherInATickThanItsSpeed(GameTestHelper helper) {
        int pods = 2 * PODS;
        ServerLevel level = TestLayers.level(helper, 1);
        List<BlockPos> spots = new ArrayList<>();
        List<MockPlayer> pilots = new ArrayList<>();
        List<Arrival<PodEntity>> arrivals = new ArrayList<>();
        for (int i = 0; i < pods; i++) {
            Vec3 at = start(i);
            MockPlayer pilot = MockPlayers.join(helper, "drive-step-" + i);
            pilot.teleportTo(level, at, EAST, 0f);
            pilots.add(pilot);
            arrivals.add(new Arrival<>("pod " + i, "the far chunk ticks"));
            spots.add(BlockPos.containing(at));
        }
        FarChunks.awaitEntityTicking(helper, level, spots, i -> {
            // Cut once the row's chunk ticks: an earlier cut can unload unsaved (#1083).
            room(level, i);
            PodEntity pod = TestEntities.create(PodRegistry.POD, level);
            pod.setPos(start(i));
            pod.setYRot(EAST);
            level.addFreshEntity(pod);
            if (!pilots.get(i).player().startRiding(pod)) {
                throw failure(helper, "the pilot could not mount pod %s", i);
            }
            pilots.get(i).setInput(sprints(i) ? FORWARD_SPRINT : FORWARD);
            arrivals.get(i).set(pod);
        });
        Vec3[] last = new Vec3[pods];
        boolean[] done = new boolean[pods];
        double[] worst = new double[pods];
        TestTicks.each(helper, () -> {
            int finished = 0;
            for (int i = 0; i < pods; i++) {
                if (!arrivals.get(i).isPresent() || done[i]) {
                    finished += done[i] ? 1 : 0;
                    continue;
                }
                PodEntity pod = arrivals.get(i).get();
                Vec3 now = pod.position();
                if (last[i] != null) {
                    double step = Math.hypot(now.x - last[i].x, now.z - last[i].z);
                    worst[i] = Math.max(worst[i], step);
                    if (step > bound(i) + 1e-6) {
                        throw failure(
                                helper,
                                "pod %s moved %s in one tick (%s -> %s), its speed is %s",
                                i,
                                step,
                                last[i],
                                now,
                                bound(i));
                    }
                }
                last[i] = now;
                // On the last slab's column: a pod the drill has centred there stands on it exactly (#1276's faster
                // drive arrives and is centred within one tick).
                if (now.x >= X + 2 + SLABS) {
                    pilots.get(i).releaseInput();
                    pod.discard();
                    done[i] = true;
                }
            }
            if (finished == pods) {
                helper.succeed();
            } else if (helper.getTick() >= MAX_TICKS - 2) {
                throw failure(helper, "pods not done:%s", describe(arrivals, done, last));
            }
        });
    }

    /** Which pods never arrived or never finished, and where each one stopped. */
    private static String describe(List<Arrival<PodEntity>> arrivals, boolean[] done, Vec3[] last) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < done.length; i++) {
            if (!done[i]) {
                text.append("\n  pod %s: %s, %s"
                        .formatted(
                                i,
                                arrivals.get(i).isPresent() ? "arrived" : "never arrived",
                                last[i] == null ? "no position yet" : "last at " + last[i]));
            }
        }
        return text.toString();
    }

    /** Row {@code i}: a room with a stone bed and {@link #SLABS} slabs of stone for the pod to drill. */
    private static void room(ServerLevel level, int i) {
        int z = Z + i * ROW;
        RoomCarver.carve(level, X - 4, X + 5, FLOOR - 8, FLOOR - 1, z - 4, z + 4, Blocks.STONE);
        RoomCarver.carve(level, X - 4, X + 5, FLOOR, FLOOR + 10, z - 4, z + 4, Blocks.AIR);
        for (int x = X + 3; x < X + 3 + SLABS; x++) {
            for (int y = FLOOR; y <= FLOOR + 10; y++) {
                for (int dz = -3; dz <= 3; dz++) {
                    level.setBlock(new BlockPos(x, y, z + dz), Blocks.STONE.defaultBlockState(), 2);
                }
            }
        }
    }

    private static Vec3 start(int i) {
        return new Vec3(X + 0.3 + (i % PODS) * bound(i) / PODS, FLOOR, Z + i * ROW + 0.3);
    }
}
