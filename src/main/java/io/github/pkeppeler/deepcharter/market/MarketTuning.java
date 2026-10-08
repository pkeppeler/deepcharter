package io.github.pkeppeler.deepcharter.market;

/**
 * Tunables for the market feature, read as {@code MarketTuning.DEFAULT.thing()}.
 *
 * @param foundersHandsReward dollars the charter that restores the Founder's hands is paid, at completion. It is what the ten Bronzium
 *                            would have fetched at the ore processor, so the story beat costs the charter nothing.
 * @param moraleInitiativeReward dollars paid for each round of the Morale Initiative. A placeholder, 25% over what its ten Silverium fetch
 *                            (1000) at the ore processor: the order beats selling, but only by the cost of the trip.
 */
public record MarketTuning(long foundersHandsReward, long moraleInitiativeReward) {
	public static final MarketTuning DEFAULT = new MarketTuning(600, 1250);
}
