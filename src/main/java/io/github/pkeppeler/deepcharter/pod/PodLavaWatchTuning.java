package io.github.pkeppeler.deepcharter.pod;

/**
 * Tunables for the lava watch (#454), read as {@code PodLavaWatchTuning.DEFAULT.thing()}. Every pod with a pilot warns of flowing lava that
 * is joined to its box by open cells, whatever its parts. The warning is for lava that comes nearer, so that a lavafall that stays where it is does not
 * keep the line and the lamp on.
 *
 * @param reach          how many blocks of open cells from the pod's box the watch follows; lava joined by a path of this many cells or fewer is seen, one more is not
 * @param scanEveryTicks server ticks between two looks, so a pod pays for one look in this many ticks
 * @param nearBand       lava this many blocks off or nearer warns even when it stays where it is
 * @param quietScans     looks in a row with the lava no nearer, after which lava beyond the near band stops warning until it comes nearer again
 */
public record PodLavaWatchTuning(int reach, int scanEveryTicks, int nearBand, int quietScans) {
    /**
     * (A) The watch follows 12 blocks of open cells and looks every 10 ticks. Lava flows a block in about 1.5 seconds (30 ticks), so the warning
     * comes seconds before the lava; the liner's view is 3 beside and 4 over the box, and the watch sees farther without lining a cell.
     * Lava within 6 blocks always warns. Farther lava warns while it comes nearer, and falls quiet after 6 looks (3 seconds, two flow steps) with no change.
     */
    public static final PodLavaWatchTuning DEFAULT = new PodLavaWatchTuning(12, 10, 6, 6);

    public PodLavaWatchTuning {
        if (reach < 1 || scanEveryTicks < 1 || quietScans < 1) {
            throw new IllegalArgumentException(
                    "the lava watch follows 1 block or more, looks every 1 tick or more and waits 1 look or more, got "
                            + reach + ", " + scanEveryTicks + " and " + quietScans);
        }
        if (nearBand < 0 || nearBand > reach) {
            throw new IllegalArgumentException("the near band is 0 up to the reach of " + reach + ", got " + nearBand);
        }
    }
}
