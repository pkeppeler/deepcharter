package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.Failures.failure;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

/** Server GameTest for #1227: the drive-smooth floor passes float noise at the coordinate and fails a real hitch. No world is built. */
public class DriveJudgeTest {
    /** A pod's speed in blocks per tick and the share of it a steady drive must cover each tick, as in PodDriveSmoothClientTest. */
    private static final double SPEED = 0.2f;

    private static final double SHARE = 0.5;
    private static final double COORDINATE = 300;

    @GameTest
    public void aStepAFewFloatUlpsUnderTheFloorPassesAndARealHitchFails(GameTestHelper helper) {
        double floor = SHARE * SPEED;
        float ulp = Math.ulp((float) COORDINATE);
        expect(helper, "the CI step 0.0999755859375", 0.0999755859375, floor, true);
        expect(helper, "the floor itself", floor, floor, true);
        expect(helper, "one ulp under", floor - ulp, floor, true);
        expect(helper, "three ulps under", floor - 3 * ulp, floor, true);
        expect(helper, "a quarter of the speed", 0.25 * SPEED, floor, false);
        expect(helper, "#1112's stall", 0.025, floor, false);
        helper.succeed();
    }

    private static void expect(GameTestHelper helper, String what, double measured, double floor, boolean passes) {
        if (DriveJudge.reaches(measured, floor, COORDINATE) != passes) {
            throw failure(
                    helper,
                    "%s: a step of %s against a floor of %s at coordinate %s should %s",
                    what,
                    measured,
                    floor,
                    COORDINATE,
                    passes ? "pass" : "fail");
        }
    }
}
