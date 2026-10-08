package io.github.pkeppeler.deepcharter.scanner;

import io.github.pkeppeler.deepcharter.upgrade.ComponentTrack;
import io.github.pkeppeler.deepcharter.upgrade.UpgradeTuning;

/**
 * Tunables for the scanner feature, read as {@code ScannerTuning.DEFAULT.thing()}.
 *
 * <p>Colours are opaque ARGB. The HUD draws them with fill(), which ignores world light.
 *
 * @param tierOneArea the area a tier 1 scanner covers (M1's range); higher tiers scale it by their scanner value over tier 1's
 * @param gasTier the lowest scanner tier that shows gas pockets; below it they read as the rock they look like
 * @param rescanTicks client ticks between rescans
 * @param cellPixels GUI pixels per cell
 * @param margin GUI pixels between the panel and the screen edge
 */
public record ScannerTuning(
		ScanArea tierOneArea,
		int gasTier,
		int rescanTicks,
		int cellPixels,
		int margin,
		int airColor,
		int rockColor,
		int oreColor,
		int goldOreColor,
		int gasColor,
		int podColor,
		int frameColor) {
	public static final ScannerTuning DEFAULT = new ScannerTuning(
			new ScanArea(24, 8, 32), 3, 5, 3, 4,
			0xFF101820, 0xFF5C5248, 0xFFE8E8F0, 0xFFFFD21E, 0xFFE040E0, 0xFF38F06E, 0xFF000000);

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

	public boolean showsGas(int tier) {
		return tier >= gasTier;
	}
}
