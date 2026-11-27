package io.github.pkeppeler.deepcharter.pod;

/**
 * Tunables for the rotor's ground cushion (#1432), read as {@code HardLandingTuning.DEFAULT.thing()}. Speeds are
 * blocks per tick.
 *
 * @param cushionHeight blocks of air under the hull within which a crewed, powered pod's rotor cushions its sink,
 *                      above 0
 * @param cushionBrake  sink speed the cushion takes off each tick, above 0
 * @param cushionSink   the cushion brakes a sink down to this and no slower, 0 or more
 */
public record HardLandingTuning(double cushionHeight, double cushionBrake, double cushionSink) {
    /** (A) The numbers and what they land are in mechanics.md, Soft landings. */
    public static final HardLandingTuning DEFAULT = new HardLandingTuning(3, 0.12, 0.4);

    public HardLandingTuning {
        if (!(cushionHeight > 0)) {
            throw new IllegalArgumentException("the cushion reaches above 0 blocks, got " + cushionHeight);
        }
        if (!(cushionBrake > 0)) {
            throw new IllegalArgumentException("the cushion brakes above 0 a tick, got " + cushionBrake);
        }
        if (!(cushionSink >= 0)) {
            throw new IllegalArgumentException("the cushion's floor is a sink of 0 or more, got " + cushionSink);
        }
    }
}
