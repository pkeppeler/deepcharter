package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.Failures.expect;
import static io.github.pkeppeler.deepcharter.test.support.Failures.failure;
import static io.github.pkeppeler.deepcharter.test.support.Inputs.SPRINT;

import io.github.pkeppeler.deepcharter.layer.LayerBlocks;
import io.github.pkeppeler.deepcharter.pod.PodBrace;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.pod.PodStats;
import io.github.pkeppeler.deepcharter.pod.PodTuning;
import io.github.pkeppeler.deepcharter.pod.VoidBelow;
import io.github.pkeppeler.deepcharter.pod.VoidBelowTuning;
import io.github.pkeppeler.deepcharter.test.support.FarChunks;
import io.github.pkeppeler.deepcharter.test.support.MockPlayer;
import io.github.pkeppeler.deepcharter.test.support.MockPlayers;
import io.github.pkeppeler.deepcharter.test.support.RoomCarver;
import io.github.pkeppeler.deepcharter.test.support.TestEntities;
import io.github.pkeppeler.deepcharter.test.support.TestLayers;
import io.github.pkeppeler.deepcharter.test.support.TestTicks;
import java.util.Optional;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * Server GameTests for the void below (#1387): the price of a free fall into open space under the drill, on the crust
 * warning, and the drill's hollow pitch for the last rows before it breaks through into a drop that lands hard. Layer
 * 1's crust is its lowest 3 rows (local y 0 to 2), and the rows under it are layer 2's rock, where {@link
 * RoomCarver#carvePocketBelow} opens 8 rows of air over a stone bed: a 9-block fall from the last crust row.
 */
public class VoidBelowTest {
    private static final int Z = 9150;
    private static final float FULL_CRUST = 24f;
    private static final float ROW = 8f;
    /**
     * The fall from the last crust row into {@link RoomCarver#carvePocketBelow}'s pocket: its 8 rows of air and the
     * bored row.
     */
    private static final int POCKET_FALL = 9;
    /** The drill's hollow pitch must start at least a second before the pod breaks through. */
    private static final int MIN_LEAD_TICKS = 20;
    /** Rows of stone the pod bores in the drilling tests: the hollow rows and as many again before them, and more. */
    private static final int ROWS = 2 * VoidBelowTuning.DEFAULT.hollowRows() + 2;
    /** The drilling tests' local Y of the lowest bored row: mid-layer, clear of the crust and the layer's roof. */
    private static final int BORE_BOTTOM_LOCAL = 100;

    // ---- the price of the drop ----

    @GameTest
    public void aDropCostsWhatTheMovementSimulationLandsWith(GameTestHelper helper) {
        PodStats stock = PodStats.base();
        float nine = VoidBelow.dropCost(stock, 9);
        expect(
                helper,
                nine > 18f && nine < 20f,
                "a free fall of 9 blocks lands hard for about 19 hull, it costs %s",
                nine);
        expect(
                helper,
                VoidBelow.dropCost(stock, 2) == 0f,
                "a 2-block drop lands soft, it costs %s",
                VoidBelow.dropCost(stock, 2));
        expect(helper, VoidBelow.dropCost(stock, 1) == 0f, "the 1-block fall into a bored slab is free");
        float three = VoidBelow.dropCost(stock, 3);
        float four = VoidBelow.dropCost(stock, 4);
        expect(helper, three == 0f && four > 0f, "3 blocks land soft and 4 hard, they cost %s and %s", three, four);
        expect(helper, VoidBelow.dropCost(stock, 0) == 0f, "no fall is free");
        // Independent of the helper's loop: a fall long enough for terminal speed costs (gravity / (1 / drag - 1) -
        // 0.7) x 50.
        PodTuning.Movement movement = PodTuning.DEFAULT.movement();
        double terminal = movement.gravity() * movement.verticalDrag() / (1 - movement.verticalDrag());
        float deep = VoidBelow.dropCost(stock, 4000);
        float limit = (float) ((terminal - stock.hardLandingSpeed()) * stock.hullDamagePerSpeed());
        expect(
                helper,
                deep <= limit + 0.01f && deep > limit - 1f,
                "a very long fall nears the terminal cost %s, it costs %s",
                limit,
                deep);
        helper.succeed();
    }

    /** The helper's price is what a real pod loses when it falls the same 9 blocks onto stone. */
    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + 200)
    public void aRealFallOfNineBlocksLosesTheDropsPrice(GameTestHelper helper) {
        int x = 9100;
        ServerLevel one = TestLayers.level(helper, 1);
        int floorTop = TestLayers.worldY(1, BORE_BOTTOM_LOCAL);
        RoomCarver.carve(one, x - 2, x + 2, floorTop - 2, floorTop, Z - 2, Z + 2, Blocks.STONE);
        RoomCarver.carve(one, x - 2, x + 2, floorTop + 1, floorTop + POCKET_FALL + 3, Z - 2, Z + 2, Blocks.AIR);
        Vec3 start = new Vec3(x + 0.5, floorTop + 1 + POCKET_FALL, Z + 0.5);
        PodEntity[] pod = {null};
        FarChunks.awaitEntityTicking(helper, one, BlockPos.containing(start), () -> {
            PodEntity spawned = TestEntities.create(PodRegistry.POD, one);
            spawned.setPos(start);
            one.addFreshEntity(spawned);
            pod[0] = spawned;
        });
        helper.succeedWhen(() -> {
            if (pod[0] == null || !pod[0].onGround() || pod[0].tickCount < 5) {
                throw failure(helper, "the pod has not landed");
            }
            float lost = PodTuning.DEFAULT.shell().fullHull() - pod[0].hull();
            float priced = VoidBelow.dropCost(PodStats.of(pod[0]), POCKET_FALL);
            if (Math.abs(lost - priced) > 0.5f) {
                throw failure(helper, "a 9-block fall should lose the drop's price %s, it lost %s", priced, lost);
            }
            pod[0].discard();
        });
    }

    // ---- the crust warning prices the drop ----

    @GameTest
    public void aPodOverACrustWithADeepVoidIsWarnedOfTheDrop(GameTestHelper helper) {
        int x = 9200;
        ServerLevel one = TestLayers.level(helper, 1);
        crust(one, x);
        RoomCarver.carvePocketBelow(one, 1, x - 3, x + 3, Z - 3, Z + 3);
        float drop = VoidBelow.dropCost(PodStats.base(), POCKET_FALL);
        // 26 outlasts the crust's 24, so before #1387 it showed nothing; the drop of about 19 makes it a death.
        expectWarning(helper, pod(one, x, 5, 26f), 2, 3, FULL_CRUST, drop, "2 slabs above the crust, over the pocket");
        expectWarning(helper, pod(one, x, 1, 26f), 0, 1, ROW, drop, "on the last crust row, before it is bored");
        expectNone(helper, pod(one, x, 5, FULL_CRUST + drop + 1), "for a hull that outlasts the crust and the drop");
        helper.succeed();
    }

    @GameTest
    public void aPodOverACrustOnSolidRockHasNoDropToPay(GameTestHelper helper) {
        int x = 9300;
        ServerLevel one = TestLayers.level(helper, 1);
        crust(one, x);
        int floor = TestLayers.band(one, 1).minY();
        RoomCarver.carve(one, x - 3, x + 3, floor - 12, floor - 1, Z - 3, Z + 3, Blocks.STONE);
        expectNone(
                helper,
                pod(one, x, 5, 26f),
                "for 26 hull over solid rock: the crust takes 24 and the pod lands on rock");
        expectWarning(helper, pod(one, x, 5, 20f), 2, 3, FULL_CRUST, 0f, "for 20 hull, with no drop part");
        helper.succeed();
    }

    /** A 2-row gap under the crust is a 3-block fall, which lands soft: no price on the warning and no hollow pitch. */
    @GameTest
    public void aGapThatLandsSoftIsNeitherPricedNorHeard(GameTestHelper helper) {
        int x = 9600;
        ServerLevel one = TestLayers.level(helper, 1);
        crust(one, x);
        int floor = TestLayers.band(one, 1).minY();
        RoomCarver.carve(one, x - 3, x + 3, floor - 12, floor - 3, Z - 3, Z + 3, Blocks.STONE);
        RoomCarver.carve(one, x - 3, x + 3, floor - 2, floor - 1, Z - 3, Z + 3, Blocks.AIR);
        softFloor(helper, one, x, "a 2-row gap");
        helper.succeed();
    }

    /** Fluid under the crust is a floor: a pod falls 3 blocks to the water of a flooded pocket, not 9 to its bed. */
    @GameTest
    public void aFluidUnderTheCrustIsAFloor(GameTestHelper helper) {
        int x = 9700;
        ServerLevel one = TestLayers.level(helper, 1);
        crust(one, x);
        RoomCarver.carvePocketBelow(one, 1, x - 3, x + 3, Z - 3, Z + 3);
        int floor = TestLayers.band(one, 1).minY();
        RoomCarver.carve(one, x - 3, x + 3, floor - 8, floor - 3, Z - 3, Z + 3, Blocks.WATER);
        softFloor(helper, one, x, "water 2 rows under the crust");
        helper.succeed();
    }

    /** The pod on the last crust row at {@code x}: the fall after it lands soft, so no drop price, no hollow drill. */
    private static void softFloor(GameTestHelper helper, ServerLevel level, int x, String what) {
        expectWarning(helper, pod(level, x, 1, ROW), 0, 1, ROW, 0f, "over " + what + ", with no drop part");
        PodEntity last = pod(level, x, 1, 100f);
        expect(helper, !VoidBelow.hollow(last), "the drill over %s does not sound hollow", what);
    }

    // ---- the drill sounds hollow before it breaks through ----

    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + 1200)
    public void aPodDrillingTowardADeepCavityHearsItHollowBeforeItBreaksThrough(GameTestHelper helper) {
        int x = 9400;
        ServerLevel one = TestLayers.level(helper, 1);
        int boreFloor = TestLayers.worldY(1, BORE_BOTTOM_LOCAL);
        // Stone the pod bores, over 10 rows of air, over a stone bed: an 11-block fall from the last row.
        RoomCarver.carve(one, x - 3, x + 3, boreFloor - 12, boreFloor - 11, Z - 3, Z + 3, Blocks.STONE);
        RoomCarver.carve(one, x - 3, x + 3, boreFloor - 10, boreFloor - 1, Z - 3, Z + 3, Blocks.AIR);
        int top = bore(one, x, boreFloor);
        int[] firstHollow = {-1};
        drill(helper, one, x, top, "hollow-driller", (pod, tick) -> {
            boolean hollow = VoidBelow.hollow(pod);
            if (hollow && pod.getY() >= boreFloor + VoidBelowTuning.DEFAULT.hollowRows() + 1) {
                throw failure(
                        helper,
                        "the drill sounds hollow only for the last %s rows, it does at y %s over a breakthrough at %s",
                        VoidBelowTuning.DEFAULT.hollowRows(),
                        pod.getY(),
                        boreFloor);
            }
            if (hollow && firstHollow[0] < 0) {
                firstHollow[0] = tick;
            }
            if (pod.getY() < boreFloor - 0.5) {
                if (firstHollow[0] < 0 || tick - firstHollow[0] < MIN_LEAD_TICKS) {
                    throw failure(
                            helper,
                            "the hollow pitch should lead the breakthrough (tick %2$s) by %1$s ticks, it began at %3$s",
                            MIN_LEAD_TICKS,
                            tick,
                            firstHollow[0]);
                }
                return true;
            }
            return false;
        });
    }

    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + 1200)
    public void aPodDrillingOverSolidGroundNeverHearsItHollow(GameTestHelper helper) {
        int x = 9500;
        ServerLevel one = TestLayers.level(helper, 1);
        int boreFloor = TestLayers.worldY(1, BORE_BOTTOM_LOCAL);
        RoomCarver.carve(one, x - 3, x + 3, boreFloor - 12, boreFloor - 1, Z - 3, Z + 3, Blocks.STONE);
        int top = bore(one, x, boreFloor);
        drill(helper, one, x, top, "solid-driller", (pod, tick) -> {
            if (VoidBelow.hollow(pod)) {
                throw failure(helper, "a drill over solid stone never sounds hollow, it does at y %s", pod.getY());
            }
            return pod.getY() < boreFloor + 0.01;
        });
    }

    // ---- helpers ----

    /**
     * A Mole with its feet at {@code localY} in layer 1 at {@code x}, on the ground with {@code hull}; not in the
     * world.
     */
    private static PodEntity pod(ServerLevel level, int x, int localY, float hull) {
        PodEntity pod = TestEntities.create(PodRegistry.POD, level);
        pod.setPos(x, TestLayers.worldY(1, localY), Z);
        pod.setHull(hull);
        pod.setOnGround(true);
        return pod;
    }

    /** Three rows of breach crust at layer 1's floor, under stone. */
    private static void crust(ServerLevel level, int x) {
        int floor = TestLayers.band(level, 1).minY();
        RoomCarver.carve(level, x - 3, x + 3, floor, floor + 2, Z - 3, Z + 3, LayerBlocks.BREACH_CRUST);
        RoomCarver.carve(level, x - 3, x + 3, floor + 3, floor + 10, Z - 3, Z + 3, Blocks.STONE);
    }

    /**
     * {@link #ROWS} rows of stone down to {@code boreFloor}, under an open room; the Y of the pod's feet on top of
     * them.
     */
    private static int bore(ServerLevel level, int x, int boreFloor) {
        int top = boreFloor + ROWS;
        RoomCarver.carve(level, x - 3, x + 3, boreFloor, top - 1, Z - 3, Z + 3, Blocks.STONE);
        RoomCarver.carve(level, x - 3, x + 3, top, top + 5, Z - 3, Z + 3, Blocks.AIR);
        return top;
    }

    /** What the drilling tests check each tick; true once the test is done. */
    private interface Watch {
        boolean done(PodEntity pod, int tick);
    }

    /**
     * A piloted Mole on the stone at {@code x} that holds the drill; {@code watch} reads it each tick until it is done.
     */
    private static void drill(GameTestHelper helper, ServerLevel level, int x, int top, String name, Watch watch) {
        MockPlayer pilot = MockPlayers.join(helper, name);
        pilot.player().setGameMode(GameType.SURVIVAL);
        Vec3 start = new Vec3(x, top, Z);
        pilot.teleportTo(level, start, 0f, 0f);
        PodEntity[] pod = {null};
        int[] ticks = {0};
        FarChunks.awaitEntityTicking(helper, level, BlockPos.containing(start), () -> {
            PodEntity spawned = TestEntities.create(PodRegistry.POD, level);
            spawned.setPos(start);
            level.addFreshEntity(spawned);
            if (!pilot.player().startRiding(spawned)) {
                throw failure(helper, "the pilot could not mount the pod");
            }
            pilot.setInput(SPRINT);
            pod[0] = spawned;
        });
        TestTicks.each(helper, () -> {
            if (pod[0] == null) {
                return;
            }
            if (watch.done(pod[0], ticks[0]++)) {
                pilot.releaseInput();
                pod[0].discard();
                helper.succeed();
            }
        });
    }

    private static void expectNone(GameTestHelper helper, PodEntity pod, String when) {
        Optional<PodBrace.Warning> warning = PodBrace.status(pod, true).warning();
        if (warning.isPresent()) {
            throw failure(helper, "no crust warning %s, got %s", when, warning.get());
        }
    }

    private static void expectWarning(
            GameTestHelper helper, PodEntity pod, int slabsAway, int crustSlabs, float cost, float drop, String when) {
        PodBrace.Warning warning =
                PodBrace.status(pod, true).warning().orElseThrow(() -> failure(helper, "a crust warning %s", when));
        if (warning.slabsAway() != slabsAway
                || warning.crustSlabs() != crustSlabs
                || Math.abs(warning.cost() - cost) > 0.01f
                || Math.abs(warning.drop() - drop) > 0.01f) {
            throw failure(
                    helper,
                    "the warning %s should be %d away, %d crust slabs, cost %s, drop %s; it is %s",
                    when,
                    slabsAway,
                    crustSlabs,
                    cost,
                    drop,
                    warning);
        }
    }
}
