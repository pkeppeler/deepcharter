package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.Failures.failure;
import static io.github.pkeppeler.deepcharter.test.support.Inputs.FORWARD;

import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
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
 * on the bore's centre. Ten pods start at offsets along the drive that cover every final-approach gap from 0 to 0.2.
 */
public class PodDriveStepTest {
    private static final int MAX_TICKS = FarChunks.AWAIT_BUDGET_TICKS + 700;
    /**
     * Layer 1 columns x 4260-4269, z 2996-3148, all in chunk column 266: no other test stages here. PodDrillTest's nudge test
     * shared x 4024 and in reverse order ran in one batch (#1178).
     */
    private static final int X = 4264;

    private static final int Z = 3000;
    private static final int FLOOR = TestLayers.worldY(1, 60);
    private static final float EAST = -90f;
    private static final int SLABS = 3;
    /** One pod per start offset along the drive, in rows of its own. */
    private static final int PODS = 10;

    private static final int ROW = 16;
    private static final double OFFSET_STEP = 0.02;

    @GameTest(maxTicks = MAX_TICKS)
    public void aDrillingPodNeverMovesFurtherInATickThanItsSpeed(GameTestHelper helper) {
        ServerLevel level = TestLayers.level(helper, 1);
        List<BlockPos> spots = new ArrayList<>();
        List<MockPlayer> pilots = new ArrayList<>();
        List<Arrival<PodEntity>> arrivals = new ArrayList<>();
        for (int i = 0; i < PODS; i++) {
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
            level.addFreshEntity(pod);
            if (!pilots.get(i).player().startRiding(pod)) {
                throw failure(helper, "the pilot could not mount pod %s", i);
            }
            pilots.get(i).setInput(FORWARD);
            arrivals.get(i).set(pod);
        });
        double speed = PodTuning.DEFAULT.movement().horizontalSpeed();
        Vec3[] last = new Vec3[PODS];
        boolean[] done = new boolean[PODS];
        double[] worst = new double[PODS];
        TestTicks.each(helper, () -> {
            int finished = 0;
            for (int i = 0; i < PODS; i++) {
                if (!arrivals.get(i).isPresent() || done[i]) {
                    finished += done[i] ? 1 : 0;
                    continue;
                }
                PodEntity pod = arrivals.get(i).get();
                Vec3 now = pod.position();
                if (last[i] != null) {
                    double step = Math.hypot(now.x - last[i].x, now.z - last[i].z);
                    worst[i] = Math.max(worst[i], step);
                    if (step > speed + 1e-6) {
                        throw failure(
                                helper,
                                "pod %s moved %s in one tick (%s -> %s), its speed is %s",
                                i,
                                step,
                                last[i],
                                now,
                                speed);
                    }
                }
                last[i] = now;
                if (now.x > X + 2 + SLABS) {
                    pilots.get(i).releaseInput();
                    pod.discard();
                    done[i] = true;
                }
            }
            if (finished == PODS) {
                helper.succeed();
            } else if (helper.getTick() >= MAX_TICKS - 2) {
                throw failure(helper, "pods not done:%s", describe(arrivals, done, last));
            }
        });
    }

    /** Which pods never arrived or never finished, and where each one stopped. */
    private static String describe(List<Arrival<PodEntity>> arrivals, boolean[] done, Vec3[] last) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < PODS; i++) {
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
        return new Vec3(X + 0.3 + i * OFFSET_STEP, FLOOR, Z + i * ROW + 0.3);
    }
}
