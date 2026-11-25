package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.Failures.failure;

import io.github.pkeppeler.deepcharter.test.support.DriveJudge;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

/** Server GameTest for #1227: the drive-smooth floor passes the network position quantum and fails a real hitch. No world is built. */
public class DriveJudgeTest {
    /** A pod's speed in blocks per tick and the share of it a steady drive must cover each tick, as in PodDrives. */
    private static final double SPEED = 0.2f;

    private static final double SHARE = 0.5;

    @GameTest
    public void aStepWithinTheNetworkQuantumUnderTheFloorPassesAndARealHitchFails(GameTestHelper helper) {
        double floor = SHARE * SPEED;
        double quantum = DriveJudge.POSITION_QUANTUM;
        expect(helper, "the CI step 0.0999755859375", 0.0999755859375, floor, true);
        expect(helper, "the floor itself", floor, floor, true);
        expect(helper, "one quantum under", floor - quantum, floor, true);
        expect(helper, "just outside the tolerance", floor - 2 * quantum, floor, false);
        expect(helper, "a quarter of the speed", 0.25 * SPEED, floor, false);
        expect(helper, "#1112's stall", 0.025, floor, false);
        helper.succeed();
    }

    private static void expect(GameTestHelper helper, String what, double measured, double floor, boolean passes) {
        if (DriveJudge.reaches(measured, floor) != passes) {
            throw failure(
                    helper,
                    "%s: a step of %s against a floor of %s should %s",
                    what,
                    measured,
                    floor,
                    passes ? "pass" : "fail");
        }
    }
}
