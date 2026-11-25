package io.github.pkeppeler.deepcharter.transmission;

/**
 * Tunables for the transmission feature, read as {@code TransmissionTuning.DEFAULT.thing()}.
 *
 * @param bonusB1 the smallest Personnel bonus, in dollars, credited once to the charter that fires a transmission of its tier. Act 1
 *                pays several B1 and B2 bonuses before the Prospector, so the tiers are kept small: together about the price of
 *                restoring the Prospector, not a fortune beside the Mole's
 * @param bonusB2 the second tier
 * @param bonusB3 the third tier
 * @param zonePollTicks server ticks between checks of which zone each charter member stands in
 */
public record TransmissionTuning(long bonusB1, long bonusB2, long bonusB3, int zonePollTicks) {
    public static final TransmissionTuning DEFAULT = new TransmissionTuning(100, 250, 500, 20);

    public TransmissionTuning {
        if (bonusB1 <= 0 || bonusB2 <= 0 || bonusB3 <= 0) {
            throw new IllegalArgumentException(
                    "bonuses must be positive, got " + bonusB1 + ", " + bonusB2 + ", " + bonusB3);
        }
        if (zonePollTicks < 1) {
            throw new IllegalArgumentException("zonePollTicks must be at least 1, got " + zonePollTicks);
        }
    }
}
