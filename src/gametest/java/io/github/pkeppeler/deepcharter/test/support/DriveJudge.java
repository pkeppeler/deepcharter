package io.github.pkeppeler.deepcharter.test.support;

/** The floor comparison of {@link PodDrives}, apart from the game so {@code DriveJudgeTest} runs the same code. */
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
}
