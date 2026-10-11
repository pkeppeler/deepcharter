package io.github.pkeppeler.deepcharter.pod;

/**
 * Tunables for the lava watch (#454), read as {@code PodLavaWatchTuning.DEFAULT.thing()}. Every pod with a pilot warns of flowing lava that
 * is joined to its box by open cells, whatever its parts.
 *
 * @param reach          how many blocks of open cells from the pod's box the watch follows; lava joined by a path of this many cells or fewer warns, one more does not
 * @param scanEveryTicks server ticks between two looks, so a pod pays for one look in this many ticks
 */
public record PodLavaWatchTuning(int reach, int scanEveryTicks) {
	/**
	 * (A) The watch follows 12 blocks of open cells and looks every 10 ticks. Lava flows a block in about 1.5 seconds (30 ticks), so the warning
	 * comes seconds before the lava; the liner's view is 3 beside and 4 over the box, and the watch sees farther without lining a cell.
	 */
	public static final PodLavaWatchTuning DEFAULT = new PodLavaWatchTuning(12, 10);

	public PodLavaWatchTuning {
		if (reach < 1 || scanEveryTicks < 1) {
			throw new IllegalArgumentException("the lava watch follows 1 block or more and looks every 1 tick or more, got " + reach + " and " + scanEveryTicks);
		}
	}
}
