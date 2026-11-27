package io.github.pkeppeler.deepcharter.surface;

/**
 * Tunables for layer 3's giant fungi (#1202), read as {@code GiantFungusTuning.DEFAULT.thing()}. The real light a giant
 * brings is the knob its generation cost turns on (ADR 0086): the glow drawn on its rims, gills and tips is a model's,
 * and costs nothing to generate.
 *
 * @param parasolGlowcaps  the fewest glowcaps round a parasol's foot; it grows this many to two more
 * @param spireGlowcaps    the fewest glowcaps at a spire cluster's mound; it grows this many to two more
 * @param mostGillLamps    the most gill lamps under a parasol's cap; it grows 0 to this many
 * @param gillLampLight    the block light of a gill lamp
 * @param tipLight         the block light of the lit tip of a spire cluster's tallest spire
 */
public record GiantFungusTuning(
        int parasolGlowcaps, int spireGlowcaps, int mostGillLamps, int gillLampLight, int tipLight) {
    public static final GiantFungusTuning DEFAULT = new GiantFungusTuning(3, 1, 2, 5, 5);

    public GiantFungusTuning {
        if (gillLampLight < 0 || gillLampLight > 15 || tipLight < 0 || tipLight > 15) {
            throw new IllegalArgumentException(
                    "a block light is 0 to 15, got gill lamp " + gillLampLight + " and tip " + tipLight);
        }
        if (parasolGlowcaps < 0 || spireGlowcaps < 0 || mostGillLamps < 0) {
            throw new IllegalArgumentException("a count of lights is at least 0");
        }
    }
}
