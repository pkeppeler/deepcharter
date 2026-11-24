package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.Failures.failure;

import io.github.pkeppeler.deepcharter.creature.Tallies;
import io.github.pkeppeler.deepcharter.creature.TallyTuning;
import io.github.pkeppeler.deepcharter.ore.OreType;
import io.github.pkeppeler.deepcharter.pod.PodStats;
import io.github.pkeppeler.deepcharter.test.support.EarlyRunModel;
import io.github.pkeppeler.deepcharter.test.support.EarlyRunModel.Run;
import io.github.pkeppeler.deepcharter.test.support.EarlyRunModel.Zone;
import io.github.pkeppeler.deepcharter.test.support.TallyTimings;
import java.util.Map;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Server GameTests for #917: what a Tally costs a run of {@link EarlyRunModel}. A Tally is a tax, not a run-ender: it takes one ore, once,
 * and only from a pilot who lets it. The timings it rests on are measured in {@link TallyTest} (the ticks to clamp, to the sample and to break);
 * here they meet the spawn rate and the run's net. No world is built; the numbers go in {@code docs/design/mechanics.md}.
 */
public class TallyEconomyTest {
    private static final Logger LOGGER = LoggerFactory.getLogger(TallyEconomyTest.class);
    private static final TallyTuning TUNING = TallyTuning.DEFAULT;
    private static final int TICKS_PER_SECOND = 20;
    /** (A) Minutes of a run in layer 1 and in layer 2, down and back up: as the Retained's model counts them. */
    private static final double RUN_MINUTES = 4;
    /** (A) Share of a run that a pod spends within the casing's zone, where a Tally is sent: the Conduit runs beside the colony and the first shafts. */
    private static final double ZONE_SHARE = 0.5;
    /** (A) Share of Tallies sent that keep their pod in sense for the whole trail: the pod moves, and a pilot can outrun one. */
    private static final double KEEP_SHARE = 0.6;
    /** (A) Share of clamps that find the pilot not answering inside the hold: a pilot who reads the clamp line drills and the cutter breaks it. */
    private static final double UNANSWERED_SHARE = 0.4;
    /** (A) Seconds from the clamp line to the pilot drilling, for a pilot who watches the pod. */
    private static final double REACTION_SECONDS = 0.5;
    /** A first-hour pilot loses at most this share of a layer 1 run's net to Tallies, on average. */
    private static final double LAYER_ONE_SHARE_MAX = 0.05;
    /** A layer 2 run loses at most this share. */
    private static final double LAYER_TWO_SHARE_MAX = 0.15;
    /** A layer 2 run meets at least this many skims on average: a presence and not a rare sight. */
    private static final double LAYER_TWO_SKIMS_MIN = 0.1;
    /** A run is never skimmed more than once on average: a tax, not a run-ender. */
    private static final double SKIMS_MAX = 1;
    /** One skim, at the worst, costs at most this share of a layer 1 run's net. */
    private static final double WORST_SKIM_SHARE_MAX = 0.3;
    /** A trail takes about 30 seconds. */
    private static final double TRAIL_SECONDS_MIN = 25;

    private static final double TRAIL_SECONDS_MAX = 35;

    /** The mean value of the ore a drill takes out of a zone: what a sample is worth. */
    private static double meanOre(Zone zone) {
        double value = 0;
        for (Map.Entry<OreType, Double> ore : zone.chances().entrySet()) {
            value += ore.getValue() * ore.getKey().value();
        }
        return value / zone.oreChance();
    }

    /** Skims a run meets in a layer whose check passes {@code chance}: a check every interval, in the zone, kept and unanswered. */
    private static double skimsPerRun(double chance) {
        double checks = RUN_MINUTES * 60 * TICKS_PER_SECOND / TUNING.checkIntervalTicks();
        return checks * ZONE_SHARE * chance * KEEP_SHARE * UNANSWERED_SHARE;
    }

    /** The timings the pacing rests on (measured in {@link TallyTest}, pinned here): the trail, the cutter and the hold. */
    @GameTest
    public void theTrailTakesAboutThirtySecondsAndAPilotWhoWatchesBreaksItInTime(GameTestHelper helper) {
        double trail = (double) TUNING.trailTicks() / TICKS_PER_SECOND;
        double cutter = (double) TallyTimings.cutterBreakTicks(TUNING) / TICKS_PER_SECOND;
        double hold = (double) TUNING.podSampleTicks() / TICKS_PER_SECOND;
        LOGGER.info(
                "[tally-economy] trail {} s, the cutter breaks it in {} s, the hatch hold is {} s, the foot hold {} s",
                trail,
                cutter,
                hold,
                (double) TUNING.footSampleTicks() / TICKS_PER_SECOND);
        if (trail < TRAIL_SECONDS_MIN || trail > TRAIL_SECONDS_MAX) {
            throw failure(helper, "the trail takes %s s, not about 30", trail);
        }
        if (REACTION_SECONDS + cutter >= hold) {
            throw failure(
                    helper,
                    "a pilot who answers after %s s and a cutter that takes %s s do not beat the hold of %s s",
                    REACTION_SECONDS,
                    cutter,
                    hold);
        }
        if (cutter > 1.5) {
            throw failure(helper, "the cutter takes %s s to break a Tally: it is not easy to break", cutter);
        }
        helper.succeed();
    }

