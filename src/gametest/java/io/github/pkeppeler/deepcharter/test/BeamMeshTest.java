package io.github.pkeppeler.deepcharter.test;

import com.google.errorprone.annotations.FormatMethod;
import com.google.errorprone.annotations.FormatString;
import io.github.pkeppeler.deepcharter.client.beam.BeamMesh;
import io.github.pkeppeler.deepcharter.client.theme.BeamStyle;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;

/**
 * Server GameTests for the shape of a fake light beam (#248): a cone of translucent quads that is brightest at the lamp, fades along
 * its length, widens with distance, and is narrower and brighter at its end when a wall shortens it. Plain Java over a vertex sink.
 */
public class BeamMeshTest {
    private static final double TOLERANCE = 1e-9;
    private static final int SIDES = 8;
    private static final double FULL = 20.0;
    private static final BeamStyle STYLE = new BeamStyle(0xFFFFFFFF, FULL, 0.1, 2.1, 0.2, 0.5, 0.4, 0.0, 0.0, 0.0);
    /** The brightest of the nested cones: here the core. */
    private static final double PEAK = Math.max(STYLE.alpha(), STYLE.coreAlpha());

    private record Vertex(double x, double y, double z, double alpha) {}

    private static List<Vertex> build(double length, double dx, double dy, double dz) {
        List<Vertex> out = new ArrayList<>();
        BeamMesh.emit(
                (x, y, z, alpha, u, v) -> out.add(new Vertex(x, y, z, alpha)),
                STYLE,
                SIDES,
                FULL,
                length,
                0,
                0,
                0,
                dx,
                dy,
                dz);
        return out;
    }

    private static double radiusAboutX(Vertex v) {
        return Math.sqrt(v.y * v.y + v.z * v.z);
    }

    @GameTest
    public void aBeamIsNestedConesOfQuads(GameTestHelper helper) {
        int expected = BeamMesh.SHELLS * SIDES * 4;
        int count = build(FULL, 1, 0, 0).size();
        if (count != expected) {
            throw failure(
                    helper,
                    "a beam of %s sides is %s vertices (%s nested cones of quads), got %s",
                    SIDES,
                    expected,
                    BeamMesh.SHELLS,
                    count);
        }
        helper.succeed();
    }

    @GameTest
    public void aBeamFadesAlongItsLength(GameTestHelper helper) {
        double startAlpha = 0;
        double endAlpha = 0;
        for (Vertex v : build(FULL, 1, 0, 0)) {
            if (Math.abs(v.x) < TOLERANCE) {
                startAlpha = Math.max(startAlpha, v.alpha);
            } else if (Math.abs(v.x - FULL) < TOLERANCE) {
                endAlpha = Math.max(endAlpha, v.alpha);
            }
        }
        if (Math.abs(startAlpha - PEAK) > TOLERANCE) {
            throw failure(helper, "the beam starts at alpha %s, got %s", PEAK, startAlpha);
        }
        if (endAlpha > 1e-6) {
            throw failure(helper, "an unobstructed beam fades to nothing at its reach, got alpha %s", endAlpha);
        }
        helper.succeed();
    }

    @GameTest
    public void aBeamWidensWithDistance(GameTestHelper helper) {
        double startRadius = 0;
        double endRadius = 0;
        for (Vertex v : build(FULL, 1, 0, 0)) {
            if (Math.abs(v.x) < TOLERANCE) {
                startRadius = Math.max(startRadius, radiusAboutX(v));
            } else if (Math.abs(v.x - FULL) < TOLERANCE) {
                endRadius = Math.max(endRadius, radiusAboutX(v));
            }
        }
        if (Math.abs(startRadius - STYLE.startRadius()) > 1e-6 || Math.abs(endRadius - STYLE.endRadius()) > 1e-6) {
            throw failure(
                    helper,
                    "radius should go from %s to %s, went from %s to %s",
                    STYLE.startRadius(),
                    STYLE.endRadius(),
                    startRadius,
                    endRadius);
        }
        helper.succeed();
    }

    @GameTest
    public void aShortenedBeamEndsNarrowerAndBrighterAtTheWall(GameTestHelper helper) {
        double length = 5.0;
        double endRadius = 0;
        double endAlpha = 0;
        double farthest = 0;
        for (Vertex v : build(length, 1, 0, 0)) {
            farthest = Math.max(farthest, v.x);
            if (Math.abs(v.x - length) < TOLERANCE) {
                endRadius = Math.max(endRadius, radiusAboutX(v));
                endAlpha = Math.max(endAlpha, v.alpha);
            }
        }
        double radius = STYLE.startRadius() + (STYLE.endRadius() - STYLE.startRadius()) * length / FULL;
        double alpha = PEAK * (1 - length / FULL);
        if (Math.abs(farthest - length) > TOLERANCE) {
            throw failure(helper, "a beam cut at %s blocks reaches %s", length, farthest);
        }
        if (Math.abs(endRadius - radius) > 1e-6 || Math.abs(endAlpha - alpha) > 1e-6) {
            throw failure(
                    helper,
                    "a beam cut at %s blocks ends at radius %s and alpha %s, got %s and %s",
                    length,
                    radius,
                    alpha,
                    endRadius,
                    endAlpha);
        }
        helper.succeed();
    }

    @GameTest
    public void aBeamPointingStraightDownIsWellFormed(GameTestHelper helper) {
        List<Vertex> vertices = build(FULL, 0, -1, 0);
        double lowest = 0;
        for (Vertex v : vertices) {
            if (Double.isNaN(v.x) || Double.isNaN(v.y) || Double.isNaN(v.z) || Double.isNaN(v.alpha)) {
                throw failure(helper, "a beam straight down has a NaN vertex");
            }
            lowest = Math.min(lowest, v.y);
        }
        if (Math.abs(lowest + FULL) > 1e-6) {
            throw failure(helper, "a beam straight down reaches y = %s, not -%s", lowest, FULL);
        }
        helper.succeed();
    }

    @GameTest
    public void aBeamOfNoLengthDrawsNothing(GameTestHelper helper) {
        if (!build(0.0, 1, 0, 0).isEmpty()) {
            throw failure(helper, "a beam of length 0 should have no vertices");
        }
        helper.succeed();
    }

    @FormatMethod
    private static RuntimeException failure(GameTestHelper helper, @FormatString String format, Object... args) {
        return helper.assertionException(Component.literal(String.format(format, args)));
    }
}
