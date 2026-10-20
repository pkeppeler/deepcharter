package io.github.pkeppeler.deepcharter.test;

import com.google.errorprone.annotations.FormatMethod;
import com.google.errorprone.annotations.FormatString;
import io.github.pkeppeler.deepcharter.attachment.Versioned;
import io.github.pkeppeler.deepcharter.charter.CharterId;
import io.github.pkeppeler.deepcharter.charter.Charters;
import io.github.pkeppeler.deepcharter.layer.BreachEvents;
import io.github.pkeppeler.deepcharter.layer.LayerChain;
import io.github.pkeppeler.deepcharter.pod.Chassis;
import io.github.pkeppeler.deepcharter.pod.PodComponents;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodEvents;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.pod.PodTowing;
import io.github.pkeppeler.deepcharter.pod.PodTowing.Refusal;
import io.github.pkeppeler.deepcharter.pod.TowTuning;
import io.github.pkeppeler.deepcharter.test.support.ClientLight;
import io.github.pkeppeler.deepcharter.test.support.FarChunks;
import io.github.pkeppeler.deepcharter.test.support.LogCapture;
import io.github.pkeppeler.deepcharter.test.support.MockPlayer;
import io.github.pkeppeler.deepcharter.test.support.MockPlayers;
import io.github.pkeppeler.deepcharter.test.support.RoomCarver;
import io.github.pkeppeler.deepcharter.test.support.TestEntities;
import io.github.pkeppeler.deepcharter.test.support.TestTicks;
import io.github.pkeppeler.deepcharter.test.support.UseEntity;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Server GameTests for #76, the tow cable: a cable reaches the longest line, the towed pod trails its tower and passes through blocks,
 * its mass cuts the tower's lift, the link is saved, anyone may tow, and the towed pod crosses a breach with its tower.
 */
public class TowingTest {
    private static final String ATTACHMENTS_KEY = "fabric:attachments";
    private static final int FLOOR_Y = 1;
    private static final int FLOOR_RADIUS = 3;
    private static final double EPSILON = 1e-6;
    /** Ticks a pod needs, once its chunk ticks, to fall through the shaft and cross. */
    private static final int CROSSING_TICKS = 200;
    /** Entities BreachEvents.CROSSED has fired for. */
    private static final Set<UUID> CROSSED = ConcurrentHashMap.newKeySet();

    private static final AtomicInteger CHARTERS = new AtomicInteger();
    /** A block of rock fills x 1..5, y 2..5, z 1..4 (see fillRock). The pod sits in it and its tower beyond its south face, in the open. */
    private static final Vec3 ROCK_POD = new Vec3(3.5, 3, 3.5);

    private static final Vec3 ROCK_TOWER = new Vec3(3.5, 3, 6.0);
    /** Where the tow scenes are built in layer 1, each in its own range of x so none builds on another, and the top of the stone bed they stand on. */
    private static final int TOW_Z = 15008;

    private static final int TOW_FLOOR = 60;
    private static final int GROUND_X = 15000;
    private static final int SKY_X = 15100;
    private static final int SHAFT_X = 15300;
    private static final int RELOAD_X = 15500;
    private static final int CHUNK_X = 15600;
    /** Blocks that the floor of a shaft's gallery is above the floor of its shaft. */
    private static final int GALLERY_FLOOR = 10;

    private static final Input JUMP = new Input(false, false, false, false, true, false, false);

    static {
        BreachEvents.CROSSED.register((entity, from, to, fromLayer, toLayer) -> CROSSED.add(entity.getUUID()));
    }

    @GameTest
    public void aCableReachesAsFarAsTheLongestLineAndNoFurther(GameTestHelper helper) {
        double reach = TowTuning.DEFAULT.reachFor(PodRegistry.chassis().stream()
                .max(Comparator.comparingDouble(Chassis::width))
                .orElseThrow());
        PodEntity tower = helper.spawn(PodRegistry.POD, new Vec3(1.5, 2, 1.5));
        PodEntity atReach = helper.spawn(PodRegistry.POD, new Vec3(1.5 + reach, 2, 1.5));
        PodEntity pastReach = helper.spawn(PodRegistry.POD, new Vec3(1.5, 2, 1.5 + reach + 0.5));
        try {
            if (!Optional.of(Refusal.TOO_FAR).equals(PodTowing.refusal(tower, pastReach))) {
                throw failure(
                        helper,
                        "a pod half a block past the reach of %s should be out of reach, got %s",
                        reach,
                        PodTowing.refusal(tower, pastReach));
            }
            try {
                PodTowing.attach(tower, pastReach);
                throw failure(helper, "attaching out of reach must throw");
            } catch (IllegalStateException expected) {
                // Expected.
            }
            if (PodTowing.isTowed(pastReach)) {
                throw failure(helper, "a refused cable must leave no link");
            }
            if (PodTowing.refusal(tower, atReach).isPresent()) {
                throw failure(
                        helper, "a pod exactly the reach away is in reach, got %s", PodTowing.refusal(tower, atReach));
            }
            PodTowing.attach(tower, atReach);
            if (!Optional.of(tower.getUUID()).equals(PodTowing.towerId(atReach)) || PodTowing.isTowed(tower)) {
                throw failure(helper, "the pod in reach should be towed by the tower, and not the reverse");
            }
            helper.succeed();
        } finally {
            tower.discard();
            atReach.discard();
            pastReach.discard();
        }
    }

    @GameTest
    public void aPodCannotBeTowedTwiceOrTowItselfOrFormAChain(GameTestHelper helper) {
        PodEntity a = helper.spawn(PodRegistry.POD, new Vec3(1.5, 2, 1.5));
        PodEntity b = helper.spawn(PodRegistry.POD, new Vec3(1.5, 2, 4.5));
        PodEntity c = helper.spawn(PodRegistry.POD, new Vec3(4.5, 2, 1.5));
        try {
            expectRefusal(helper, a, a, Refusal.SAME_POD);
            if (PodTowing.detach(b)) {
                throw failure(helper, "detaching a pod that is not towed should report that nothing was detached");
            }
            PodTowing.attach(a, b);
            expectRefusal(helper, a, c, Refusal.ALREADY_TOWING);
            expectRefusal(helper, c, b, Refusal.ALREADY_TOWED);
            expectRefusal(helper, b, c, Refusal.TOWER_IS_TOWED);
            expectRefusal(helper, c, a, Refusal.TOWED_TOWS);
            if (!PodTowing.detach(b) || PodTowing.isTowed(b)) {
                throw failure(helper, "detaching a towed pod should free it");
            }
            PodTowing.attach(c, b);
            if (!Optional.of(c.getUUID()).equals(PodTowing.towerId(b))) {
                throw failure(helper, "a freed pod can be towed by another tower, got %s", PodTowing.towerId(b));
            }
            helper.succeed();
        } finally {
            a.discard();
            b.discard();
            c.discard();
        }
    }

