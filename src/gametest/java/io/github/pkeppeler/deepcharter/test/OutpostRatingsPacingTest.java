package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.Failures.expect;

import io.github.pkeppeler.deepcharter.pod.Chassis;
import io.github.pkeppeler.deepcharter.pod.PodLiningTuning;
import io.github.pkeppeler.deepcharter.pod.PodStats;
import io.github.pkeppeler.deepcharter.test.support.EarlyRunModel;
import io.github.pkeppeler.deepcharter.test.support.EarlyRunModel.Run;
import io.github.pkeppeler.deepcharter.test.support.EarlyRunModel.Zone;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The pacing of #989 (ADR 0076): what a rated shell for a small outpost costs against a run's net. Rows are modelled, from
 * {@link EarlyRunModel}, and labelled so in the log, as an example for a future layer that asks level 2 (no layer does yet); no world is built. The shell is slag brick, which the processor fuses from a hopper pod's
 * spoil for no money, so its cost is runs of spoil, not dollars.
 */
public class OutpostRatingsPacingTest {
    private static final Logger LOGGER = LoggerFactory.getLogger(OutpostRatingsPacingTest.class);

    /** A small outpost: a room 3 by 3 inside and 2 high (a terminal and a bed beside it), with a shell one block thick on all six sides. */
    private static final int INSIDE_WIDTH = 3;

    private static final int INSIDE_HEIGHT = 2;
    private static final int SHELL_BLOCKS =
            (INSIDE_WIDTH + 2) * (INSIDE_WIDTH + 2) * (INSIDE_HEIGHT + 2) - INSIDE_WIDTH * INSIDE_WIDTH * INSIDE_HEIGHT;
    /** The shell takes this many hopper runs of a Mole with tier 2 parts at most, in layer 2 and in layer 1. */
    private static final int SHELL_RUNS_MAX = 4;

    /** (A) Modelled: bricks one hopper run brings home, from the spoil it keeps. */
    private static int bricksPerRun(Zone zone, Run run) {
        return EarlyRunModel.spoil(Chassis.MOLE, zone, run) / PodLiningTuning.DEFAULT.spoilPerBrick();
    }

    @GameTest
    public void aRatedShellForASmallOutpostCostsAFewHopperRuns(GameTestHelper helper) {
        float mass = EarlyRunModel.hopperMass();
        Zone upper = Zone.load("upper_levels");
        PodStats mole = EarlyRunModel.mole(2, 2, 2);
        Run plain = EarlyRunModel.layerTwoRun(upper, mole);
        Run hopper = EarlyRunModel.layerTwoRun(upper, mole, mass);
        int bricks = bricksPerRun(upper, hopper);
        int runs = (int) Math.ceil(SHELL_BLOCKS / (double) bricks);
        LOGGER.info(
                "[ratings] modelled, an example for a future layer asking level 2 (no layer does): a {}x{}x{} room's shell is {} blocks of slag brick = {} spoil; a layer 2 hopper run of a Mole (tier 2 parts) brings {} bricks and nets ${} against ${} without the hopper, so the shell takes {} runs",
                INSIDE_WIDTH,
                INSIDE_WIDTH,
                INSIDE_HEIGHT,
                SHELL_BLOCKS,
                SHELL_BLOCKS * PodLiningTuning.DEFAULT.spoilPerBrick(),
                bricks,
                Math.round(hopper.net()),
                Math.round(plain.net()),
                runs);
        expect(
                helper,
                Math.round(hopper.net()) == Math.round(plain.net()),
                "a full hopper should not cost the run its income: $%s against $%s",
                hopper.net(),
                plain.net());
        expect(
                helper,
                runs <= SHELL_RUNS_MAX,
                "the shell takes %s layer 2 hopper runs, at most %s are allowed",
                runs,
                SHELL_RUNS_MAX);
        helper.succeed();
    }

    @GameTest
    public void aLayerOneShellOfSlagCostsTheSameFewRuns(GameTestHelper helper) {
        float mass = EarlyRunModel.hopperMass();
        Zone topsoil = Zone.load("topsoil_claims");
        Run run = EarlyRunModel.run(topsoil, PodStats.base(), 0, mass);
        int bricks = bricksPerRun(topsoil, run);
        int runs = (int) Math.ceil(SHELL_BLOCKS / (double) bricks);
        LOGGER.info(
                "[ratings] modelled, the same example shell from layer 1 spoil: a stock Mole's hopper run brings {} bricks and nets ${}, so the shell takes {} runs; layer 1's own rock also holds layer 1 and is mined by hand",
                bricks,
                Math.round(run.net()),
                runs);
        expect(
                helper,
                runs <= SHELL_RUNS_MAX,
                "the shell takes %s layer 1 hopper runs, at most %s are allowed",
                runs,
                SHELL_RUNS_MAX);
        helper.succeed();
    }
}
