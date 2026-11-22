package io.github.pkeppeler.deepcharter.repair;

/**
 * Tunables for the bolt driver (#987), read as {@code BoltDriverTuning.DEFAULT.thing()}. What a roof bolt holds is {@code CaveInTuning}'s.
 *
 * @param range             blocks a fired bolt flies before it drops; it stops at the first block or creature in its way
 * @param damage            health a bolt takes from a creature; the Retained have 12, so three bolts end one
 * @param fireCooldownTicks ticks before the driver fires again
 * @param setCooldownTicks  ticks before the driver does anything after it sets a roof bolt
 * @param packSize          bolts in one purchase at the repair station, whose price is for the pack
 * @param boltSpacing       a roof bolt is refused within this many blocks of another: it would hold nothing new
 */
public record BoltDriverTuning(
        double range, float damage, int fireCooldownTicks, int setCooldownTicks, int packSize, double boltSpacing) {
    public static final BoltDriverTuning DEFAULT = new BoltDriverTuning(24.0, 5f, 12, 20, 8, 2.0);

    public BoltDriverTuning {
        if (range <= 0
                || damage <= 0
                || fireCooldownTicks < 0
                || setCooldownTicks < 0
                || packSize < 1
                || boltSpacing < 0) {
            throw new IllegalArgumentException("bolt driver tuning out of range: " + this);
        }
    }
}
