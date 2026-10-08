package io.github.pkeppeler.deepcharter.market;

/**
 * Tunables for the market feature, read as {@code MarketTuning.DEFAULT.thing()}.
 *
 * @param foundersHandsReward dollars the charter that restores the Founder's hands is paid, at completion. It is what the ten Bronzium
 *                            would have fetched at the ore processor, so the story beat costs the charter nothing.
 */
public record MarketTuning(long foundersHandsReward) {
	public static final MarketTuning DEFAULT = new MarketTuning(600);
}
