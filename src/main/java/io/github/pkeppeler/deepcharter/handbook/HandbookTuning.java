package io.github.pkeppeler.deepcharter.handbook;

/**
 * Tunables for the handbook feature, read as {@code HandbookTuning.DEFAULT.thing()}.
 *
 * @param progressPollTicks how often each online player's directive advancements are checked, in ticks. A vanilla trigger
 *                          completes a directive up to this long after it happens; {@code Directives.fire} does not wait
 */
public record HandbookTuning(int progressPollTicks) {
	public static final HandbookTuning DEFAULT = new HandbookTuning(10);

	public HandbookTuning {
		if (progressPollTicks < 1) {
			throw new IllegalArgumentException("progressPollTicks must be at least 1, got " + progressPollTicks);
		}
	}
}
