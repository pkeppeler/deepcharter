package io.github.pkeppeler.deepcharter.surface;

/**
 * Tunables for the surface feature, read as {@code SurfaceTuning.DEFAULT.thing()}.
 *
 * @param greatStalkChance  the chance that a fungal stalk the world grows is a great stalk, when the ground and the air above it have room
 * @param ordinaryMaxHeight the most blocks in an ordinary stalk column
 * @param scatterSpeed      how fast a burst block's drops fly out, in blocks a tick
 */
public record SurfaceTuning(double greatStalkChance, int ordinaryMaxHeight, double scatterSpeed) {
    public static final SurfaceTuning DEFAULT = new SurfaceTuning(0.02, 6, 0.1);
}
