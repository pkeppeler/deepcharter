package io.github.pkeppeler.deepcharter.pod;

/**
 * Tunables for the void below (#1387), read as {@code VoidBelowTuning.DEFAULT.thing()}.
 *
 * @param hollowRows  the drill sounds hollow while the row it would break through on is this many rows down or fewer,
 *                    the row under the pod's feet being 1; 1 or more
 * @param scanBlocks  the most blocks of open space read under a row; a deeper fall is priced as a fall of this many
 *                    blocks, 1 or more
 * @param hollowPitch the pitch the drill-down loop plays at while it sounds hollow, above 0
 */
public record VoidBelowTuning(int hollowRows, int scanBlocks, float hollowPitch) {
    public static final VoidBelowTuning DEFAULT = new VoidBelowTuning(2, 64, 1.4f);

    public VoidBelowTuning {
        if (hollowRows < 1) {
            throw new IllegalArgumentException("the hollow pitch starts 1 row or more ahead, got " + hollowRows);
        }
        if (scanBlocks < 1) {
            throw new IllegalArgumentException("the scan reads 1 block or more, got " + scanBlocks);
        }
        if (!(hollowPitch > 0f)) {
            throw new IllegalArgumentException("the hollow pitch is above 0, got " + hollowPitch);
        }
    }
}
