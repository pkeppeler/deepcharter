package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.Failures.expect;

import io.github.pkeppeler.deepcharter.creature.CreatureTuning;
import io.github.pkeppeler.deepcharter.pod.PodStats;
import io.github.pkeppeler.deepcharter.repair.Consumable;
import io.github.pkeppeler.deepcharter.repair.RepairRegistry;
import io.github.pkeppeler.deepcharter.repair.RepairTuning;
import io.github.pkeppeler.deepcharter.repair.ThrownKit;
import io.github.pkeppeler.deepcharter.repair.ThrownKitEntity;
import io.github.pkeppeler.deepcharter.repair.ThrownKitTuning;
import io.github.pkeppeler.deepcharter.test.support.EarlyRunModel;
import io.github.pkeppeler.deepcharter.test.support.FarChunks;
import io.github.pkeppeler.deepcharter.test.support.ThrowRoom;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Pacing of the thrown kit (#985), held to bands. <b>Measured</b> from real runs: how far a throw goes, how long a flare burns, how long a decoy lasts,
 * how long a charge's fuse is, the prices (read from {@link Consumable}) and what a stock run nets (read from {@link EarlyRunModel}, the economy
 * test's own model). <b>Modelled</b>, stated in the code: the length of each fight ({@link Encounter}), how fast a person walks, and the Listeners'
 * patience. The numbers are logged as {@code [thrown-kit]} lines, which the PR quotes.
 */
public class ThrownKitPacingTest {
    private static final Logger LOGGER = LoggerFactory.getLogger(ThrownKitPacingTest.class);
    private static final ThrownKitTuning KIT = ThrownKitTuning.DEFAULT;
    private static final CreatureTuning CREATURE = CreatureTuning.DEFAULT;
    private static final int TICKS_PER_SECOND = 20;

    /** Modelled: blocks a tick that a person walks, 4.3 blocks a second: the cheapest way off a charge's blast. */
    private static final double WALK_BLOCKS_PER_TICK = 4.3 / TICKS_PER_SECOND;

    private static final int MEASURE_X = 8224;
    private static final int FLAT_X = 7776;
    private static final int LOB_X = 7840;
    private static final int LAND_BUDGET_TICKS = 120;
    private static final int MAX_TICKS = FarChunks.AWAIT_BUDGET_TICKS + 2 * LAND_BUDGET_TICKS;
    /** The flare burns for the longest of the three, so the run that measures all of them is as long as the shipped burn (1,800 ticks) and a margin. */
    private static final int MEASURE_RUN_TICKS = 2_400;

    private static final int MEASURE_MAX_TICKS = FarChunks.AWAIT_BUDGET_TICKS + MEASURE_RUN_TICKS;
    /** A throw level with the eyes, which lands about 12 blocks off, and one 15 degrees up, which goes further. */
    private static final float FLAT = 0f;

    private static final float LOB = -15f;
    /** The measured band of a flat throw, from the thrower's feet in blocks, and of a lob. */
    private static final double FLAT_MIN = 8;

    private static final double FLAT_MAX = 16;
    private static final double LOB_MIN = 14;
    private static final double LOB_MAX = 30;

    /** What a run of the kit measured: ticks a flare burned, a decoy sounded and a charge's fuse ran, each from landing to its end. */
    private record Measured(int flareTicks, int decoyTicks, int fuseTicks) {}

    /**
     * One fight, as the creatures document it (CREATURES sections 4 and 6), a model: how long it lasts and what it needs from the kit. How many throws and
     * dollars it takes comes from the measured durations and the prices.
     *
     * @param name       what the fight is
     * @param ticks      how long the fight lasts
     * @param needsLight whether the dark has to be held off for all of it, one flare for each burn
     * @param decoyTicks how long a noise has to be kept up, one decoy for each time one sounds, or 0
     * @param charges    charges thrown in it
     */
    private record Encounter(String name, int ticks, boolean needsLight, int decoyTicks, int charges) {
        int flares(Measured measured) {
            return needsLight ? Math.ceilDiv(ticks, measured.flareTicks()) : 0;
        }

        int decoys(Measured measured) {
            return decoyTicks > 0 ? Math.ceilDiv(decoyTicks, measured.decoyTicks()) : 0;
        }

        int throwsNeeded(Measured measured) {
            return flares(measured) + decoys(measured) + charges;
        }

        long cost(Measured measured) {
            return flares(measured) * Consumable.THROWN_FLARE.price()
                    + decoys(measured) * Consumable.THROWN_DECOY.price()
                    + charges * Consumable.THROWN_CHARGE.price();
        }
    }

    /** What a stock Mole's run nets in layer 1: the run the economy test prices the layer 1 tools against. */
    private static double layerOneRunNet() {
        return EarlyRunModel.run(EarlyRunModel.Zone.load("topsoil_claims"), PodStats.base(), 0)
                .net();
    }

    /** What a Mole with tier 2 parts nets in layer 2: the run the economy test prices the layer 2 tools against. */
    private static double layerTwoRunNet() {
        return EarlyRunModel.layerTwoRun(EarlyRunModel.Zone.load("upper_levels"), EarlyRunModel.mole(2, 2, 2), 0f)
                .net();
    }

    /** Throws one flare at {@code pitch} from the room's throwing spot and reports how far it landed, in blocks along the room. */
    private static void measure(GameTestHelper helper, int x, float pitch, double min, double max, String what) {
        double[] range = {0};
        ThrowRoom.start(
                helper,
                x,
                room -> {
                    room.thrower().teleportTo(room.level(), room.throwingSpot(), 0f, pitch);
                    var refused = ThrownKit.throwFrom(room.thrower().player(), Consumable.THROWN_FLARE, KIT);
                    expect(helper, refused.isEmpty(), "the throw was refused: %s", refused);
                },
                room -> {
                    BlockPos flare = room.flare();
                    if (flare == null) {
                        expect(helper, room.ticks() <= LAND_BUDGET_TICKS, "the %s never landed", what);
                        return;
                    }
                    Vec3 from = room.throwingSpot();
                    range[0] = Math.hypot(flare.getX() + 0.5 - from.x, flare.getZ() + 0.5 - from.z);
                    LOGGER.info(
                            "[thrown-kit] a {} throw lands {} blocks away, {} ticks after the throw",
                            what,
                            range[0],
                            room.ticks());
                    expect(
                            helper,
                            range[0] >= min && range[0] <= max,
                            "a %s throw landed %.1f blocks away, the band is %.0f to %.0f",
                            what,
                            range[0],
                            min,
                            max);
                    helper.succeed();
                });
    }

    @GameTest(maxTicks = MAX_TICKS)
    public void aFlatThrowLandsWithinTheTunedRange(GameTestHelper helper) {
        measure(helper, FLAT_X, FLAT, FLAT_MIN, FLAT_MAX, "flat");
    }

    @GameTest(maxTicks = MAX_TICKS)
    public void aLobGoesFartherThanAFlatThrow(GameTestHelper helper) {
        measure(helper, LOB_X, LOB, LOB_MIN, LOB_MAX, "lob");
    }

    /** The entity of the kit thrown from the room, found by the item it carries. */
    private static ThrownKitEntity kit(ThrowRoom room, Consumable item) {
        return room.kits().stream()
                .filter(kit -> kit.getItem().is(RepairRegistry.item(item)))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("no " + item + " in the air"));
    }

    /** Throws {@code item} from the room's throwing spot at {@code pitch}. */
    private static void throwAt(GameTestHelper helper, ThrowRoom room, Consumable item, float pitch) {
        room.thrower().teleportTo(room.level(), room.throwingSpot(), 0f, pitch);
        var refused = ThrownKit.throwFrom(room.thrower().player(), item, KIT);
        expect(helper, refused.isEmpty(), "the throw of %s was refused: %s", item, refused);
    }

    /**
     * Throws one of each, runs until all three have ended, and holds what each measured to its band: a flare's burn against the Retained's cadence, a
     * decoy's lifetime against how long a fight needs a noise, a charge's fuse against the walk out of its reach. Then the fights of {@link Encounter},
     * costed from those measurements, against a run's takings.
     */
    @GameTest(maxTicks = MEASURE_MAX_TICKS)
    public void flareDecoyAndFuseAreMeasuredAndHeldToTheirBands(GameTestHelper helper) {
        ThrownKitEntity[] decoy = {null};
        ThrownKitEntity[] charge = {null};
        int[] flareLanded = {-1};
        int[] flareOut = {-1};
        int[] decoyLanded = {-1};
        int[] decoyOut = {-1};
        int[] chargeLanded = {-1};
        int[] chargeOut = {-1};
        ThrowRoom.start(
                helper,
                MEASURE_X,
                room -> {
                    expect(
                            helper,
                            KIT.flare().burnTicks() + 3 * LAND_BUDGET_TICKS <= MEASURE_RUN_TICKS,
                            "the run is %d ticks, too short for a %d tick flare",
                            MEASURE_RUN_TICKS,
                            KIT.flare().burnTicks());
                    throwAt(helper, room, Consumable.THROWN_FLARE, 0f);
                    throwAt(helper, room, Consumable.THROWN_DECOY, -6f);
                    throwAt(helper, room, Consumable.THROWN_CHARGE, -12f);
                    decoy[0] = kit(room, Consumable.THROWN_DECOY);
                    charge[0] = kit(room, Consumable.THROWN_CHARGE);
                },
                room -> {
                    int now = room.ticks();
                    BlockPos flare = room.flare();
                    if (flareLanded[0] < 0 && flare != null) {
                        flareLanded[0] = now;
                    } else if (flareLanded[0] >= 0 && flareOut[0] < 0 && room.flare() == null) {
                        flareOut[0] = now;
                    }
                    if (decoyLanded[0] < 0 && decoy[0].landed()) {
                        decoyLanded[0] = now;
                    } else if (decoyLanded[0] >= 0 && decoyOut[0] < 0 && decoy[0].isRemoved()) {
                        decoyOut[0] = now;
                    }
                    if (chargeLanded[0] < 0 && charge[0].landed()) {
                        chargeLanded[0] = now;
                    } else if (chargeLanded[0] >= 0 && chargeOut[0] < 0 && charge[0].isRemoved()) {
                        chargeOut[0] = now;
                    }
                    if (flareOut[0] < 0 || decoyOut[0] < 0 || chargeOut[0] < 0) {
                        return;
                    }
                    Measured measured = new Measured(
                            flareOut[0] - flareLanded[0], decoyOut[0] - decoyLanded[0], chargeOut[0] - chargeLanded[0]);
                    holdDurations(helper, measured);
                    holdFights(helper, measured);
                    helper.succeed();
                });
    }

    private static void holdDurations(GameTestHelper helper, Measured measured) {
        int cadence = CREATURE.respawnDelayTicks();
        LOGGER.info(
                "[thrown-kit] measured: a flare burned {} s, a band's figures come back {} s after a kill, so one flare spans {} such gaps",
                measured.flareTicks() / TICKS_PER_SECOND,
                cadence / TICKS_PER_SECOND,
                (double) measured.flareTicks() / cadence);
        expectBand(
                helper, (double) measured.flareTicks() / cadence, 3, 10, "gaps between figures that one flare covers");
        LOGGER.info(
                "[thrown-kit] measured: a decoy sounded {} s; the Listeners give up after about 10 s of silence (modelled)",
                measured.decoyTicks() / TICKS_PER_SECOND);
        expectBand(helper, (double) measured.decoyTicks() / TICKS_PER_SECOND, 20, 90, "seconds a decoy sounds");
        int clear = (int)
                Math.ceil((RepairTuning.DEFAULT.chargeReach() + KIT.charge().blastRadius() + 1) / WALK_BLOCKS_PER_TICK);
        LOGGER.info(
                "[thrown-kit] measured: a charge's fuse ran {} ticks; walking clear of its reach takes {} (modelled)",
                measured.fuseTicks(),
                clear);
        expect(
                helper,
                measured.fuseTicks() >= 2 * clear && measured.fuseTicks() <= 6 * TICKS_PER_SECOND,
                "a fuse of %d ticks should be at least twice the %d ticks to walk clear, and no more than six seconds",
                measured.fuseTicks(),
                clear);
    }

    private static void holdFights(GameTestHelper helper, Measured measured) {
        double layerTwo = layerTwoRunNet();
        Encounter hold =
                new Encounter("holding a dark stretch for five minutes", 5 * 60 * TICKS_PER_SECOND, true, 0, 0);
        Encounter hush = new Encounter(
                "the Hush, solo: a three minute tow under decoys with two charges",
                3 * 60 * TICKS_PER_SECOND,
                false,
                3 * 60 * TICKS_PER_SECOND,
                2);
        Encounter listener = new Encounter(
                "a Listener: set a decoy, plant, retreat", 30 * TICKS_PER_SECOND, false, 30 * TICKS_PER_SECOND, 1);
        for (Encounter encounter : new Encounter[] {hold, hush, listener}) {
            LOGGER.info(
                    "[thrown-kit] {}: {} throws, ${}, {}% of a layer 2 run of ${}",
                    encounter.name(),
                    encounter.throwsNeeded(measured),
                    encounter.cost(measured),
                    Math.round(100 * encounter.cost(measured) / layerTwo),
                    Math.round(layerTwo));
        }
        expectBand(helper, hold.throwsNeeded(measured), 2, 6, "throws to hold a dark stretch for five minutes");
        expectShare(helper, hold.cost(measured), layerTwo, 0.05, 0.25, "holding a dark stretch for five minutes");
        expectBand(helper, hush.throwsNeeded(measured), 4, 10, "throws in the solo Hush tow");
        expectShare(helper, hush.cost(measured), layerTwo, 0.30, 0.70, "the solo Hush tow");
        expectBand(helper, listener.throwsNeeded(measured), 2, 3, "throws against a Listener");
        expectShare(helper, listener.cost(measured), layerTwo, 0.10, 0.35, "a Listener");
    }

    @GameTest
    public void eachItemCostsAFractionOfALayerOneRun(GameTestHelper helper) {
        double layerOne = layerOneRunNet();
        LOGGER.info("[thrown-kit] a stock layer 1 run nets ${} (EarlyRunModel)", Math.round(layerOne));
        expectShareOfLayerOne(helper, Consumable.THROWN_FLARE, layerOne, 0.05, 0.15);
        expectShareOfLayerOne(helper, Consumable.THROWN_DECOY, layerOne, 0.15, 0.30);
        expectShareOfLayerOne(helper, Consumable.THROWN_CHARGE, layerOne, 0.35, 0.55);
        expect(
                helper,
                Consumable.THROWN_CHARGE.price() > Consumable.BLASTING_CHARGE.price(),
                "a charge thrown from a distance costs more than the one set under the pod ($%d)",
                Consumable.BLASTING_CHARGE.price());
        expect(
                helper,
                Consumable.THROWN_FLARE.price() < Consumable.THROWN_DECOY.price(),
                "a flare is the cheapest round");
        helper.succeed();
    }

    private static void expectBand(GameTestHelper helper, double value, double min, double max, String what) {
        expect(helper, value >= min && value <= max, "%s: %.1f, the band is %.1f to %.1f", what, value, min, max);
    }

    private static void expectShare(
            GameTestHelper helper, long cost, double layerTwoNet, double min, double max, String what) {
        double share = cost / layerTwoNet;
        expect(
                helper,
                share >= min && share <= max,
                "%s costs $%d, %.0f%% of a layer 2 run; the band is %.0f%% to %.0f%%",
                what,
                cost,
                100 * share,
                100 * min,
                100 * max);
    }

    private static void expectShareOfLayerOne(
            GameTestHelper helper, Consumable item, double layerOneNet, double min, double max) {
        double share = item.price() / layerOneNet;
        expect(
                helper,
                share >= min && share <= max,
                "%s costs $%d, %.0f%% of a layer 1 run; the band is %.0f%% to %.0f%%",
                item,
                item.price(),
                100 * share,
                100 * min,
                100 * max);
    }
}
