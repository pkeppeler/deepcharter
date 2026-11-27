package io.github.pkeppeler.deepcharter.test.support;

import io.github.pkeppeler.deepcharter.pod.PodGlideTuning;

/**
 * The floor comparison and the coast's reach of {@link PodDrives}, kept out of {@code src/client} so that {@code DriveJudgeTest} runs the
 * same code as the client drive tests. The reach is spelled out here, not read from {@code PodGlideTuning.coastReach()}, so the tests check the
 * game's copy against an independent one.
 */
public final class DriveJudge {
    private DriveJudge() {}

    /**
     * The network sends a position as a multiple of this (VecDeltaCodec.TRUNCATION_STEPS is 4096). Two rounded positions, and a lerp between
     * them, differ from the truth by at most one quantum.
     */
    public static final double POSITION_QUANTUM = 1.0 / 4096;

    public static boolean reaches(double measured, double floor) {
        return measured >= floor - POSITION_QUANTUM;
    }

    /** Ticks of its speed a whole coast draws: the full-speed ticks and the tail's share of the last. */
    public static double coastReach() {
        PodGlideTuning tuning = PodGlideTuning.DEFAULT;
        return tuning.coastTicks() - 1 + tuning.coastTailShare();
    }
}