    /**
     * (A) Layer 1: the expected loss to Tallies, one ore at the mean ore of the zone, stays under 5 per cent of a stock Mole's net.
     * Layer 2: a run meets at least a tenth of a skim and at most one, and loses at most 15 per cent of a tier 2 run's net. Layer 2 sends more.
     */
    @GameTest
    public void aTallyIsATaxInLayerOneAndTwoNotARunEnder(GameTestHelper helper) {
        Zone layerOne = Zone.load("topsoil_claims");
        Run oneRun = EarlyRunModel.run(layerOne, PodStats.base(), 0);
        double oneSkims = skimsPerRun(Tallies.chance(1));
        double oneLoss = oneSkims * meanOre(layerOne);
        Zone layerTwo = Zone.load("upper_levels");
        Run twoRun = EarlyRunModel.layerTwoRun(layerTwo, EarlyRunModel.mole(2, 2, 2));
        double twoSkims = skimsPerRun(Tallies.chance(2));
        double twoLoss = twoSkims * meanOre(layerTwo);
        LOGGER.info(
                "[tally-economy] layer 1: {} skims a run, ${} lost of a net of ${} ({}); one sample is worth ${}",
                oneSkims,
                oneLoss,
                oneRun.net(),
                oneLoss / oneRun.net(),
                meanOre(layerOne));
        LOGGER.info(
                "[tally-economy] layer 2: {} skims a run, ${} lost of a net of ${} ({}); one sample is worth ${}",
                twoSkims,
                twoLoss,
                twoRun.net(),
                twoLoss / twoRun.net(),
                meanOre(layerTwo));
        if (oneLoss / oneRun.net() > LAYER_ONE_SHARE_MAX) {
            throw failure(
                    helper,
                    "Tallies cost a layer 1 run %s of its net on average, over %s",
                    oneLoss / oneRun.net(),
                    LAYER_ONE_SHARE_MAX);
        }
        if (twoLoss / twoRun.net() > LAYER_TWO_SHARE_MAX) {
            throw failure(
                    helper,
                    "Tallies cost a layer 2 run %s of its net on average, over %s",
                    twoLoss / twoRun.net(),
                    LAYER_TWO_SHARE_MAX);
        }
        if (twoSkims < LAYER_TWO_SKIMS_MIN || twoSkims > SKIMS_MAX || oneSkims > SKIMS_MAX) {
            throw failure(
                    helper,
                    "a run meets %s skims in layer 1 and %s in layer 2, outside the tax's %s to %s",
                    oneSkims,
                    twoSkims,
                    LAYER_TWO_SKIMS_MIN,
                    SKIMS_MAX);
        }
        if (twoSkims <= oneSkims) {
            throw failure(
                    helper, "layer 2 skims %s a run and layer 1 %s: they do not rise with depth", twoSkims, oneSkims);
        }
        helper.succeed();
    }

    /** A sample is the cheapest ore held, so the worst skim in a zone is its cheapest ore, a share of a run's net that stays well under half. */
    @GameTest
    public void theWorstSkimIsTheCheapestOreOfTheZone(GameTestHelper helper) {
        Zone layerOne = Zone.load("topsoil_claims");
        Run run = EarlyRunModel.run(layerOne, PodStats.base(), 0);
        double cheapest = layerOne.chances().keySet().stream()
                .mapToInt(OreType::value)
                .min()
                .orElseThrow();
        LOGGER.info(
                "[tally-economy] the worst skim in layer 1 is one ore of ${}, {} of a run's net of ${}",
                cheapest,
                cheapest / run.net(),
                run.net());
        if (cheapest / run.net() > WORST_SKIM_SHARE_MAX) {
            throw failure(
                    helper,
                    "one ore of $%s is over %s of a layer 1 run's net of $%s",
                    cheapest,
                    WORST_SKIM_SHARE_MAX,
                    run.net());
        }
        helper.succeed();
    }
}
