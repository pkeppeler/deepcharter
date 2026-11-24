package io.github.pkeppeler.deepcharter.test;

import io.github.pkeppeler.deepcharter.layer.LayerTuning;
import java.util.function.UnaryOperator;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

/**
 * Server GameTests of the seamless swap (#1229, ADR 0089, slice 3): the tuning that bounds it, the readiness of a
 * vehicle's riders, the swap itself and its recovery.
 */
public class SeamSwapTest {
    /** {@link LayerTuning#DEFAULT} with one component changed, through the canonical constructor. */
    private static LayerTuning with(UnaryOperator<Builder> change) {
        return change.apply(new Builder(LayerTuning.DEFAULT)).build();
    }

    /** The two slice 3 components over the default; the rest are the default's. */
    private record Builder(int swapRadius, int flawlessFrames, int radius) {
        Builder(LayerTuning base) {
            this(base.seamSwapRadius(), base.seamFlawlessFrames(), base.seamRadius());
        }

        LayerTuning build() {
            LayerTuning d = LayerTuning.DEFAULT;
            return new LayerTuning(
                    d.seaLevel(),
                    d.feetPerBlock(),
                    d.crustThickness(),
                    d.pocketRadius(),
                    d.pocketHeight(),
                    d.structureSpacing(),
                    d.structureFloorMargin(),
                    d.structureCeilingMargin(),
                    d.lavaHullPerSecond(),
                    d.lavaCueTicks(),
                    d.grainPulseTicks(),
                    d.grainSqueezeHull(),
                    d.grainSqueezeDamage(),
                    d.grainSkateHeatPerSecond(),
                    d.grainDecoyChance(),
                    d.glowcapLight(),
                    d.seamLeadPerRow(),
                    d.seamFloorMargin(),
                    d.seamSpeedWindow(),
                    d.seamDesignFallSpeed(),
                    radius,
                    d.seamRefreshTicks(),
                    d.seamTicketTimeout(),
                    d.seamRecentreChunks(),
                    d.seamBytesPerTick(),
                    d.seamMissedWindowTicks(),
                    swapRadius,
                    flawlessFrames);
        }
    }

    private static void expectRejected(GameTestHelper helper, String what, UnaryOperator<Builder> change) {
        try {
            with(change);
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw helper.assertionException(what + " should be refused");
    }

    /** The shipped swap tuning is 3 chunks and 2 frames, and a swap radius beyond what is sendable, or below 1, is refused. */
    @GameTest
    public void swapTuningIsValidated(GameTestHelper helper) {
        LayerTuning tuning = LayerTuning.DEFAULT;
        if (tuning.seamSwapRadius() != 3 || tuning.seamFlawlessFrames() != 2) {
            throw helper.assertionException("the swap tuning is not 3 chunks and 2 frames: " + tuning);
        }
        with(b -> new Builder(3, 0, 4));
        expectRejected(helper, "a swap radius of 4, past the send radius 3", b -> new Builder(4, 2, 4));
        expectRejected(helper, "a swap radius of 0", b -> new Builder(0, 2, 4));
        expectRejected(helper, "a negative Flawless Frames count", b -> new Builder(3, -1, 4));
        helper.succeed();
    }
}
