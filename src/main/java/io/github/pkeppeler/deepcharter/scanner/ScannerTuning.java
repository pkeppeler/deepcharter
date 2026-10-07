package io.github.pkeppeler.deepcharter.scanner;

/**
 * Tunables for the scanner feature, read as {@code ScannerTuning.DEFAULT.thing()}.
 *
 * <p>Colours are opaque ARGB. The HUD draws them with fill(), which ignores world light.
 *
 * @param halfWidth blocks scanned each side of the pod, along its facing
 * @param up blocks scanned above the pod's feet
 * @param down blocks scanned below the pod's feet
 * @param rescanTicks client ticks between rescans
 * @param cellPixels GUI pixels per cell
 * @param margin GUI pixels between the panel and the screen edge
 */
public record ScannerTuning(
		int halfWidth,
		int up,
		int down,
		int rescanTicks,
		int cellPixels,
		int margin,
		int airColor,
		int rockColor,
		int oreColor,
		int goldOreColor,
		int podColor,
		int frameColor) {
	public static final ScannerTuning DEFAULT = new ScannerTuning(
			24, 8, 32, 5, 3, 4,
			0xFF101820, 0xFF5C5248, 0xFFE8E8F0, 0xFFFFD21E, 0xFF38F06E, 0xFF000000);

	public int columns() {
		return 2 * halfWidth + 1;
	}

	public int rows() {
		return up + down + 1;
	}
}
