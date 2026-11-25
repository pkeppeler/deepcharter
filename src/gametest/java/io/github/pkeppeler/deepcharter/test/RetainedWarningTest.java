package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.Failures.expect;

import io.github.pkeppeler.deepcharter.sound.ambience.AmbienceTuning;
import io.github.pkeppeler.deepcharter.sound.ambience.QuietTracker;
import io.github.pkeppeler.deepcharter.sound.ambience.RetainedSight;
import io.github.pkeppeler.deepcharter.sound.ambience.RetainedWarning;
import io.github.pkeppeler.deepcharter.sound.ambience.RetainedWarning.Figure;
import io.github.pkeppeler.deepcharter.sound.ambience.WarningTuning;
import java.util.List;
import java.util.OptionalInt;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * Server GameTests for #861's warning in the dark: when the song drifts from a Retained. The decision is pure (no client), so each rule is
 * run for as many ticks as it needs in a moment: no cue without a figure, none for a seen one, a cue for one in range and unseen, once per
 * encounter, a cooldown, and silence while something sounds or near a wreck's phonograph. Whether a figure is seen (the view cone and the
 * ray against rock) runs on a level here, as the client's does.
 */
public class RetainedWarningTest {
    private static final WarningTuning TUNING = WarningTuning.DEFAULT;
    private static final double NEAR = 24;

    private static RetainedWarning warning() {
        return new RetainedWarning(TUNING);
    }

    private static List<Figure> one(int id, double distance, boolean seen) {
        return List.of(new Figure(id, distance, seen));
    }

    /** Ticks {@code count} times with the same arguments; returns the ticks (from 0) on which a cue came. */
    private static List<Integer> run(
            RetainedWarning warning, int count, List<Figure> figures, boolean quiet, boolean wreck) {
        java.util.ArrayList<Integer> cues = new java.util.ArrayList<>();
        for (int tick = 0; tick < count; tick++) {
            OptionalInt cue = warning.tick(figures, quiet, wreck);
            if (cue.isPresent()) {
                cues.add(tick);
            }
        }
        return cues;
    }

    @GameTest
    public void withNoFigureThereIsNeverACueHoweverLongTheDarkIsQuiet(GameTestHelper helper) {
        RetainedWarning warning = warning();
        expect(helper, run(warning, 24_000 * 10, List.of(), false, false).isEmpty(), "a cue came with no figure near");
        helper.succeed();
    }

    @GameTest
    public void aFigureThePlayerSeesIsNeverCued(GameTestHelper helper) {
        RetainedWarning warning = warning();
        expect(helper, run(warning, 50_000, one(1, NEAR, true), false, false).isEmpty(), "a seen figure was cued");
        helper.succeed();
    }

    @GameTest
    public void anUnseenFigureInRangeIsCuedOnceItHasStayedUnseenAWhile(GameTestHelper helper) {
        RetainedWarning warning = warning();
        List<Integer> cues = run(warning, 1_000, one(1, NEAR, false), false, false);
        expect(helper, cues.equals(List.of(TUNING.confirmTicks() - 1)), "the cue came at %s", cues);
        helper.succeed();
    }

    @GameTest
    public void aFigureOutOfRangeIsNotCuedNorOneTooClose(GameTestHelper helper) {
        RetainedWarning warning = warning();
        expect(
                helper,
                run(warning, 5_000, one(1, TUNING.maxRange() + 1, false), false, false)
                        .isEmpty(),
                "a far figure was cued");
        expect(
                helper,
                run(warning, 5_000, one(2, TUNING.minRange() - 1, false), false, false)
                        .isEmpty(),
                "a close figure was cued");
        List<Integer> edges = run(warning(), 1_000, one(3, TUNING.minRange(), false), false, false);
        expect(helper, edges.size() == 1, "a figure at the near edge of the range was not cued: %s", edges);
        expect(
                helper,
                run(warning(), 1_000, one(4, TUNING.maxRange(), false), false, false)
                                .size()
                        == 1,
                "a figure at the far edge was not cued");
        helper.succeed();
    }

