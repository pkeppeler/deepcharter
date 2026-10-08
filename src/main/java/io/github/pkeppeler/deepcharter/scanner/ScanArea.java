package io.github.pkeppeler.deepcharter.scanner;

/**
 * The blocks a scanner slice covers around the pod's feet.
 *
 * @param halfWidth blocks each side of the pod, along its facing
 * @param up blocks above the pod's feet
 * @param down blocks below the pod's feet
 */
public record ScanArea(int halfWidth, int up, int down) {
	public int columns() {
		return 2 * halfWidth + 1;
	}

	public int rows() {
		return up + down + 1;
	}

	public boolean contains(int ahead, int above) {
		return Math.abs(ahead) <= halfWidth && above <= up && above >= -down;
	}
}
