package io.github.pkeppeler.deepcharter.layer;

import java.util.Arrays;

/**
 * Tunables for the layer feature. Add one component per tunable and give it its value in
 * {@link #DEFAULT}; read it as {@code LayerTuning.DEFAULT.thing()}.
 *
 * @param seaLevel       the Y of depth 0 on the surface
 * @param feetPerBlock   the altimeter's feet per block
 * @param crustThickness blocks of breach crust at the floor of each layer; the layer JSONs stack this many
 * @param pocketRadius   a crossing carves a pocket this many blocks out from the arrival column on each side
 * @param pocketHeight   blocks of air in that pocket; an entity arrives at its bottom, so this is also how far
 *                       from the crossing line it starts
 * @param structureSpacing  each kind of structure has one site in every square of this many blocks (a multiple of 16)
 * @param structureFloorMargin  structures keep this many blocks above the floor of the layer, clear of the crust and the rock floor
 * @param structureCeilingMargin  structures keep this many blocks below the top of the layer, clear of the rock roof
 * @param lavaHullPerSecond hull points a pod loses each second while it is in or touching lava, before a radiator
 * @param lavaCueTicks      ticks between the hiss that tells the crew the hull is burning
 * @param grainPulseTicks   ticks between two pulses of the grained crust (#746); 100 is 5 s, inside the 4 to 6 s of the design (#185)
 * @param grainSqueezeHull  hull a pod that is neither moving nor drilling loses to each pulse while it touches the grained crust
 * @param grainSqueezeDamage health a player on foot loses to each pulse while touching the grained crust
 * @param grainSkateHeatPerSecond hull heat a pod takes each second its drill skates sideways across the grain, before a radiator
 */
public record LayerTuning(
        int seaLevel,
        double feetPerBlock,
        int crustThickness,
        int pocketRadius,
        int pocketHeight,
        int structureSpacing,
        int structureFloorMargin,
        int structureCeilingMargin,
        float lavaHullPerSecond,
        int lavaCueTicks,
        int grainPulseTicks,
        float grainSqueezeHull,
        float grainSqueezeDamage,
        float grainSkateHeatPerSecond) {
    public static final LayerTuning DEFAULT = new LayerTuning(63, 3.28, 3, 2, 4, 384, 24, 56, 10f, 15, 100, 5f, 4f, 4f);

    public LayerTuning {
        if (grainPulseTicks < 2) {
            throw new IllegalArgumentException("grainPulseTicks must be at least 2, got " + grainPulseTicks);
        }
        if (structureSpacing <= 0 || structureSpacing % 16 != 0) {
            throw new IllegalArgumentException(
                    "structureSpacing must be a positive multiple of 16, got " + structureSpacing);
        }
        // A site keeps reach + 1 blocks from its cell's edge, so that its shell lies in the cell too; the cell needs
        // room left to place it.
        int widest = Arrays.stream(StructureKind.values())
                .mapToInt(StructureKind::reach)
                .max()
                .orElseThrow();
        if (structureSpacing <= 2 * (widest + 1)) {
            throw new IllegalArgumentException(
                    "structureSpacing " + structureSpacing + " leaves no room for a structure of reach " + widest);
        }
    }
}
