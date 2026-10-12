package io.github.pkeppeler.deepcharter.pod;

/**
 * Tunables for the heat shield part (#482), read as {@code PodHeatShieldTuning.DEFAULT.thing()}. The price is {@code UpgradeTuning}'s.
 *
 * @param hullPerBrick lava hull one slag brick absorbs before it is spent, above 0 (after the radiator has cut the lava's rate)
 */
public record PodHeatShieldTuning(float hullPerBrick) {
	/** (A) A brick of the rack, then of the seated pilot's pack, soaks 4 hull of lava: a second of lava is 10 hull, so a brick buys 0.4 s and a full rack of 24 doubles a stock Mole's 10 s in lava. */
	public static final PodHeatShieldTuning DEFAULT = new PodHeatShieldTuning(4f);

	public PodHeatShieldTuning {
		if (!(hullPerBrick > 0f)) {
			throw new IllegalArgumentException("a brick absorbs hull above 0, got " + hullPerBrick);
		}
	}
}
