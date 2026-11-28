package io.github.pkeppeler.deepcharter.pod;

/**
 * What ore carried loose in a rider's pack costs the pod (ADR 0110), read as {@code LooseOreTuning.DEFAULT.thing()}.
 *
 * @param weightFactor  what a rider's loose ore weighs against the pod's lift, as a multiple of its mass: it is not
 *                      racked and shifts. Fuel and other pack items stay at 1; at least 1
 * @param crampPerMass  the share of the drill's speed and of the pod's steering that each unit of loose ore mass takes
 * @param maxCramp      the most the cab's cramp takes from either, at least 0 and under 1, so a pod still drills and
 *                      turns
 * @param rattlePerOre  hull points each loose ore adds to a hard landing's damage, 0 or more
 */
public record LooseOreTuning(float weightFactor, float crampPerMass, float maxCramp, float rattlePerOre) {
    /**
     * (A) Loose ore weighs double. Each unit of its mass takes half a percent of the drill's speed and the steering,
     * so a full stock bay of goldium (7 ore, 70 mass) in the pack takes 35% of both, and no more than half of them
     * ever. Each loose ore adds one hull point to a hard landing.
     */
    public static final LooseOreTuning DEFAULT = new LooseOreTuning(2f, 0.005f, 0.5f, 1f);

    public LooseOreTuning {
        if (!(weightFactor >= 1f)) {
            throw new IllegalArgumentException("loose ore weighs at least its mass, got a factor of " + weightFactor);
        }
        if (!(crampPerMass >= 0f)) {
            throw new IllegalArgumentException("the cramp per mass is 0 or more, got " + crampPerMass);
        }
        if (!(maxCramp >= 0f && maxCramp < 1f)) {
            throw new IllegalArgumentException("the cramp takes a share from 0 up to 1, got " + maxCramp);
        }
        if (!(rattlePerOre >= 0f)) {
            throw new IllegalArgumentException("the rattle is 0 hull or more for each ore, got " + rattlePerOre);
        }
    }

    /** The share of the drill's speed and steering that loose ore of {@code mass} takes. */
    public float cramp(float mass) {
        return Math.min(maxCramp, mass * crampPerMass);
    }
}
