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
 * @param seamLeadPerRow    blocks of lead before a floor per row of the world below (#1159): the lead of a 2048-tall world is 656 blocks
 * @param seamFloorMargin   blocks above a floor inside which a vehicle always streams the area below, however slowly it moves
 * @param seamSpeedWindow   ticks over which a vehicle's descent speed is read
 * @param seamDesignFallSpeed blocks per tick of the fastest fall the lead is sized for (terminal speed, 78 blocks a second)
 * @param seamRadius        chunks out from the centre that the arrival area holds
 * @param seamRefreshTicks  ticks between two refreshes of the ticket under a vehicle that stays in the zone
 * @param seamTicketTimeout ticks the arrival ticket outlasts its last refresh
 * @param seamRecentreChunks chunks (Chebyshev) a vehicle must be from the arrival area's centre before the area moves to it: one area per vehicle
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
        float grainSkateHeatPerSecond,
        double seamLeadPerRow,
        int seamFloorMargin,
        int seamSpeedWindow,
        double seamDesignFallSpeed,
        int seamRadius,
        int seamRefreshTicks,
        int seamTicketTimeout,
        int seamRecentreChunks) {
    public static final LayerTuning DEFAULT = new LayerTuning(
            63, 3.28, 3, 2, 4, 384, 24, 56, 10f, 15, 100, 5f, 4f, 4f, 0.32, 160, 10, 3.9, 4, 20, 100, 2);

    public LayerTuning {
        if (seamRefreshTicks < 1 || seamRefreshTicks >= seamTicketTimeout) {
            throw new IllegalArgumentException("seamRefreshTicks must be at least 1 and under seamTicketTimeout "
                    + seamTicketTimeout + ", or the ticket lapses between refreshes; got " + seamRefreshTicks);
        }
        if (seamRecentreChunks < 1) {
            throw new IllegalArgumentException("seamRecentreChunks must be at least 1, got " + seamRecentreChunks);
        }
        if (seamSpeedWindow < 1) {
            throw new IllegalArgumentException("seamSpeedWindow must be at least 1, got " + seamSpeedWindow);
        }
        if (grainPulseTicks < 2) {
            throw new IllegalArgumentException("grainPulseTicks must be at least 2, got " + grainPulseTicks);
        }
        if (structureSpacing <= 0 || structureSpacing % 16 != 0) {
            throw new IllegalArgumentException(
                    "structureSpacing must be a positive multiple of 16, got " + structureSpacing);
        }
        // A site keeps its margin from its cell's edge so its shell lies in the cell: the cell needs room for it.
        int widest = Arrays.stream(StructureKind.values())
                .mapToInt(StructureKind::margin)
                .max()
                .orElseThrow();
        if (structureSpacing <= 2 * widest) {
            throw new IllegalArgumentException(
                    "structureSpacing " + structureSpacing + " leaves no room for a structure with margin " + widest);
        }
    }
}
