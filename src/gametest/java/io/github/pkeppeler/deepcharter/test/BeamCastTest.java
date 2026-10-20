package io.github.pkeppeler.deepcharter.test;

import com.google.errorprone.annotations.FormatMethod;
import com.google.errorprone.annotations.FormatString;
import io.github.pkeppeler.deepcharter.client.beam.BeamCast;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * Server GameTests for how long a fake light beam is (#248): the full length in open air, shortened by a wall (the beam stops where it
 * enters the block), nothing when the lamp is dark, and a shorter reach for a weaker lamp. The cast is plain Java over a block
 * predicate, so no world is needed; {@code BeamsClientTest} checks the real blocks.
 */
public class BeamCastTest {
    private static final double TOLERANCE = 1e-6;
    private static final double MAX = 24.0;

    private static final BeamCast.Solid OPEN = (x, y, z) -> false;

    private static BeamCast.Solid wallAtX(int wall) {
        return (x, y, z) -> x == wall;
    }

    private static void expect(GameTestHelper helper, String what, double expected, double actual) {
        if (Math.abs(expected - actual) > TOLERANCE) {
            throw failure(helper, "%s: expected %s, got %s", what, expected, actual);
        }
    }

    @GameTest
    public void aBeamInOpenAirIsItsFullLength(GameTestHelper helper) {
        expect(helper, "along x", MAX, BeamCast.length(OPEN, 0.5, 0.5, 0.5, 1, 0, 0, MAX));
        expect(helper, "straight down", MAX, BeamCast.length(OPEN, 0.5, 30.5, 0.5, 0, -1, 0, MAX));
        double d = Math.sqrt(1.0 / 3.0);
        expect(helper, "along a diagonal", MAX, BeamCast.length(OPEN, 0.1, 0.2, 0.3, d, d, d, MAX));
        helper.succeed();
    }

    @GameTest
    public void aWallCutsTheBeamWhereItEntersTheBlock(GameTestHelper helper) {
        // From x = 0.5 to the face of the wall block at x = 5 is 4.5 blocks, either way round.
        expect(helper, "wall ahead", 4.5, BeamCast.length(wallAtX(5), 0.5, 0.5, 0.5, 1, 0, 0, MAX));
        expect(helper, "wall behind", 5.5, BeamCast.length(wallAtX(-6), 0.5, 0.5, 0.5, -1, 0, 0, MAX));
        expect(helper, "floor below", 2.2, BeamCast.length((x, y, z) -> y <= 0, 0.5, 3.2, 0.5, 0, -1, 0, MAX));
        helper.succeed();
    }

    @GameTest
    public void aWallBeyondTheReachDoesNotShortenTheBeam(GameTestHelper helper) {
        expect(helper, "wall past the end", MAX, BeamCast.length(wallAtX(40), 0.5, 0.5, 0.5, 1, 0, 0, MAX));
        // The cell the beam would enter at exactly its reach is out: the reach is the cap.
        expect(helper, "wall at the end", MAX, BeamCast.length(wallAtX(25), 0.5, 0.5, 0.5, 1, 0, 0, MAX));
        helper.succeed();
    }

    @GameTest
    public void aSlantedBeamStopsAtTheFirstBlockItEnters(GameTestHelper helper) {
        // A beam of slope 1 in x and y, from the corner of cell (0, 0): it enters the ceiling at y = 3 after 3 *
        // sqrt(2) blocks.
        double d = Math.sqrt(0.5);
        double expected = 3.0 * Math.sqrt(2.0);
        expect(helper, "ceiling", expected, BeamCast.length((x, y, z) -> y >= 3, 0.0, 0.0, 0.5, d, d, 0, MAX));
        helper.succeed();
    }

    @GameTest
    public void aLampInsideABlockCastsNothing(GameTestHelper helper) {
        expect(helper, "inside a wall", 0.0, BeamCast.length((x, y, z) -> true, 0.5, 0.5, 0.5, 1, 0, 0, MAX));
        helper.succeed();
    }

    @GameTest
    public void aDarkLampHasNoBeam(GameTestHelper helper) {
        expect(helper, "unlit", 0.0, BeamCast.reach(false, 15, MAX));
        expect(helper, "no lights part", 0.0, BeamCast.reach(true, 0, MAX));
        helper.succeed();
    }

    @GameTest
    public void aWeakerLampReachesLess(GameTestHelper helper) {
        expect(helper, "full lamp", MAX, BeamCast.reach(true, 15, MAX));
        expect(helper, "half lamp", MAX * 6 / 15, BeamCast.reach(true, 6, MAX));
        helper.succeed();
    }

    @FormatMethod
    private static RuntimeException failure(
            GameTestHelper helper, @FormatString String format, @Nullable Object... args) {
        return helper.assertionException(Component.literal(String.format(format, args)));
    }
}
