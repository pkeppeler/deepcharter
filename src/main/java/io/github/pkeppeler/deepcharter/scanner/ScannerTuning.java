package io.github.pkeppeler.deepcharter.scanner;

import io.github.pkeppeler.deepcharter.upgrade.ComponentTrack;
import io.github.pkeppeler.deepcharter.upgrade.UpgradeTuning;

/**
 * Tunables for the scanner feature, read as {@code ScannerTuning.DEFAULT.thing()}.
 *
 * <p>How the minimap looks (its colours, cell size and margin) is the client UI theme's, {@code theme/scanner.json}.
 *
 * @param tierOneArea the area a tier 1 scanner covers (M1's range); higher tiers scale it by their scanner value over tier 1's
 * @param lavaTier the lowest scanner tier that marks lava (the thermal tier); below it lava reads as open space, like air
 * @param lavaSpread how many blocks either side of the slice's plane the thermal tier looks for lava: a bore is 2 blocks wide, so the plane alone misses most of the lava a pod touches (PR 287)
 * @param gasTier the lowest scanner tier that shows gas pockets; below it they read as the rock they look like
 * @param rescanTicks client ticks between rescans
 */
public record ScannerTuning(
		ScanArea tierOneArea,
		int lavaTier,
		int lavaSpread,
		int gasTier,
		int rescanTicks) {
	public static final ScannerTuning DEFAULT = new ScannerTuning(new ScanArea(24, 8, 32), 2, 2, 3, 5);

	/** The area a scanner of {@code tier} covers; tier 0 is no scanner and has none, so it throws. */
	public ScanArea area(int tier) {
		ComponentTrack track = ComponentTrack.SCANNER;
		if (tier < 1 || tier > track.maxTier()) {
			throw new IllegalArgumentException("a scanner has tiers 1 to " + track.maxTier() + ", got " + tier);
		}
		float scale = UpgradeTuning.DEFAULT.value(track, tier) / UpgradeTuning.DEFAULT.value(track, 1);
		return new ScanArea(Math.round(tierOneArea.halfWidth() * scale), Math.round(tierOneArea.up() * scale),
				Math.round(tierOneArea.down() * scale));
	}

	public boolean showsLava(int tier) {
		return tier >= lavaTier;
	}

	public boolean showsGas(int tier) {
		return tier >= gasTier;
	}
}
