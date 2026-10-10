package io.github.pkeppeler.deepcharter.pod;

/**
 * Tunables for the breach brace part and the crust warning (#378), read as {@code PodBraceTuning.DEFAULT.thing()}. The price is {@code UpgradeTuning}'s.
 *
 * @param hullPerOre    hull the brace patches for each ore it burns, above 0
 * @param patchTicks    ticks between two patches while the pod rests, 1 or more
 * @param reserveHull   the brace patches until the hull is this much over what the crust rows left would take, 0 or more
 * @param warnSlabs     the crust warning shows, and the brace works, from this many slabs above the crust, 0 or more
 * @param restTicks     the pod must have stood on the ground with no input from its pilot for this many ticks before the brace works, 0 or more
 */
public record PodBraceTuning(float hullPerOre, int patchTicks, float reserveHull, int warnSlabs, int restTicks) {
	/**
	 * (A) A patch burns the cheapest ore in the bay for 12 hull, once a second while the pod rests, until the hull is 4 over the crust's price. The
	 * warning, and so the brace, starts 16 slabs up: time to stop the drill and burn what it takes. The pod rests after 20 ticks on the ground with the pilot
	 * giving no input, which is longer than the pod takes to fall into the hole of a slab. The brace never burns an ore that costs more a hull than Hull Nanobots do.
	 */
	public static final PodBraceTuning DEFAULT = new PodBraceTuning(12f, 20, 4f, 16, 20);

	public PodBraceTuning {
		if (!(hullPerOre > 0f)) {
			throw new IllegalArgumentException("a patch repairs hull above 0, got " + hullPerOre);
		}
		if (patchTicks < 1) {
			throw new IllegalArgumentException("a patch takes 1 tick or more, got " + patchTicks);
		}
		if (!(reserveHull >= 0f)) {
			throw new IllegalArgumentException("the reserve is 0 hull or more, got " + reserveHull);
		}
		if (restTicks < 0) {
			throw new IllegalArgumentException("the rest is 0 ticks or more, got " + restTicks);
		}
		if (warnSlabs < 0) {
			throw new IllegalArgumentException("the warning reaches 0 slabs or more, got " + warnSlabs);
		}
	}
}