    @GameTest
    public void aGlanceAtTheFigureStartsTheCountAgain(GameTestHelper helper) {
        RetainedWarning warning = warning();
        for (int round = 0; round < 10; round++) {
            expect(
                    helper,
                    run(warning, TUNING.confirmTicks() - 1, one(1, NEAR, false), false, false)
                            .isEmpty(),
                    "cued before the figure had stayed unseen long enough");
            run(warning, 1, one(1, NEAR, true), false, false);
        }
        expect(
                helper,
                run(warning, TUNING.confirmTicks(), one(1, NEAR, false), false, false)
                                .size()
                        == 1,
                "never cued after the glances");
        helper.succeed();
    }

    @GameTest
    public void aFigureIsCuedOnceHoweverLongItStaysAndTheCooldownHoldsTheNextBack(GameTestHelper helper) {
        RetainedWarning warning = warning();
        int day = 24_000;
        expect(
                helper,
                run(warning, day, one(1, NEAR, false), false, false).size() == 1,
                "one figure was cued more than once in a day");
        // A second figure at once: the cooldown holds it back until it has run, counted from the first cue.
        List<Figure> two = List.of(new Figure(1, NEAR, false), new Figure(2, NEAR, false));
        RetainedWarning fresh = warning();
        List<Integer> cues = run(fresh, TUNING.cooldownTicks() * 3, two, false, false);
        expect(helper, cues.size() == 2, "two figures were cued %s times: %s", cues.size(), cues);
        expect(
                helper,
                cues.get(1) - cues.get(0) == TUNING.cooldownTicks(),
                "the second cue came %s ticks after the first, not the cooldown's %s",
                cues.get(1) - cues.get(0),
                TUNING.cooldownTicks());
        expect(
                helper,
                TUNING.cooldownTicks() >= 3 * 60 * 20,
                "a cooldown of %s ticks is not several minutes",
                TUNING.cooldownTicks());
        helper.succeed();
    }

    @GameTest
    public void aFigureThatLeavesTheListAndReturnsIsANewEncounterAfterTheCooldown(GameTestHelper helper) {
        RetainedWarning warning = warning();
        expect(
                helper,
                run(warning, 200, one(1, NEAR, false), false, false).size() == 1,
                "the first encounter was not cued");
        run(warning, TUNING.cooldownTicks(), List.of(), false, false);
        expect(
                helper,
                run(warning, 200, one(1, NEAR, false), false, false).size() == 1,
                "a returning figure was not cued as a new encounter");
        helper.succeed();
    }

    @GameTest
    public void nothingIsCuedWhileSomethingSoundsAndItComesWhenTheDarkIsQuietAgain(GameTestHelper helper) {
        RetainedWarning warning = warning();
        expect(helper, run(warning, 2_000, one(1, NEAR, false), true, false).isEmpty(), "a cue came over a sound");
        List<Integer> after = run(warning, 5, one(1, NEAR, false), false, false);
        expect(helper, after.equals(List.of(0)), "the cue did not come when the sound ended: %s", after);
        helper.succeed();
    }

    @GameTest
    public void nothingIsCuedNearAPlayingWreckPhonographAndTheCountStartsAgainAfter(GameTestHelper helper) {
        RetainedWarning warning = warning();
        expect(
                helper,
                run(warning, 5_000, one(1, NEAR, false), false, true).isEmpty(),
                "a cue came near a wreck's phonograph");
        List<Integer> after = run(warning, TUNING.confirmTicks() + 5, one(1, NEAR, false), false, false);
        expect(
                helper,
                after.equals(List.of(TUNING.confirmTicks() - 1)),
                "the count did not start again after the wreck's song: %s",
                after);
        helper.succeed();
    }

