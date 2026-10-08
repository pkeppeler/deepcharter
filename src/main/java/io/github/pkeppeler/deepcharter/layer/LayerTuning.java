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
 */
public record LayerTuning(int seaLevel, double feetPerBlock, int crustThickness, int pocketRadius, int pocketHeight,
		int structureSpacing, int structureFloorMargin, int structureCeilingMargin, float lavaHullPerSecond, int lavaCueTicks) {
	public static final LayerTuning DEFAULT = new LayerTuning(63, 3.28, 3, 2, 4, 384, 24, 56, 10f, 15);

	public LayerTuning {
		if (structureSpacing <= 0 || structureSpacing % 16 != 0) {
			throw new IllegalArgumentException("structureSpacing must be a positive multiple of 16, got " + structureSpacing);
		}
		// A site keeps reach + 1 blocks from its cell's edge, so that its shell lies in the cell too; the cell needs room left to place it.
		int widest = Arrays.stream(StructureKind.values()).mapToInt(StructureKind::reach).max().orElseThrow();
		if (structureSpacing <= 2 * (widest + 1)) {
			throw new IllegalArgumentException("structureSpacing " + structureSpacing + " leaves no room for a structure of reach " + widest);
		}
	}
}
