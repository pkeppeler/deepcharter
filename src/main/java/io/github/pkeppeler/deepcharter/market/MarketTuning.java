package io.github.pkeppeler.deepcharter.market;

/**
 * Tunables for the market feature, read as {@code MarketTuning.DEFAULT.thing()}.
 *
 * @param processorRadius blocks from the middle of an ore processor within which a pod counts as parked at it: its cargo is
 *                        sold by "Sell All" there
 */
public record MarketTuning(double processorRadius) {
	public static final MarketTuning DEFAULT = new MarketTuning(10.0);
}
