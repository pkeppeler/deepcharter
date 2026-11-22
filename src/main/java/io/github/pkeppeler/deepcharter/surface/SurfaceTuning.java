package io.github.pkeppeler.deepcharter.surface;

/**
 * Tunables for the surface feature, read as {@code SurfaceTuning.DEFAULT.thing()}.
 *
 * @param greatStalkChance  the chance that a fungal stalk the world grows is a great stalk, when the ground and the air above it have room
 * @param ordinaryMaxHeight the most blocks in an ordinary stalk column
 * @param collapseDelayTicks the sag between a stalk breaking and the first layer of what is above it bursting
 * @param collapseStepTicks the ticks between one layer of a collapsing stalk bursting and the next
 * @param scatterSpeed      how fast a burst block's drops fly out, in blocks a tick
 */
public record SurfaceTuning(
        double greatStalkChance,
        int ordinaryMaxHeight,
        int collapseDelayTicks,
        int collapseStepTicks,
        double scatterSpeed) {
    public static final SurfaceTuning DEFAULT = new SurfaceTuning(0.02, 6, 8, 3, 0.1);
}
