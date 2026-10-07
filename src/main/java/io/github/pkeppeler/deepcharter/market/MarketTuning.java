package io.github.pkeppeler.deepcharter.market;

/**
 * Tunables for the market feature, read as {@code MarketTuning.DEFAULT.thing()}. Add one
 * component per tunable and give it its value where {@code DEFAULT} is built.
 */
public record MarketTuning() {
	public static final MarketTuning DEFAULT = new MarketTuning();
}
