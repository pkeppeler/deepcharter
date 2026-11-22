package io.github.pkeppeler.deepcharter.outpost;

import java.util.List;

/**
 * Tunables for the ratings (#989, ADR 0076), read as {@code RatingTuning.DEFAULT.thing()}.
 *
 * @param layers  what each built layer asks of a block, layer 1 first: index 0 is layer 1. The surface asks nothing, and a depth with no
 *                entry has no rated material yet. A test pins its length to the layers the campaign world builds, so a new layer cannot
 *                ship without ratings
 * @param maxLevel the highest level a block tag can give: tags {@code rating/<axis>/1} up to this one are read
 */
public record RatingTuning(List<Ratings> layers, int maxLevel) {
    /**
     * (A) Layer 1 asks level 1 of everything, and layer 2 asks level 2 of heat and quake and level 1 of pressure and flooding: the Old
     * Workings are deeper and have collapsed before. Later layers follow their themes (SPEC "Story layers"), each when it is built.
     */
    public static final RatingTuning DEFAULT = new RatingTuning(List.of(Ratings.all(1), new Ratings(2, 1, 1, 2)), 5);

    public RatingTuning {
        layers = List.copyOf(layers);
        if (maxLevel < 1) {
            throw new IllegalArgumentException("maxLevel must be at least 1, got " + maxLevel);
        }
        for (Ratings ratings : layers) {
            for (RatingAxis axis : RatingAxis.values()) {
                if (ratings.of(axis) > maxLevel) {
                    throw new IllegalArgumentException("a layer asks " + axis + " " + ratings.of(axis)
                            + ", above the highest tag level " + maxLevel);
                }
            }
        }
        for (int i = 1; i < layers.size(); i++) {
            for (RatingAxis axis : RatingAxis.values()) {
                if (layers.get(i).of(axis) < layers.get(i - 1).of(axis)) {
                    throw new IllegalArgumentException(
                            "depth never asks less: layer " + (i + 1) + " asks less " + axis + " than layer " + i);
                }
            }
        }
    }
}
