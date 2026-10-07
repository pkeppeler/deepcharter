package io.github.pkeppeler.deepcharter.transmission;

/**
 * Tunables for the transmission feature, read as {@code TransmissionTuning.DEFAULT.thing()}.
 *
 * @param bonusB1 the first employer bonus, in dollars, credited once to the charter that fires its transmission
 * @param bonusB2 the second employer bonus
 * @param bonusB3 the third employer bonus
 * @param zonePollTicks server ticks between checks of which zone each charter member stands in
 */
public record TransmissionTuning(long bonusB1, long bonusB2, long bonusB3, int zonePollTicks) {
	public static final TransmissionTuning DEFAULT = new TransmissionTuning(1_000, 3_000, 10_000, 20);

	public TransmissionTuning {
		if (bonusB1 <= 0 || bonusB2 <= 0 || bonusB3 <= 0) {
			throw new IllegalArgumentException("bonuses must be positive, got " + bonusB1 + ", " + bonusB2 + ", " + bonusB3);
		}
		if (zonePollTicks < 1) {
			throw new IllegalArgumentException("zonePollTicks must be at least 1, got " + zonePollTicks);
		}
	}
}
