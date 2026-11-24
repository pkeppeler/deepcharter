package io.github.pkeppeler.deepcharter.test;

/** The floor comparison of {@link PodDriveSmoothClientTest}, apart from the game so {@link DriveJudgeTest} runs the same code. */
final class DriveJudge {
    private DriveJudge() {}

    /**
     * The network sends a position as a multiple of this (VecDeltaCodec.TRUNCATION_STEPS is 4096). Two rounded positions, and a lerp between
     * them, differ from the truth by at most one quantum.
     */
    static final double POSITION_QUANTUM = 1.0 / 4096;

    static boolean reaches(double measured, double floor) {
        return measured >= floor - POSITION_QUANTUM;
    }
}
