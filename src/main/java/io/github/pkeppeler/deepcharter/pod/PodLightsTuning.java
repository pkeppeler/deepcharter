package io.github.pkeppeler.deepcharter.pod;

/**
 * Tunables for {@link PodLights}, read as {@code PodLightsTuning.DEFAULT.thing()}. How bright a lights part is comes from
 * {@code UpgradeTuning}, because it is the part's number.
 *
 * @param sweepIntervalTicks how often each dimension looks for light blocks that no pod holds any more
 */
public record PodLightsTuning(int sweepIntervalTicks) {
	public static final PodLightsTuning DEFAULT = new PodLightsTuning(20);

	public PodLightsTuning {
		if (sweepIntervalTicks < 1) {
			throw new IllegalArgumentException("the light sweep interval must be at least 1 tick, got " + sweepIntervalTicks);
		}
	}
}
