package io.github.pkeppeler.deepcharter.test;

/** The floor comparison of {@link PodDriveSmoothClientTest}, apart from the game so {@link DriveJudgeTest} runs the same code. */
final class DriveJudge {
    private DriveJudge() {}

    /** The network sends an entity's position as a multiple of this (VecDeltaCodec.TRUNCATION_STEPS is 4096), so the client's drawn one is rounded to it. */
    static final double POSITION_QUANTUM = 1.0 / 4096;

    /** Two rounded positions (each off by at most half a quantum, and a lerp between them no more) differ from the truth by at most one quantum. */
    static final double TOLERANCE = POSITION_QUANTUM;

    static boolean reaches(double measured, double floor) {
        return measured >= floor - TOLERANCE;
    }
}
