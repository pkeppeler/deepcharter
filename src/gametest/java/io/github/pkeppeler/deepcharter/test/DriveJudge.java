package io.github.pkeppeler.deepcharter.test;

/** The floor comparisons of {@link PodDriveSmoothClientTest}, apart from the game so {@link DriveJudgeTest} runs the same code. */
final class DriveJudge {
    private DriveJudge() {}

    /** Four float ulps at the coordinate: the drawn positions are float-quantized, a real hitch (0.025 against 0.1) is orders above. */
    static double tolerance(double coordinate) {
        return 4 * Math.ulp((float) coordinate);
    }

    /** True when {@code measured} reaches {@code floor}, give or take the float noise at {@code coordinate}. */
    static boolean reaches(double measured, double floor, double coordinate) {
        return measured >= floor - tolerance(coordinate);
    }

    /** The largest coordinate magnitude of a position, which sets the float precision its differences carry. */
    static double magnitude(double x, double y, double z) {
        return Math.max(Math.abs(x), Math.max(Math.abs(y), Math.abs(z)));
    }
}
