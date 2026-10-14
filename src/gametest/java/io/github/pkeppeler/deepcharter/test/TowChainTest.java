package io.github.pkeppeler.deepcharter.test;

import io.github.pkeppeler.deepcharter.client.pod.TowChain;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;

/**
 * Server GameTests for the geometry of the tow line (#508): where it starts and ends, how far it hangs when slack, that it is straight
 * when taut, and how many links it has. The drawing is checked by {@code TowingClientTest}.
 */
public class TowChainTest {
    private static final double ROPE = 5.0;
    private static final double RADIUS = 1.0;
    private static final double LINK = 0.2;
    private static final double TOLERANCE = 1e-6;

    private static TowChain line(double between) {
        TowChain chain = new TowChain();
        chain.build(0, 0, 0, RADIUS, between, 0, 0, RADIUS, ROPE, LINK);
        return chain;
    }

    private static double sagOf(TowChain chain) {
        double lowest = 0;
        for (int i = 0; i <= chain.links; i++) {
            lowest = Math.min(lowest, chain.y[i]);
        }
        return -lowest;
    }

    @GameTest
    public void theLineRunsFromHitchToHitch(GameTestHelper helper) {
        TowChain chain = line(2 * RADIUS + 3.0);
        if (Math.abs(chain.x[0] - RADIUS) > TOLERANCE
                || Math.abs(chain.x[chain.links] - (2 * RADIUS + 3.0 - RADIUS)) > TOLERANCE) {
            throw failure(
                    helper, "the line should run from x=1 to x=4, ran from %s to %s", chain.x[0], chain.x[chain.links]);
        }
        helper.succeed();
    }

    @GameTest
    public void aSlackLineHangsAndATautOneIsStraight(GameTestHelper helper) {
        TowChain slack = line(2 * RADIUS + 3.0);
        // A rope of 5 over a chord of 3 hangs sqrt(3 * 3 * 2 / 8) = 1.5 blocks at its lowest, to within the curve's
        // sampling.
        if (Math.abs(sagOf(slack) - 1.5) > 0.05) {
            throw failure(
                    helper, "a rope of %s over a chord of 3 should hang about 1.5 blocks, hung %s", ROPE, sagOf(slack));
        }
        TowChain taut = line(2 * RADIUS + ROPE);
        TowChain stretched = line(2 * RADIUS + ROPE + 2.0);
        if (sagOf(taut) > TOLERANCE || sagOf(stretched) > TOLERANCE) {
            throw failure(helper, "a taut line must not hang, hung %s and %s", sagOf(taut), sagOf(stretched));
        }
        helper.succeed();
    }

    @GameTest
    public void theLineFollowsTheHitchesInAnyDirection(GameTestHelper helper) {
        TowChain chain = new TowChain();
        chain.build(1, 2, 3, RADIUS, 1, 2 + 2 * RADIUS + ROPE, 3, RADIUS, ROPE, LINK);
        if (Math.abs(chain.y[0] - 3.0) > TOLERANCE || Math.abs(chain.y[chain.links] - 8.0) > TOLERANCE) {
            throw failure(
                    helper,
                    "a pod hanging straight down should hitch at its top and the tower at its foot, got y %s to %s",
                    chain.y[0],
                    chain.y[chain.links]);
        }
        helper.succeed();
    }

    @GameTest
    public void theLinkCountFollowsTheArcAndIsCapped(GameTestHelper helper) {
        TowChain taut = line(2 * RADIUS + ROPE);
        if (taut.links != (int) Math.ceil(ROPE / LINK)) {
            throw failure(
                    helper,
                    "a taut rope of %s with links of %s should have %s links, has %s",
                    ROPE,
                    LINK,
                    (int) Math.ceil(ROPE / LINK),
                    taut.links);
        }
        TowChain slack = line(2 * RADIUS + 3.0);
        if (slack.links <= (int) Math.ceil(3.0 / LINK)) {
            throw failure(
                    helper,
                    "a slack line is longer than its chord, so it needs more links than %s, has %s",
                    (int) Math.ceil(3.0 / LINK),
                    slack.links);
        }
        TowChain fine = new TowChain();
        fine.build(0, 0, 0, RADIUS, 40, 0, 0, RADIUS, ROPE, 0.001);
        if (fine.links != TowChain.MAX_LINKS) {
            throw failure(helper, "a very fine chain is capped at %s links, has %s", TowChain.MAX_LINKS, fine.links);
        }
        TowChain together = line(0.0);
        if (together.links < 1) {
            throw failure(helper, "pods on the same spot still have a line of at least one link");
        }
        helper.succeed();
    }

    private static RuntimeException failure(GameTestHelper helper, String format, Object... args) {
        return helper.assertionException(Component.literal(String.format(format, args)));
    }
}
