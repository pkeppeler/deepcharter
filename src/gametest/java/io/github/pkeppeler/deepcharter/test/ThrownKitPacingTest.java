package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.Failures.expect;

import io.github.pkeppeler.deepcharter.creature.CreatureTuning;
import io.github.pkeppeler.deepcharter.repair.Consumable;
import io.github.pkeppeler.deepcharter.repair.RepairTuning;
import io.github.pkeppeler.deepcharter.repair.ThrownKit;
import io.github.pkeppeler.deepcharter.repair.ThrownKitTuning;
import io.github.pkeppeler.deepcharter.test.support.FarChunks;
import io.github.pkeppeler.deepcharter.test.support.ThrowRoom;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Pacing of the thrown kit (#985), measured and held to bands: how far a throw goes, how many throws a fight costs, how long a flare and a decoy last
 * against the creatures' cadence, and what an encounter costs against a run's takings. The numbers are logged as {@code [thrown-kit]} lines, which
 * the PR quotes. A fight is a model, stated in {@link Encounter}: this test is where its numbers are tuned, not where a fight is played.
 */
public class ThrownKitPacingTest {
    private static final Logger LOGGER = LoggerFactory.getLogger(ThrownKitPacingTest.class);
    private static final ThrownKitTuning KIT = ThrownKitTuning.DEFAULT;
    private static final CreatureTuning CREATURE = CreatureTuning.DEFAULT;
    private static final int TICKS_PER_SECOND = 20;

    /** What a stock Mole's run nets in layer 1 and in layer 2 (see {@link Consumable}); {@code EconomyAffordabilityTest} measures them. */
    private static final double LAYER_ONE_RUN_NET = 115;

    private static final double LAYER_TWO_RUN_NET = 348;
    /** Blocks a second that a person walks: the cheapest way off a charge's blast. */
    private static final double WALK_BLOCKS_PER_TICK = 4.3 / TICKS_PER_SECOND;

    private static final int FLAT_X = 7776;
    private static final int LOB_X = 7840;
    private static final int LAND_BUDGET_TICKS = 120;
    private static final int MAX_TICKS = FarChunks.AWAIT_BUDGET_TICKS + 2 * LAND_BUDGET_TICKS;
    /** A throw level with the eyes, which lands about 12 blocks off, and one 15 degrees up, which goes further. */
    private static final float FLAT = 0f;

    private static final float LOB = -15f;
    /** The measured band of a flat throw, from the thrower's feet in blocks, and of a lob. */
    private static final double FLAT_MIN = 8;

    private static final double FLAT_MAX = 16;
    private static final double LOB_MIN = 14;
    private static final double LOB_MAX = 30;

    /**
     * One fight, as the creatures document it (CREATURES sections 4 and 6): how long it lasts and what it needs from the kit.
     *
     * @param name       what the fight is
     * @param ticks      how long the fight lasts
     * @param needsLight whether the dark has to be held off for all of it, one flare for each {@code burnTicks}
     * @param decoyTicks how long a noise has to be kept up, one decoy for each {@code noiseTicks}, or 0
     * @param charges    charges thrown in it
     */
    private record Encounter(String name, int ticks, boolean needsLight, int decoyTicks, int charges) {
        int flares() {
            return needsLight ? coverage(ticks, KIT.flare().burnTicks()) : 0;
        }

        int decoys() {
            return decoyTicks > 0 ? coverage(decoyTicks, KIT.decoy().noiseTicks()) : 0;
        }

        int throwsNeeded() {
            return flares() + decoys() + charges;
        }

        long cost() {
            return flares() * Consumable.THROWN_FLARE.price()
                    + decoys() * Consumable.THROWN_DECOY.price()
                    + charges * Consumable.THROWN_CHARGE.price();
        }
    }

    /** How many of something that lasts {@code per} ticks cover {@code ticks}. */
    private static int coverage(int ticks, int per) {
        return Math.ceilDiv(ticks, per);
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

    @GameTest
    public void aFightCostsAFewThrowsAndAFractionOfARun(GameTestHelper helper) {
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
                    "[thrown-kit] {}: {} throws, ${}, {}% of a layer 2 run",
                    encounter.name(),
                    encounter.throwsNeeded(),
                    encounter.cost(),
                    Math.round(100 * encounter.cost() / LAYER_TWO_RUN_NET));
        }
        expectBand(helper, hold.throwsNeeded(), 2, 6, "throws to hold a dark stretch for five minutes");
        expectShare(helper, hold.cost(), 0.05, 0.25, "holding a dark stretch for five minutes");
        expectBand(helper, hush.throwsNeeded(), 4, 10, "throws in the solo Hush tow");
        expectShare(helper, hush.cost(), 0.30, 0.70, "the solo Hush tow");
        expectBand(helper, listener.throwsNeeded(), 2, 3, "throws against a Listener");
        expectShare(helper, listener.cost(), 0.10, 0.35, "a Listener");
        helper.succeed();
    }

    @GameTest
    public void eachItemCostsAFractionOfALayerOneRun(GameTestHelper helper) {
        expectShareOfLayerOne(helper, Consumable.THROWN_FLARE, 0.05, 0.15);
        expectShareOfLayerOne(helper, Consumable.THROWN_DECOY, 0.15, 0.30);
        expectShareOfLayerOne(helper, Consumable.THROWN_CHARGE, 0.35, 0.55);
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

    @GameTest
    public void durationsAreTunedAgainstTheCreaturesCadence(GameTestHelper helper) {
        int flare = KIT.flare().burnTicks();
        int cadence = CREATURE.respawnDelayTicks();
        LOGGER.info(
                "[thrown-kit] a flare burns {} s, a band's figures come back {} s after a kill, so one flare spans {} such gaps",
                flare / TICKS_PER_SECOND,
                cadence / TICKS_PER_SECOND,
                flare / cadence);
        expectBand(helper, (double) flare / cadence, 3, 10, "gaps between figures that one flare covers");
        int decoy = KIT.decoy().noiseTicks();
        LOGGER.info(
                "[thrown-kit] a decoy sounds {} s; the Listeners give up after about 10 s of silence",
                decoy / TICKS_PER_SECOND);
        expectBand(helper, (double) decoy / TICKS_PER_SECOND, 20, 90, "seconds a decoy sounds");
        int clear = (int)
                Math.ceil((RepairTuning.DEFAULT.chargeReach() + KIT.charge().blastRadius() + 1) / WALK_BLOCKS_PER_TICK);
        LOGGER.info(
                "[thrown-kit] a charge's fuse is {} ticks; walking clear of its reach takes {}",
                KIT.charge().fuseTicks(),
                clear);
        expect(
                helper,
                KIT.charge().fuseTicks() >= 2 * clear && KIT.charge().fuseTicks() <= 6 * TICKS_PER_SECOND,
                "a fuse of %d ticks should be at least twice the %d ticks to walk clear, and no more than six seconds",
                KIT.charge().fuseTicks(),
                clear);
        helper.succeed();
    }

    private static void expectBand(GameTestHelper helper, double value, double min, double max, String what) {
        expect(helper, value >= min && value <= max, "%s: %.1f, the band is %.1f to %.1f", what, value, min, max);
    }

    private static void expectShare(GameTestHelper helper, long cost, double min, double max, String what) {
        double share = cost / LAYER_TWO_RUN_NET;
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

    private static void expectShareOfLayerOne(GameTestHelper helper, Consumable item, double min, double max) {
        double share = item.price() / LAYER_ONE_RUN_NET;
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