    /**
     * On the ground the line is slack until the tower walks off, then the towed pod is pulled along the floor with the line taut:
     * the whole {@link TowTuning#lineLength} of free line between the hulls, 4 to 8 blocks, not the old 0.6.
     */
    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + 200)
    public void aTowedPodTrailsOnTheGroundAtTheLineLengthWithoutSinkingOrTakingDamage(GameTestHelper helper) {
        ServerLevel level = layer(helper, 1);
        int x = GROUND_X;
        RoomCarver.carve(level, x, x + 40, TOW_FLOOR - 12, TOW_FLOOR - 1, TOW_Z, TOW_Z + 10, Blocks.STONE);
        RoomCarver.carve(level, x, x + 40, TOW_FLOOR, TOW_FLOOR + 8, TOW_Z, TOW_Z + 10, Blocks.AIR);
        Vec3 start = new Vec3(x + 12.5, TOW_FLOOR, TOW_Z + 5.5);
        Vec3[] towedStart = {null};
        float[] hullStart = {0f};
        towScene(
                helper,
                level,
                columns(x, x + 40, TOW_Z),
                start,
                start.add(-2, 0, 0),
                step -> start.add(0.2 * Math.min(step, 50), 0, 0),
                (tower, towed, age) -> {
                    double trail = TowTuning.DEFAULT.trailDistance(tower, towed);
                    if (age == 1) {
                        towedStart[0] = towed.position();
                        hullStart[0] = towed.hull();
                        double freeLine = trail
                                - (tower.chassis().width() + towed.chassis().width()) / 2.0;
                        if (freeLine < 4.0 || freeLine > 8.0) {
                            throw failure(
                                    helper,
                                    "the free line between the hulls should be 4 to 8 blocks, it is %s",
                                    freeLine);
                        }
                    }
                    if (age == 10 && towed.position().distanceTo(towedStart[0]) > 0.3) {
                        throw failure(
                                helper,
                                "the line is slack while the tower is 4 blocks from the pod, which should stay put, it went from %s to %s",
                                towedStart[0],
                                towed.position());
                    }
                    if (age < 120) {
                        return false;
                    }
                    double gap = tower.position().distanceTo(towed.position());
                    if (Math.abs(gap - trail) > 0.1) {
                        throw failure(
                                helper,
                                "the towed pod should trail on a taut line %s from its tower, it is %s away",
                                trail,
                                gap);
                    }
                    if (Math.abs(towed.getY() - TOW_FLOOR) > 0.05) {
                        throw failure(
                                helper,
                                "the towed pod should rest on the floor at y %s, it is at %s",
                                TOW_FLOOR,
                                towed.getY());
                    }
                    if (towed.hull() != hullStart[0]) {
                        throw failure(
                                helper,
                                "a towed pod takes no damage, hull went from %s to %s",
                                hullStart[0],
                                towed.hull());
                    }
                    return true;
                });
    }

    /** In the air the towed pod hangs from the line like a sling load: below its tower and a little behind it while the tower flies on. */
    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + 200)
    public void aTowedPodHangsBelowAndBehindItsTowerInTheAir(GameTestHelper helper) {
        ServerLevel level = layer(helper, 1);
        Vec3 start = skyRoom(level, SKY_X);
        // The tower flies east at 0.2 a tick for 100 ticks, from a pod hanging straight below it. The pod swings about
        // the place it settles
        // to, so how far behind it hangs is the mean over the last 40 ticks of the flight.
        double[] behindSum = {0};
        towScene(
                helper,
                level,
                columns(SKY_X, SKY_X + 40, TOW_Z),
                start,
                start.add(0, -7, 0),
                step -> start.add(0.2 * Math.min(step, 100), 0, 0),
                (tower, towed, age) -> {
                    double trail = TowTuning.DEFAULT.trailDistance(tower, towed);
                    double gap = tower.position().distanceTo(towed.position());
                    // The tower has moved 0.2 since the cable held the pod.
                    if (gap > trail + 0.25) {
                        throw failure(helper, "the line is %s long, the towed pod is %s away", trail, gap);
                    }
                    if (age <= 60) {
                        return false;
                    }
                    behindSum[0] += tower.getX() - towed.getX();
                    double below = tower.getY() - towed.getY();
                    if (below < 0.8 * trail) {
                        throw failure(
                                helper,
                                "the towed pod should hang below its tower on the line, it is %s below of a %s line",
                                below,
                                trail);
                    }
                    if (age < 100) {
                        return false;
                    }
                    double behind = behindSum[0] / 40;
                    if (behind < 0.3) {
                        throw failure(
                                helper,
                                "the towed pod should hang a little behind a tower flying east, it is %s behind on average",
                                behind);
                    }
                    return true;
                });
    }

    /** A towed pod with no gravity stays where it hangs on a slack line; one with gravity sinks until the line is taut. */
    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + 100)
    public void aTowedPodWithNoGravityDoesNotSinkOnASlackLine(GameTestHelper helper) {
        slackLine(helper, SKY_X + 50, true);
    }

    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + 100)
    public void aTowedPodWithGravitySinksOnASlackLine(GameTestHelper helper) {
        slackLine(helper, SKY_X + 100, false);
    }

    private static void slackLine(GameTestHelper helper, int x, boolean noGravity) {
        ServerLevel level = layer(helper, 1);
        Vec3 start = skyRoom(level, x);
        Vec3 towedStart = start.add(0, -1, 0);
        int settle = 60;
        towScene(helper, level, columns(x, x + 40, TOW_Z), start, towedStart, step -> start, (tower, towed, age) -> {
            if (age == 1) {
                towed.setNoGravity(noGravity);
            }
            if (age < settle) {
                return false;
            }
            double sank = towedStart.y - towed.getY();
            if (noGravity ? Math.abs(sank) > 0.15 : sank < 1.5) {
                throw failure(
                        helper,
                        "a towed pod with%s gravity on a slack line should %s, it sank %s",
                        noGravity ? "out" : "",
                        noGravity ? "hold its height" : "sink toward the taut line",
                        sank);
            }
            return true;
        });
    }

    /** A jerk of the tower swings the pod, and the swing only dies away: no window of 30 ticks swings wider than the one before. */
    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + 300)
    public void aSwingingTowedPodSettlesAndNeverSwingsWider(GameTestHelper helper) {
        ServerLevel level = layer(helper, 1);
        Vec3 start = skyRoom(level, SKY_X + 64);
        int jerkTicks = 24;
        int window = 30;
        double[] widest = new double[6];
        towScene(
                helper,
                level,
                columns(SKY_X + 64, SKY_X + 104, TOW_Z),
                start,
                start.add(0, -7, 0),
                step -> start.add(0.25 * Math.min(step, jerkTicks), 0, 0),
                (tower, towed, age) -> {
                    int since = age - jerkTicks;
                    if (since > 0 && since / window < widest.length) {
                        widest[since / window] =
                                Math.max(widest[since / window], Math.abs(towed.getX() - tower.getX()));
                    }
                    if (since < window * widest.length) {
                        return false;
                    }
                    if (widest[0] < 0.5) {
                        throw failure(helper, "a jerk of the tower should swing the pod, it swung %s", widest[0]);
                    }
                    for (int i = 1; i < widest.length; i++) {
                        if (widest[i] > widest[i - 1] + 0.05) {
                            throw failure(
                                    helper,
                                    "the swing grew from %s to %s in window %s of %s",
                                    widest[i - 1],
                                    widest[i],
                                    i,
                                    Arrays.toString(widest));
                        }
                    }
                    if (widest[widest.length - 1] > 0.5 * widest[0]) {
                        throw failure(helper, "the swing should have died away, windows %s", Arrays.toString(widest));
                    }
                    return true;
                });
    }

    /** Up a 2-wide shaft and out of its top, the towed pod hangs in the shaft below the tower and its hull is never in a wall. */
    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + 300)
    public void aTowUpAShaftAndOutOfItsTopNeverPutsThePodInTheWalls(GameTestHelper helper) {
        ServerLevel level = layer(helper, 1);
        Vec3 base = shaft(level, SHAFT_X);
        towScene(
                helper,
                level,
                columns(SHAFT_X, SHAFT_X + 4, TOW_Z + 103),
                base.add(0, 4, 0),
                base,
                step -> base.add(0, 4 + 0.15 * Math.min(step, 140), 0),
                (tower, towed, age) -> {
                    requireNoWalls(helper, towed);
                    if (age < 200) {
                        return false;
                    }
                    double trail = TowTuning.DEFAULT.trailDistance(tower, towed);
                    double gap = tower.position().distanceTo(towed.position());
                    if (tower.getY() < base.y + 20 || gap > trail + 0.1 || tower.getY() - towed.getY() < 0.8 * trail) {
                        throw failure(
                                helper,
                                "the tower out of the shaft at %s should hold the pod on a taut line of %s below it, the pod is at %s",
                                tower.position(),
                                trail,
                                towed.position());
                    }
                    return true;
                });
    }

    /** Up the shaft and along a gallery, the line follows the bend: the pod is carried round the corner, never through the rock of it. */
    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + 300)
    public void aTowRoundABendInAShaftFollowsTheTowersPathWithoutSnagging(GameTestHelper helper) {
        ServerLevel level = layer(helper, 1);
        Vec3 base = shaft(level, SHAFT_X + 128);
        // 6 up the shaft at 0.15 a tick (40 ticks) to the gallery's floor, then 15 east along it (100 ticks).
        towScene(
                helper,
                level,
                columns(SHAFT_X + 128, SHAFT_X + 148, TOW_Z + 103),
                base.add(0, 4, 0),
                base,
                step -> step <= 40
                        ? base.add(0, 4 + 0.15 * step, 0)
                        : base.add(0.15 * Math.min(step - 40, 100), GALLERY_FLOOR, 0),
                (tower, towed, age) -> {
                    requireNoWalls(helper, towed);
                    if (age < 240) {
                        return false;
                    }
                    double trail = TowTuning.DEFAULT.trailDistance(tower, towed);
                    double gap = tower.position().distanceTo(towed.position());
                    if (gap > trail + 0.1) {
                        throw failure(
                                helper,
                                "the towed pod should be on a line no longer than %s, it is %s away",
                                trail,
                                gap);
                    }
                    if (towed.getX() < base.x + 7) {
                        throw failure(
                                helper,
                                "the towed pod should have been carried round the bend into the gallery, it is at %s",
                                towed.position());
                    }
                    return true;
                });
    }

    /**
     * After a reload the pod has no path behind its tower, and a Prospector (2.9 wide) towed by a Mole (1.9 wide) is not in open space where the Mole
     * is, beside a pillar the Mole just clears. The pod must still follow, and never hang further than the line while the tower leaves.
     */
    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + 200)
    public void aPodWithNoPathBehindItsTowerStillFollowsAfterAReload(GameTestHelper helper) {
        ServerLevel level = layer(helper, 1);
        int x = RELOAD_X;
        RoomCarver.carve(level, x, x + 40, TOW_FLOOR - 12, TOW_FLOOR - 1, TOW_Z, TOW_Z + 10, Blocks.STONE);
        RoomCarver.carve(level, x, x + 40, TOW_FLOOR, TOW_FLOOR + 8, TOW_Z, TOW_Z + 10, Blocks.AIR);
        // A Mole at z + 5.0 clears this pillar by 0.05, a Prospector does not.
        RoomCarver.carve(level, x + 10, x + 10, TOW_FLOOR, TOW_FLOOR + 6, TOW_Z + 6, TOW_Z + 6, Blocks.STONE);
        Vec3 start = new Vec3(x + 10.5, TOW_FLOOR, TOW_Z + 5.0);
        double[] widest = {0};
        towScene(
                helper,
                level,
                columns(x, x + 40, TOW_Z),
                PodRegistry.typeOf(Chassis.PROSPECTOR),
                start,
                start.add(-7.4, 0, 0),
                step -> start.add(0.2 * Math.min(step, 100), 0, 0),
                (tower, towed, age) -> {
                    if (age == 1) {
                        // The same as a reload for the line: the transient state of the cable is gone and the link is
                        // not.
                        PodTowing.detach(towed);
                        PodTowing.attach(tower, towed);
                    }
                    double trail = TowTuning.DEFAULT.trailDistance(tower, towed);
                    double gap = tower.position().distanceTo(towed.position());
                    widest[0] = Math.max(widest[0], gap);
                    if (gap > trail + 0.3) {
                        throw failure(
                                helper,
                                "the tower left a pod with no path behind it: the line is %s long and the pod is %s away on tick %s",
                                trail,
                                gap,
                                age);
                    }
                    requireNoWalls(helper, towed);
                    if (age < 100) {
                        return false;
                    }
                    if (Math.abs(gap - trail) > 0.3) {
                        throw failure(helper, "the pod should trail on a taut line of %s, it is %s away", trail, gap);
                    }
                    return true;
                });
    }

    /** A pod that hangs below a tower falling 20 blocks fast, and swings when it stops, takes no hard landing: the line holds it. */
    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + 300)
    public void aTowedPodTakesNoDamageWhenItsTowerDropsFast(GameTestHelper helper) {
        ServerLevel level = layer(helper, 1);
        Vec3 high = skyRoom(level, SKY_X + 128).add(0, 5, 0);
        float[] hullStart = {0f};
        towScene(
                helper,
                level,
                columns(SKY_X + 128, SKY_X + 168, TOW_Z),
                high,
                high.add(0, -7, 0),
                step -> high.add(0, -0.9 * Math.min(step, 24), 0),
                (tower, towed, age) -> {
                    if (age == 1) {
                        hullStart[0] = towed.hull();
                    }
                    if (towed.hull() != hullStart[0]) {
                        throw failure(
                                helper,
                                "the towed pod took a hard landing on tick %s: hull went from %s to %s",
                                age,
                                hullStart[0],
                                towed.hull());
                    }
                    if (age < 120) {
                        return false;
                    }
                    if (high.y - tower.getY() < 20) {
                        throw failure(
                                helper,
                                "the tower should have dropped 20 blocks or more, it dropped %s",
                                high.y - tower.getY());
                    }
                    return true;
                });
    }

    /** The pod's chunk is kept ticking by its tower: at a chunk's edge the long line leaves the pod across the border. */
    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + 200)
    public void aTowedPodInANeighbouringChunkKeepsTicking(GameTestHelper helper) {
        ServerLevel level = layer(helper, 1);
        int x = CHUNK_X;
        BlockPos towerColumn = new BlockPos(x + 15, TOW_FLOOR, TOW_Z);
        BlockPos podColumn = new BlockPos(x + 16 + 6, TOW_FLOOR, TOW_Z);
        PodEntity[] pods = {null, null};
        int[] ready = {0};
        FarChunks.awaitEntityTicking(helper, level, List.of(towerColumn, podColumn), index -> {
            if (++ready[0] == 2) {
                RoomCarver.carve(level, x + 4, x + 28, TOW_FLOOR - 12, TOW_FLOOR - 1, TOW_Z, TOW_Z + 8, Blocks.STONE);
                RoomCarver.carve(level, x + 4, x + 28, TOW_FLOOR, TOW_FLOOR + 6, TOW_Z, TOW_Z + 8, Blocks.AIR);
                pods[0] = spawn(level, x + 15.5, TOW_FLOOR, TOW_Z + 5.0);
                pods[1] = spawn(level, x + 16 + 6.5, TOW_FLOOR, TOW_Z + 5.0);
                PodTowing.attach(pods[0], pods[1]);
                // Only the tower's chunk is forced from here on: the chunk of the pod is loaded and does not tick
                // unless the tower asks.
                FarChunks.release(helper, level, podColumn.getX() >> 4, podColumn.getZ() >> 4);
            }
        });
        int[] since = {0};
        TestTicks.each(helper, () -> {
            if (pods[1] == null) {
                return;
            }
            pods[0].setPos(x + 15.5, TOW_FLOOR, TOW_Z + 5.0);
            pods[0].setDeltaMovement(Vec3.ZERO);
            if (++since[0] == 60) {
                int ticked = pods[1].tickCount;
                pods[0].discard();
                pods[1].discard();
                if (ticked < 40) {
                    throw failure(
                            helper,
                            "the towed pod, a chunk from its tower, should tick on, it ticked %s times in 60 ticks",
                            ticked);
                }
                helper.succeed();
            }
        });
    }

    /** The reach covers the longest taut line any pair of chassis can have, and a pod at that distance is in reach. */
    @GameTest
    public void theTowReachCoversTheLongestLine(GameTestHelper helper) {
        List<Chassis> all = PodRegistry.chassis();
        Chassis widest =
                all.stream().max(Comparator.comparingDouble(Chassis::width)).orElseThrow();
        double reach = TowTuning.DEFAULT.reachFor(widest);
        for (Chassis tower : all) {
            for (Chassis towed : all) {
                if (TowTuning.DEFAULT.trailDistance(tower, towed) > reach) {
                    throw failure(
                            helper,
                            "the reach %s should cover the line of %s towing %s, %s",
                            reach,
                            tower.id(),
                            towed.id(),
                            TowTuning.DEFAULT.trailDistance(tower, towed));
                }
            }
        }
        double line = TowTuning.DEFAULT.trailDistance(widest, widest);
        PodEntity tower = helper.spawn(PodRegistry.POD, new Vec3(1.5, 2, 1.5));
        PodEntity towed = helper.spawn(PodRegistry.POD, new Vec3(1.5, 2, 1.5 + line));
        try {
            Optional<Refusal> refusal = PodTowing.refusal(tower, towed);
            if (refusal.isPresent()) {
                throw failure(
                        helper,
                        "a pod the longest line away, %s, should be in reach, but a cable is refused: %s",
                        line,
                        refusal.get());
            }
            helper.succeed();
        } finally {
            tower.discard();
            towed.discard();
        }
    }

    @GameTest
    public void aTowedPodPassesThroughBlocksUntilItIsFreed(GameTestHelper helper) {
        PodEntity tower = helper.spawn(PodRegistry.POD, new Vec3(3.5, 3, 3.5));
        PodEntity towed = helper.spawn(PodRegistry.POD, new Vec3(3.5, 3, 5.0));
        PodTowing.attach(tower, towed);
        helper.runAfterDelay(3, () -> {
            if (!towed.noPhysics
                    || !PodEvents.ignoresBlockCollision(towed)
                    || tower.noPhysics
                    || PodEvents.ignoresBlockCollision(tower)) {
                tower.discard();
                towed.discard();
                throw failure(
                        helper,
                        "only the towed pod should ignore blocks, towed %s, tower %s",
                        towed.noPhysics,
                        tower.noPhysics);
            }
            PodTowing.detach(towed);
            helper.runAfterDelay(3, () -> {
                try {
                    if (towed.noPhysics || PodEvents.ignoresBlockCollision(towed)) {
                        throw failure(helper, "a freed pod collides with blocks again");
                    }
                    helper.succeed();
                } finally {
                    tower.discard();
                    towed.discard();
                }
            });
        });
    }

    @GameTest
    public void aPodWhoseTowerIsGoneIsFreeButKeepsItsLink(GameTestHelper helper) {
        PodEntity tower = helper.spawn(PodRegistry.POD, new Vec3(3.5, 3, 3.5));
        PodEntity towed = helper.spawn(PodRegistry.POD, new Vec3(3.5, 3, 5.0));
        UUID towerId = tower.getUUID();
        PodTowing.attach(tower, towed);
        tower.discard();
        helper.runAfterDelay(3, () -> {
            try {
                if (towed.noPhysics) {
                    throw failure(helper, "a pod with no tower in the world has nothing to pass blocks for");
                }
                if (!Optional.of(towerId).equals(PodTowing.towerId(towed))) {
                    throw failure(
                            helper,
                            "the link should persist while the tower is away, got %s",
                            PodTowing.towerId(towed));
                }
                helper.succeed();
            } finally {
                towed.discard();
            }
        });
    }

    @GameTest(maxTicks = 100)
    public void aTowedPodsMassCutsTheTowersLift(GameTestHelper helper) {
        fillFloor(helper, Blocks.STONE);
        PodEntity tower = helper.spawn(PodRegistry.POD, new Vec3(FLOOR_RADIUS + 0.5, FLOOR_Y + 1, FLOOR_RADIUS + 0.5));
        PodEntity towed = helper.spawn(PodRegistry.POD, new Vec3(FLOOR_RADIUS + 0.5, FLOOR_Y + 1, FLOOR_RADIUS + 2.4));
        MockPlayer pilot = MockPlayers.join(helper, "tow-pilot");
        pilot.teleportTo(helper.getLevel(), tower.position(), 0f, 0f);
        if (!pilot.player().startRiding(tower)) {
            throw failure(helper, "the pilot could not mount the tower");
        }
        if (PodEvents.extraMass(tower) != 0f) {
            throw failure(helper, "a pod that tows nothing adds no mass, got %s", PodEvents.extraMass(tower));
        }
        PodTowing.attach(tower, towed);
        float empty = PodEvents.extraMass(tower);
        towed.setCargoMass(80f);
        float loaded = PodEvents.extraMass(tower);
        if (empty <= 0f || loaded - empty != 80f) {
            throw failure(
                    helper,
                    "the towed pod should weigh something on its own and add its cargo mass, got %s empty and %s loaded",
                    empty,
                    loaded);
        }
        double startY = tower.getY();
        pilot.setInput(JUMP);
        helper.runAfterDelay(10, () -> {
            if (tower.getY() - startY > EPSILON || tower.flying()) {
                cleanUp(helper, tower, towed, pilot);
                throw failure(
                        helper,
                        "a tower dragging more than its engine power should not lift, rose %s",
                        tower.getY() - startY);
            }
            PodTowing.detach(towed);
            helper.runAfterDelay(15, () -> {
                try {
                    if (tower.getY() - startY < 1.0) {
                        throw failure(
                                helper,
                                "once the pod is let go the tower should climb again, rose %s",
                                tower.getY() - startY);
                    }
                    helper.succeed();
                } finally {
                    cleanUp(helper, tower, towed, pilot);
                }
            });
        });
    }

    @GameTest
    public void theLinkSurvivesSaveAndLoad(GameTestHelper helper) {
        PodEntity tower = helper.spawn(PodRegistry.POD, new Vec3(1.5, 2, 1.5));
        PodEntity towed = helper.spawn(PodRegistry.POD, new Vec3(1.5, 2, 4.5));
        PodTowing.attach(tower, towed);
        Entity loaded = reload(helper, towed, null);
        try {
            if (!(loaded instanceof PodEntity pod)
                    || !Optional.of(tower.getUUID()).equals(PodTowing.towerId(pod))) {
                throw failure(helper, "the cable should survive save and load, got %s", loaded);
            }
            helper.succeed();
        } finally {
            loaded.discard();
            tower.discard();
        }
    }

    /**
     * Saved towing data of a version this build cannot read: the pod loads, ticks and syncs as a pod that is not towed, nothing
     * on a tick path throws, the data is logged once and written back unchanged, and only an explicit change throws.
     */
    @GameTest
    public void anUnreadableLinkIsKeptAndNeverBreaksATick(GameTestHelper helper) {
        String key = PodTowing.STATE.identifier().toString();
        CompoundTag future = new CompoundTag();
        future.putInt("version", 99);
        future.putString("cable", "from the future");
        CompoundTag attachments = new CompoundTag();
        attachments.put(key, future);

        PodEntity tower = helper.spawn(PodRegistry.POD, new Vec3(1.5, 2, 1.5));
        PodEntity original = helper.spawn(PodRegistry.POD, new Vec3(1.5, 2, 4.5));
        Entity loaded = reload(helper, original, attachments);
        helper.getLevel().addFreshEntity(loaded);
        PodEntity towed = (PodEntity) loaded;
        LogCapture logged = LogCapture.start(towed.getUUID().toString());
        helper.runAfterDelay(10, () -> {
            try {
                if (!(towed.getAttached(PodTowing.STATE) instanceof Versioned.Unreadable<PodTowing.State>)) {
                    throw failure(
                            helper, "version 99 should load as unreadable, got %s", towed.getAttached(PodTowing.STATE));
                }
                if (PodTowing.isTowed(towed)
                        || PodEvents.ignoresBlockCollision(towed)
                        || PodEvents.extraMass(tower) != 0f) {
                    throw failure(helper, "an unreadable link must read as no link");
                }
                if (!Optional.of(Refusal.UNREADABLE).equals(PodTowing.refusal(tower, towed))) {
                    throw failure(
                            helper,
                            "a cable must not be fitted over unreadable data, got %s",
                            PodTowing.refusal(tower, towed));
                }
                try {
                    PodTowing.detach(towed);
                    throw failure(helper, "detaching an unreadable link must throw rather than overwrite it");
                } catch (IllegalStateException expected) {
                    if (!String.valueOf(expected.getMessage()).contains(key)) {
                        throw failure(helper, "the failure should name the attachment, got: %s", expected.getMessage());
                    }
                }
                if (logged.errors().size() != 1) {
                    throw failure(
                            helper,
                            "the unreadable pod should be logged once, not once for each tick: %s",
                            logged.errors());
                }
                TagValueOutput output = TagValueOutput.createWithContext(
                        ProblemReporter.DISCARDING, helper.getLevel().registryAccess());
                towed.saveWithoutId(output);
                if (!Objects.equals(
                        future,
                        output.buildResult()
                                .getCompound(ATTACHMENTS_KEY)
                                .orElseThrow()
                                .get(key))) {
                    throw failure(helper, "the unreadable data should be written back unchanged");
                }
                helper.succeed();
            } finally {
                towed.discard();
                tower.discard();
            }
        });
    }

    @GameTest
    public void anOutsiderCannotTowAPodOfAnotherCharter(GameTestHelper helper) {
        PodEntity tower = helper.spawn(PodRegistry.POD, new Vec3(1.5, 2, 1.5));
        PodEntity owned = helper.spawn(PodRegistry.POD, new Vec3(1.5, 2, 4.5));
        MockPlayer outsider = MockPlayers.join(helper, "tow-outsider-" + CHARTERS.incrementAndGet());
        PodComponents.register(owned, someCharter(helper));
        outsider.teleportTo(helper.getLevel(), tower.position(), 0f, 0f);
        if (!outsider.player().startRiding(tower)) {
            throw failure(helper, "the outsider could not mount the unowned tower");
        }
        outsider.player().setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(PodRegistry.TOW_CABLE));
        if (PodComponents.mayAccess(owned, Optional.empty())) {
            throw failure(helper, "the test needs a pod the outsider may not access");
        }
        if (use(outsider.player(), owned).consumesAction()) {
            throw failure(helper, "a refused cable must not count as used");
        }
        helper.runAfterDelay(2, () -> {
            try {
                if (PodTowing.isTowed(owned)) {
                    throw failure(
                            helper,
                            "a player of no charter must not tow another charter's pod, got %s",
                            PodTowing.towerId(owned));
                }
                if (!List.of(Refusal.NOT_ALLOWED_TO_TOW.message()).equals(outsider.actionBarMessages())) {
                    throw failure(helper, "the outsider should be told why, got %s", outsider.actionBarMessages());
                }
                helper.succeed();
            } finally {
                outsider.leave();
                tower.discard();
                owned.discard();
            }
        });
    }

    @GameTest
    public void aPodOfAGoneCharterCanBeTowedByAnyone(GameTestHelper helper) {
        PodEntity tower = helper.spawn(PodRegistry.POD, new Vec3(1.5, 2, 1.5));
        PodEntity orphan = helper.spawn(PodRegistry.POD, new Vec3(1.5, 2, 4.5));
        MockPlayer outsider = MockPlayers.join(helper, "tow-orphan-" + CHARTERS.incrementAndGet());
        try {
            PodComponents.register(orphan, CharterId.random());
            outsider.teleportTo(helper.getLevel(), tower.position(), 0f, 0f);
            if (!outsider.player().startRiding(tower)) {
                throw failure(helper, "the outsider could not mount the tower");
            }
            outsider.player().setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(PodRegistry.TOW_CABLE));
            use(outsider.player(), orphan);
            if (!Optional.of(tower.getUUID()).equals(PodTowing.towerId(orphan))) {
                throw failure(
                        helper,
                        "a pod whose owner charter is gone is anyone's to tow, got %s",
                        PodTowing.towerId(orphan));
            }
            helper.succeed();
        } finally {
            outsider.leave();
            tower.discard();
            orphan.discard();
        }
    }

    @GameTest
    public void onlyTheOwnerOrTheTowersCrewMayFreeATowedPod(GameTestHelper helper) {
        PodEntity tower = helper.spawn(PodRegistry.POD, new Vec3(1.5, 2, 1.5));
        PodEntity towed = helper.spawn(PodRegistry.POD, new Vec3(1.5, 2, 4.5));
        MockPlayer owner = MockPlayers.join(helper, "tow-owner-" + CHARTERS.incrementAndGet());
        MockPlayer crew = MockPlayers.join(helper, "tow-crew-" + CHARTERS.incrementAndGet());
        MockPlayer stranger = MockPlayers.join(helper, "tow-stranger-" + CHARTERS.incrementAndGet());
        try {
            PodComponents.register(towed, charterOf(helper, owner));
            PodComponents.register(tower, charterOf(helper, crew));
            for (MockPlayer player : List.of(owner, crew, stranger)) {
                player.player().setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(PodRegistry.TOW_CABLE));
            }
            PodTowing.attach(tower, towed);
            use(stranger.player(), towed);
            if (!PodTowing.isTowed(towed)) {
                throw failure(helper, "a stranger to both pods must not free the towed pod");
            }
            use(owner.player(), towed);
            if (PodTowing.isTowed(towed)) {
                throw failure(helper, "the owner should free a pod someone else towed");
            }
            PodTowing.attach(tower, towed);
            use(crew.player(), towed);
            if (PodTowing.isTowed(towed)) {
                throw failure(helper, "the tower's crew should free the pod it tows");
            }
            helper.runAfterDelay(2, () -> {
                try {
                    if (!List.of(Refusal.NOT_ALLOWED_TO_FREE.message()).equals(stranger.actionBarMessages())) {
                        throw failure(helper, "the stranger should be told why, got %s", stranger.actionBarMessages());
                    }
                    helper.succeed();
                } finally {
                    owner.leave();
                    crew.leave();
                    stranger.leave();
                    tower.discard();
                    towed.discard();
                }
            });
        } catch (RuntimeException failed) {
            owner.leave();
            crew.leave();
            stranger.leave();
            tower.discard();
            towed.discard();
            throw failed;
        }
    }

    @GameTest
    public void aPodFreedInRockIsMovedIntoOpenSpaceTowardItsTower(GameTestHelper helper) {
        fillRock(helper, Blocks.STONE);
        PodEntity tower = helper.spawn(PodRegistry.POD, ROCK_TOWER);
        PodEntity towed = helper.spawn(PodRegistry.POD, ROCK_POD);
        Vec3 towerAt = tower.position();
        Vec3 inRock = towed.position();
        MockPlayer rider = MockPlayers.join(helper, "tow-rock-rider-" + CHARTERS.incrementAndGet());
        rider.teleportTo(helper.getLevel(), towed.position(), 0f, 0f);
        if (!rider.player().startRiding(towed)) {
            throw failure(helper, "the rider could not mount the towed pod");
        }
        PodTowing.attach(tower, towed);
        TestTicks.each(helper, () -> {
            tower.setPos(towerAt);
            tower.setDeltaMovement(Vec3.ZERO);
        });
        helper.runAfterDelay(5, () -> {
            towed.setPos(inRock);
            if (helper.getLevel().noCollision(towed, towed.getBoundingBox())) {
                cleanUpRock(helper, tower, towed, rider);
                throw failure(helper, "the test needs the towed pod inside rock");
            }
            PodTowing.detach(towed);
            helper.runAfterDelay(3, () -> {
                try {
                    if (!helper.getLevel().noCollision(towed, towed.getBoundingBox())) {
                        throw failure(
                                helper, "a pod freed in rock should end in open space, it is at %s", towed.position());
                    }
                    if (towed.getZ() <= inRock.z) {
                        throw failure(
                                helper,
                                "the pod should leave the rock toward its tower, which is at higher z, it is at %s",
                                towed.position());
                    }
                    if (rider.player().isInWall()) {
                        throw failure(helper, "the rider should not be suffocating in the rock");
                    }
                    helper.succeed();
                } finally {
                    cleanUpRock(helper, tower, towed, rider);
                }
            });
        });
    }

    @GameTest
    public void aPodLeftInRockByALostTowerIsMovedIntoOpenSpace(GameTestHelper helper) {
        fillRock(helper, Blocks.STONE);
        PodEntity tower = helper.spawn(PodRegistry.POD, ROCK_TOWER);
        PodEntity towed = helper.spawn(PodRegistry.POD, ROCK_POD);
        Vec3 inRock = towed.position();
        MockPlayer rider = MockPlayers.join(helper, "tow-lost-rider-" + CHARTERS.incrementAndGet());
        rider.teleportTo(helper.getLevel(), towed.position(), 0f, 0f);
        if (!rider.player().startRiding(towed)) {
            throw failure(helper, "the rider could not mount the towed pod");
        }
        PodTowing.attach(tower, towed);
        helper.runAfterDelay(3, () -> {
            towed.setPos(inRock);
            tower.discard();
            helper.runAfterDelay(3, () -> {
                try {
                    if (!helper.getLevel().noCollision(towed, towed.getBoundingBox())) {
                        throw failure(
                                helper,
                                "a pod whose tower is lost should end in open space, it is at %s",
                                towed.position());
                    }
                    if (rider.player().isInWall()) {
                        throw failure(helper, "the rider should not be suffocating in the rock");
                    }
                    if (!PodTowing.isTowed(towed)) {
                        throw failure(helper, "the pod keeps its link while the tower is lost");
                    }
                    helper.succeed();
                } finally {
                    cleanUpRock(helper, tower, towed, rider);
                }
            });
        });
    }

    @GameTest
    public void theCableIsFittedFromTheSeatAndTakenOffAgain(GameTestHelper helper) {
        PodEntity tower = helper.spawn(PodRegistry.POD, new Vec3(1.5, 2, 1.5));
        PodEntity near = helper.spawn(PodRegistry.POD, new Vec3(1.5, 2, 4.5));
        PodEntity far = helper.spawn(PodRegistry.POD, new Vec3(1.5, 2, 12.5));
        MockPlayer mock = MockPlayers.join(helper, "tow-user-" + CHARTERS.incrementAndGet());
        ServerPlayer player = mock.player();
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(PodRegistry.TOW_CABLE));
        use(player, near);
        if (PodTowing.isTowed(near)) {
            throw failure(helper, "a cable cannot be fitted from outside a pod");
        }
        mock.teleportTo(helper.getLevel(), tower.position(), 0f, 0f);
        if (!player.startRiding(tower)) {
            throw failure(helper, "the player could not mount the tower");
        }
        use(player, far);
        if (PodTowing.isTowed(far)) {
            throw failure(helper, "a cable cannot be fitted to a pod out of reach");
        }
        if (!use(player, near).consumesAction() || !Optional.of(tower.getUUID()).equals(PodTowing.towerId(near))) {
            throw failure(helper, "using the cable on a pod in reach should fit it, got %s", PodTowing.towerId(near));
        }
        if (!use(player, near).consumesAction() || PodTowing.isTowed(near)) {
            throw failure(helper, "using the cable on a towed pod should take it off");
        }
        if (!player.getItemInHand(InteractionHand.MAIN_HAND).is(PodRegistry.TOW_CABLE)) {
            throw failure(helper, "the cable is a tool and is not used up");
        }
        helper.runAfterDelay(2, () -> {
            try {
                List<Component> expected = List.of(
                        Refusal.NOT_RIDING.message(),
                        Refusal.TOO_FAR.message(),
                        Component.translatable("message.deepcharter.towing.attached"),
                        Component.translatable("message.deepcharter.towing.detached"));
                if (!expected.equals(mock.actionBarMessages())) {
                    throw failure(
                            helper, "the player should be told what each use did, got %s", mock.actionBarMessages());
                }
                helper.succeed();
            } finally {
                mock.leave();
                tower.discard();
                near.discard();
                far.discard();
            }
        });
    }

    /** The M1 crossing with a second, unridden pod on a cable: the arriving tower is a new entity and the towed pod must arrive with it. */
    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + CROSSING_TICKS)
    public void theTowedPodCrossesABreachWithItsTower(GameTestHelper helper) {
        double x = 2100.5;
        double z = 2100.5;
        ServerLevel one = layer(helper, 1);
        openShaft(one, x, z);
        MockPlayer mock = MockPlayers.join(helper, "towing-crossing");
        mock.teleportTo(one, new Vec3(x, 8, z), 0, 0);
        PodEntity[] pods = {null, null};
        FarChunks.awaitEntityTicking(helper, one, BlockPos.containing(x, 8, z), () -> {
            pods[0] = spawn(one, x, 8, z);
            pods[1] = spawn(one, x, 10, z);
            PodTowing.attach(pods[0], pods[1]);
            if (!mock.player().startRiding(pods[0], true, false)) {
                throw failure(helper, "the mock could not board the tower");
            }
        });
        helper.succeedWhen(() -> {
            if (pods[0] == null) {
                throw failure(helper, "waiting for the chunk at %s to tick entities", BlockPos.containing(x, 8, z));
            }
            ServerPlayer player = mock.player();
            if (!player.level().dimension().equals(LayerChain.dimension(2))) {
                throw failure(
                        helper,
                        "the rider is still in %s (tower ticked %s times)",
                        player.level().dimension(),
                        pods[0].tickCount);
            }
            if (!(player.getVehicle() instanceof PodEntity arrived)
                    || !arrived.getUUID().equals(pods[0].getUUID())) {
                throw failure(
                        helper, "the rider should be on the same tower after crossing, is on %s", player.getVehicle());
            }
            ServerLevel two = player.level();
            if (!(two.getEntity(pods[1].getUUID()) instanceof PodEntity crossed)) {
                throw failure(helper, "the towed pod did not arrive in layer 2");
            }
            if (crossed == pods[1] || !pods[1].isRemoved()) {
                throw failure(helper, "expected a new towed pod after the crossing and the old one removed");
            }
            if (!Optional.of(arrived.getUUID()).equals(PodTowing.towerId(crossed))) {
                throw failure(helper, "the cable did not cross with the towed pod, got %s", PodTowing.towerId(crossed));
            }
            double gap = crossed.position().distanceTo(arrived.position());
            double trail = TowTuning.DEFAULT.trailDistance(arrived, crossed);
            if (Math.abs(gap - trail) > 0.5) {
                throw failure(
                        helper,
                        "the towed pod should arrive at the trail offset of %s from its tower, not inside it, it is %s away",
                        trail,
                        gap);
            }
        });
    }

    /** A rider in the towed pod crosses with it, and CROSSED fires for that rider as it does for the pilot: both crossed. */
    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + CROSSING_TICKS)
    public void aRiderInTheTowedPodCrossesWithIt(GameTestHelper helper) {
        double x = 2200.5;
        double z = 2200.5;
        ServerLevel one = layer(helper, 1);
        openShaft(one, x, z);
        MockPlayer pilot = MockPlayers.join(helper, "towing-pilot");
        MockPlayer rider = MockPlayers.join(helper, "towing-rider");
        pilot.teleportTo(one, new Vec3(x, 8, z), 0, 0);
        rider.teleportTo(one, new Vec3(x, 10, z), 0, 0);
        PodEntity[] pods = {null, null};
        FarChunks.awaitEntityTicking(helper, one, BlockPos.containing(x, 8, z), () -> {
            pods[0] = spawn(one, x, 8, z);
            pods[1] = spawn(one, x, 10, z);
            PodTowing.attach(pods[0], pods[1]);
            if (!pilot.player().startRiding(pods[0], true, false)
                    || !rider.player().startRiding(pods[1], true, false)) {
                throw failure(helper, "the mocks could not board");
            }
        });
        helper.succeedWhen(() -> {
            if (pods[0] == null) {
                throw failure(helper, "waiting for the chunk at %s to tick entities", BlockPos.containing(x, 8, z));
            }
            ServerPlayer seated = rider.player();
            if (!seated.level().dimension().equals(LayerChain.dimension(2))) {
                throw failure(
                        helper,
                        "the rider in the towed pod is still in %s",
                        seated.level().dimension());
            }
            if (!(seated.getVehicle() instanceof PodEntity vehicle)
                    || !vehicle.getUUID().equals(pods[1].getUUID())
                    || !PodTowing.isTowed(vehicle)) {
                throw failure(
                        helper,
                        "the rider should still ride the towed pod on its cable, is on %s",
                        seated.getVehicle());
            }
            if (!pilot.player().level().dimension().equals(LayerChain.dimension(2))) {
                throw failure(
                        helper,
                        "the pilot is still in %s",
                        pilot.player().level().dimension());
            }
            if (!CROSSED.contains(seated.getUUID())
                    || !CROSSED.contains(pilot.player().getUUID())) {
                throw failure(
                        helper,
                        "CROSSED should fire for the pilot and for the rider of the towed pod, fired for %s",
                        CROSSED);
            }
        });
    }

    /** A towed pod that vanilla will not teleport (an End to Overworld trip with a rider who has not seen the credits) stays behind on its cable. */
    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + 100)
    public void aTowedPodThatCannotCrossStaysBehindAsAnOrdinaryPod(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerLevel end = ClientLight.serverLevel(server, Level.END);
        ServerLevel overworld = server.overworld();
        double x = 2300.5;
        double z = 2300.5;
        MockPlayer rider = MockPlayers.join(helper, "towing-stay-" + CHARTERS.incrementAndGet());
        rider.teleportTo(end, new Vec3(x, 100, z), 0, 0);
        PodEntity tower = helper.spawn(PodRegistry.POD, new Vec3(1.5, 2, 1.5));
        PodEntity[] stay = {null};
        LogCapture[] logged = {null};
        FarChunks.awaitEntityTicking(helper, end, BlockPos.containing(x, 100, z), () -> {
            stay[0] = spawn(end, x, 100, z);
            logged[0] = LogCapture.start(stay[0].getUUID().toString());
            Versioned.modifyOrThrow(
                    stay[0], PodTowing.STATE, state -> new PodTowing.State(Optional.of(tower.getUUID())));
            if (!rider.player().startRiding(stay[0], true, false)) {
                throw failure(helper, "the rider could not board");
            }
            BreachEvents.CROSSED.invoker().onCrossed(tower, end, overworld, 1, 2);
        });
        helper.succeedWhen(() -> {
            if (stay[0] == null) {
                throw failure(
                        helper,
                        "waiting for the chunk at %s in the End to tick entities",
                        BlockPos.containing(x, 100, z));
            }
            try {
                if (stay[0].isRemoved()
                        || stay[0].level() != end
                        || rider.player().getVehicle() != stay[0]) {
                    throw failure(
                            helper,
                            "the pod that cannot cross should stay in the End with its rider, is in %s",
                            stay[0].level().dimension());
                }
                if (!Optional.of(tower.getUUID()).equals(PodTowing.towerId(stay[0]))) {
                    throw failure(helper, "it keeps its link, got %s", PodTowing.towerId(stay[0]));
                }
                if (PodEvents.ignoresBlockCollision(stay[0])
                        || logged[0].errors().size() != 1) {
                    throw failure(
                            helper,
                            "with its tower in another level it is an ordinary pod, and the failure is logged once: %s",
                            logged[0].errors());
                }
            } finally {
                rider.leave();
                stay[0].discard();
                tower.discard();
            }
        });
    }

    private static void expectRefusal(GameTestHelper helper, PodEntity tower, PodEntity towed, Refusal expected) {
        Optional<Refusal> actual = PodTowing.refusal(tower, towed);
        if (!Optional.of(expected).equals(actual)) {
            throw failure(
                    helper,
                    "fitting a cable from %s to %s should be refused as %s, got %s",
                    tower.getUUID(),
                    towed.getUUID(),
                    expected,
                    actual);
        }
    }

    private static InteractionResult use(ServerPlayer player, PodEntity pod) {
        return UseEntity.on(player, pod.level(), InteractionHand.MAIN_HAND, pod);
    }

    private static CharterId someCharter(GameTestHelper helper) {
        return foundCharter(helper, UUID.randomUUID());
    }

    /** Founds a charter with {@code player} as its founder. */
    private static CharterId charterOf(GameTestHelper helper, MockPlayer player) {
        return foundCharter(helper, player.player().getUUID());
    }

    private static CharterId foundCharter(GameTestHelper helper, UUID founder) {
        MinecraftServer server = helper.getLevel().getServer();
        if (Charters.found(server, founder, "Towing Test " + CHARTERS.incrementAndGet())
                .isPresent()) {
            throw failure(helper, "founding the charter should succeed");
        }
        return Charters.charterOfOrThrow(server, founder).orElseThrow().id();
    }

    private static void fillRock(GameTestHelper helper, Block block) {
        for (int x = 1; x <= 5; x++) {
            for (int y = 2; y <= 5; y++) {
                for (int z = 1; z <= 4; z++) {
                    helper.setBlock(new BlockPos(x, y, z), block);
                }
            }
        }
    }

    private static void cleanUpRock(GameTestHelper helper, PodEntity tower, PodEntity towed, MockPlayer rider) {
        rider.leave();
        tower.discard();
        towed.discard();
        // room-carver: clears a block of rock in the overworld test structure, not layer rock
        fillRock(helper, Blocks.AIR);
    }

    private static PodEntity spawn(ServerLevel level, double x, double y, double z) {
        return spawn(level, PodRegistry.POD, x, y, z);
    }

    private static PodEntity spawn(ServerLevel level, EntityType<PodEntity> type, double x, double y, double z) {
        PodEntity pod = TestEntities.create(type, level);
        pod.setPos(x, y, z);
        level.addFreshEntity(pod);
        return pod;
    }

    private static void cleanUp(GameTestHelper helper, PodEntity tower, PodEntity towed, MockPlayer pilot) {
        pilot.leave();
        tower.discard();
        towed.discard();
        // room-carver: clears the floor of the overworld test structure, not layer rock
        fillFloor(helper, Blocks.AIR);
    }

    /** What a tow scene checks after each tick; true ends the test as passed, and a failure is thrown. */
    private interface TowWatch {
        boolean tick(PodEntity tower, PodEntity towed, int age);
    }

    /**
     * A tower and a pod it tows, spawned once every chunk of {@code columns} ticks entities. From then on the tower is pinned to the
     * position {@code path} gives for each tick counted from 1, so only the cable can move the towed pod, and {@code watch} runs after
     * each tick with that count. Both pods are removed when it returns true or throws.
     */
    private static void towScene(
            GameTestHelper helper,
            ServerLevel level,
            List<BlockPos> columns,
            Vec3 towerAt,
            Vec3 towedAt,
            IntFunction<Vec3> path,
            TowWatch watch) {
        towScene(helper, level, columns, PodRegistry.POD, towerAt, towedAt, path, watch);
    }

    /** As {@link #towScene(GameTestHelper, ServerLevel, List, Vec3, Vec3, IntFunction, TowWatch)} with a towed pod of {@code towedType}. */
    private static void towScene(
            GameTestHelper helper,
            ServerLevel level,
            List<BlockPos> columns,
            EntityType<PodEntity> towedType,
            Vec3 towerAt,
            Vec3 towedAt,
            IntFunction<Vec3> path,
            TowWatch watch) {
        PodEntity[] pods = {null, null};
        int[] ready = {0};
        int[] age = {0};
        FarChunks.awaitEntityTicking(helper, level, columns, index -> {
            if (++ready[0] == columns.size()) {
                pods[0] = spawn(level, towerAt.x, towerAt.y, towerAt.z);
                pods[1] = spawn(level, towedType, towedAt.x, towedAt.y, towedAt.z);
                PodTowing.attach(pods[0], pods[1]);
            }
        });
        TestTicks.each(helper, () -> {
            if (pods[1] == null) {
                return;
            }
            try {
                pods[0].setPos(path.apply(++age[0]));
                pods[0].setDeltaMovement(Vec3.ZERO);
                if (watch.tick(pods[0], pods[1], age[0])) {
                    pods[0].discard();
                    pods[1].discard();
                    helper.succeed();
                }
            } catch (RuntimeException failed) {
                pods[0].discard();
                pods[1].discard();
                throw failed;
            }
        });
    }

    /** A column of blocks in layer 1 in every chunk from {@code fromX} to {@code toX}, on the row of chunks that holds {@code z}. */
    private static List<BlockPos> columns(int fromX, int toX, int z) {
        List<BlockPos> columns = new ArrayList<>();
        for (int x = fromX; x <= toX + 15; x += 16) {
            columns.add(new BlockPos(Math.min(x, toX), TOW_FLOOR, z));
        }
        return columns;
    }

    /** An open room in layer 1 over a bed of stone, 41 x 33 x 11 blocks, and the point in it where the tower starts, high above its floor. */
    private static Vec3 skyRoom(ServerLevel level, int x) {
        RoomCarver.carve(level, x, x + 40, TOW_FLOOR - 12, TOW_FLOOR - 1, TOW_Z, TOW_Z + 10, Blocks.STONE);
        RoomCarver.carve(level, x, x + 40, TOW_FLOOR, TOW_FLOOR + 32, TOW_Z, TOW_Z + 10, Blocks.AIR);
        return new Vec3(x + 6.5, TOW_FLOOR + 25, TOW_Z + 5.5);
    }

    /**
     * A rock of 23 x 29 x 7 blocks in layer 1 with a shaft 2 wide and 2 deep up its middle and out of its top (a Mole's hull of 1.9 just
     * fits), open air over the rock, and a gallery 3 high leading east from the shaft, its floor {@link #GALLERY_FLOOR} above the
     * shaft's. Returns the middle of the shaft's floor.
     */
    private static Vec3 shaft(ServerLevel level, int x) {
        int z = TOW_Z + 100;
        RoomCarver.carve(level, x, x + 22, TOW_FLOOR - 12, TOW_FLOOR + 16, z, z + 6, Blocks.STONE);
        RoomCarver.carve(level, x - 5, x + 27, TOW_FLOOR + 17, TOW_FLOOR + 40, z - 5, z + 11, Blocks.AIR);
        RoomCarver.carve(level, x + 2, x + 3, TOW_FLOOR, TOW_FLOOR + 16, z + 2, z + 3, Blocks.AIR);
        RoomCarver.carve(
                level,
                x + 2,
                x + 20,
                TOW_FLOOR + GALLERY_FLOOR,
                TOW_FLOOR + GALLERY_FLOOR + 2,
                z + 2,
                z + 3,
                Blocks.AIR);
        return new Vec3(x + 3.0, TOW_FLOOR, z + 3.0);
    }

    private static void requireNoWalls(GameTestHelper helper, PodEntity towed) {
        if (towed.level()
                .getBlockCollisions(towed, towed.getBoundingBox())
                .iterator()
                .hasNext()) {
            throw failure(helper, "the towed pod is in a wall at %s on tick %s", towed.position(), towed.tickCount);
        }
    }

    private static void fillFloor(GameTestHelper helper, Block block) {
        for (int x = -FLOOR_RADIUS; x <= FLOOR_RADIUS; x++) {
            for (int z = -FLOOR_RADIUS; z <= FLOOR_RADIUS; z++) {
                helper.setBlock(new BlockPos(x + FLOOR_RADIUS, FLOOR_Y, z + FLOOR_RADIUS), block);
            }
        }
    }

    /** Saves {@code pod} (replacing its attachments with {@code attachments} if given), discards it and loads a new pod from the data. */
    private static Entity reload(GameTestHelper helper, PodEntity pod, @Nullable CompoundTag attachments) {
        ServerLevel level = helper.getLevel();
        TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, level.registryAccess());
        pod.saveWithoutId(output);
        pod.discard();
        CompoundTag tag = output.buildResult();
        if (attachments != null) {
            tag.put(ATTACHMENTS_KEY, attachments);
        }
        return EntityType.create(
                        PodRegistry.POD,
                        TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), tag),
                        level,
                        EntitySpawnReason.LOAD)
                .orElseThrow(() -> failure(helper, "the saved pod did not load"));
    }

    /** Clear a 3x3 shaft through the floor of {@code level}, wide enough for the pod's 1.9-block hull. */
    private static void openShaft(ServerLevel level, double x, double z) {
        BlockPos column = BlockPos.containing(x, 0, z);
        RoomCarver.carve(
                level,
                column.offset(-1, 0, -1).atY(level.getMinY()),
                column.offset(1, 0, 1).atY(level.getMinY() + 10),
                Blocks.AIR.defaultBlockState(),
                Block.UPDATE_ALL);
    }

    private static ServerLevel layer(GameTestHelper helper, int layer) {
        ServerLevel level = ClientLight.serverLevel(helper.getLevel().getServer(), LayerChain.dimension(layer));
        if (level == null) {
            throw failure(helper, "dimension %s did not load", LayerChain.dimension(layer));
        }
        return level;
    }

    @FormatMethod
    private static RuntimeException failure(
            GameTestHelper helper, @FormatString String format, @Nullable Object... args) {
        return helper.assertionException(Component.literal(String.format(format, args)));
    }
}
