package io.github.pkeppeler.deepcharter.market;

/**
 * Tunables for the market feature, read as {@code MarketTuning.DEFAULT.thing()}.
 *
 * @param foundersHandsReward dollars the charter that restores the Founder's hands is paid, at completion. It is what the ten Bronzium
 *                            would have fetched at the ore processor, so the story beat costs the charter nothing.
 * @param spoilPerCrate       spoil the ore processor takes to press one salvage crate (#470). A crate breaks into the loot of the yard's crates, so
 *                            this is the exchange rate between a bore's waste rock and the colony's wood and scrap.
 */
public record MarketTuning(long foundersHandsReward, int spoilPerCrate) {
    public static final MarketTuning DEFAULT = new MarketTuning(600, 24);

    public MarketTuning {
        if (spoilPerCrate < 1) {
            throw new IllegalArgumentException("a crate takes at least 1 spoil");
        }
    }
}
