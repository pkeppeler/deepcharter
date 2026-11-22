package io.github.pkeppeler.deepcharter.repair;

/**
 * Tunables for the Company recovery of a stranded pod (#963), read as {@code RecoveryTuning.DEFAULT.thing()}.
 *
 * @param delayTicks  ticks a pilot waits in the dark pod before the winch crew hauls it home (a minute: time for a friend with a fuel item to arrive, and long enough to hurt)
 * @param fee         dollars the Company charges for the haul, taken from the owner charter's account up to what it holds (the account never goes negative)
 * @param scrapeHull  hull points the haul costs when the account could pay none of the fee; the unpaid share of the fee is the share of this lost
 * @param splashLitres litres the crew leaves in the pod, so the pilot can drive from the pad to the pump
 */
public record RecoveryTuning(int delayTicks, long fee, float scrapeHull, float splashLitres) {
    /**
     * (A) A minute of waiting, and $50: a full tank is $10 at the pump, so a refuel is always cheaper, and $50 is about two fifths of what a stock layer 1 run nets
     * ($124, {@code EarlyRunModel}). A pilot who cannot pay loses up to a quarter of the hull instead, which the repair station mends at $1 a point.
     */
    public static final RecoveryTuning DEFAULT = new RecoveryTuning(1200, 50L, 25f, 1f);

    public RecoveryTuning {
        if (delayTicks < 1 || fee < 1 || !(scrapeHull >= 0f) || !(splashLitres > 0f)) {
            throw new IllegalArgumentException(
                    "recovery needs a delay and a fee of 1 or more, a scrape of 0 or more and some litres, got "
                            + delayTicks + ", " + fee + ", " + scrapeHull + ", " + splashLitres);
        }
    }
}