    @GameTest
    public void theNearestOfTwoUnseenFiguresIsTheOneItComesFrom(GameTestHelper helper) {
        RetainedWarning warning = warning();
        List<Figure> two = List.of(new Figure(7, 30, false), new Figure(8, 20, false));
        OptionalInt from = OptionalInt.empty();
        for (int tick = 0; tick < TUNING.confirmTicks() && from.isEmpty(); tick++) {
            from = warning.tick(two, false, false);
        }
        expect(helper, from.equals(OptionalInt.of(8)), "the song came from %s", from);
        helper.succeed();
    }

    @GameTest
    public void theViewConeSaysWhatIsAheadAndNotWhatIsBehindOrAbove(GameTestHelper helper) {
        double half = TUNING.viewHalfAngle();
        expect(helper, RetainedWarning.inCone(0, 0, 1, 0, 0, 20, half), "straight ahead is not in view");
        expect(helper, RetainedWarning.inCone(0, 0, 1, 10, 0, 20, half), "a little to the side is not in view");
        expect(helper, !RetainedWarning.inCone(0, 0, 1, 0, 0, -20, half), "behind is in view");
        expect(helper, !RetainedWarning.inCone(0, 0, 1, 20, 0, 0, half) || half >= 90, "square to the side is in view");
        expect(helper, !RetainedWarning.inCone(0, 0, 1, 0, 0, 0, half), "a zero direction is in view");
        helper.succeed();
    }

    @GameTest
    public void aFigureIsSeenOnlyInsideTheViewConeWithNoRockBetween(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Vec3 eyes = Vec3.atCenterOf(helper.absolutePos(new BlockPos(1, 3, 3)));
        // The head is two blocks above the eyes' line and the body centre on it, five blocks off.
        Vec3 head = eyes.add(5, 2, 0);
        Vec3 body = eyes.add(5, 0, 0);
        Vec3 ahead = new Vec3(1, 0, 0);
        double half = TUNING.viewHalfAngle();
        expect(
                helper,
                RetainedSight.sees(level, eyes, ahead, head, body, half),
                "a figure ahead in the open was not seen");
        expect(helper, !RetainedSight.sees(level, eyes, ahead.scale(-1), head, body, half), "a figure behind was seen");
        expect(
                helper,
                !RetainedSight.sees(level, eyes, new Vec3(0, 0, 1), head, body, half),
                "a figure to the side was seen");
        // Rock across the head's line only (a ceiling lip): the body shows, so the figure is seen.
        helper.setBlock(new BlockPos(4, 4, 3), Blocks.STONE);
        expect(
                helper,
                RetainedSight.sees(level, eyes, ahead, head, body, half),
                "a figure in view with its head behind a lip was not seen");
        // Rock across the body's line too: unseen.
        helper.setBlock(new BlockPos(4, 3, 3), Blocks.STONE);
        expect(
                helper,
                !RetainedSight.sees(level, eyes, ahead, head, body, half),
                "a figure behind rock, head and body, was seen");
        helper.setBlock(new BlockPos(4, 4, 3), Blocks.AIR);
        expect(
                helper,
                RetainedSight.sees(level, eyes, ahead, head, body, half),
                "a figure with only its head in view was not seen");
        helper.setBlock(new BlockPos(4, 3, 3), Blocks.AIR);
        expect(
                helper,
                RetainedSight.sees(level, eyes, ahead, head, body, half),
                "a figure was not seen once the rock was gone");
        helper.succeed();
    }

    @GameTest
    public void theWarningKeepsTheAmbiencesQuietRuleAndTheSongHoldsTheAmbienceBack(GameTestHelper helper) {
        expect(
                helper,
                AmbienceTuning.DEFAULT.quietPrefixes().contains("song."),
                "the songs do not hold the ambience back: %s",
                AmbienceTuning.DEFAULT.quietPrefixes());
        QuietTracker<String> tracker = new QuietTracker<>(sound -> true, AmbienceTuning.DEFAULT.quietAfterTicks());
        tracker.started("song.dark", 10);
        expect(helper, tracker.quiet(11), "the dark's song did not hold the ambience back");
        helper.succeed();
    }
}
