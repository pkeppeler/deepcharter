package io.github.pkeppeler.deepcharter.upgrade;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Tunables for the upgrade feature, read as {@code UpgradeTuning.DEFAULT.thing()}.
 *
 * @param tracks     for each {@link ComponentTrack}, what each tier is worth and costs
 */
public record UpgradeTuning(Map<ComponentTrack, Tiers> tracks) {
	public static final UpgradeTuning DEFAULT = new UpgradeTuning(defaultTracks());

	/**
	 * One track's table, with one entry for each tier from 0 (stock) up. {@code values} are the original's reference
	 * numbers where it has them (drill speed, hull points, engine power, tank litres, radiator damage multiplier, bay
	 * slots) and invented ones for the scanner (a multiplier of the stock scanner area, 0 for none) and the lights (a
	 * light level, 0 for none). {@code prices} are dollars, scaled to what a run earns (see {@link #defaultTracks}).
	 */
	public record Tiers(List<Float> values, List<Long> prices) {
		public Tiers {
			values = List.copyOf(values);
			prices = List.copyOf(prices);
			if (values.size() != prices.size()) {
				throw new IllegalArgumentException("a track needs one price for each tier value, got " + values.size() + " values and " + prices.size() + " prices");
			}
		}
	}

	public UpgradeTuning {
		tracks = Map.copyOf(tracks);
		for (ComponentTrack track : ComponentTrack.values()) {
			Tiers tiers = tracks.get(track);
			if (tiers == null || tiers.values().size() != track.maxTier() + 1) {
				throw new IllegalArgumentException("track " + track.id() + " needs a table of " + (track.maxTier() + 1) + " tiers, tier 0 included");
			}
		}
	}

	/** The track's number at {@code tier}; tier 0 is the stock part. */
	public float value(ComponentTrack track, int tier) {
		return tracks.get(track).values().get(requireTier(track, tier));
	}

	/** The dollars a part of this tier costs in the original; the stock part is free. */
	public long price(ComponentTrack track, int tier) {
		return tracks.get(track).prices().get(requireTier(track, tier));
	}

	/** How much the track's number at {@code tier} is worth against the stock part's: 1 for tier 0, 1.4 for a part 40% better. */
	public float ratio(ComponentTrack track, int tier) {
		float stock = value(track, 0);
		if (!(stock > 0f)) {
			throw new IllegalArgumentException("track " + track.id() + " has no stock part to compare with");
		}
		return value(track, tier) / stock;
	}

	private static int requireTier(ComponentTrack track, int tier) {
		if (tier < 0 || tier > track.maxTier()) {
			throw new IllegalArgumentException("track " + track.id() + " has tiers 0 to " + track.maxTier() + ", got " + tier);
		}
		return tier;
	}

	/**
	 * The prices are the original's dollars times 1/4 (SPEC section 4: "scaled to fit"). A stock Mole's early run in layer 1
	 * nets about $115 and one in layer 2, with tier 2 parts, about $400 (EconomyAffordabilityTest), so a tier 1 part is two runs
	 * away and each tier up costs about the 2.5 to 5 times the one below that the original's ladder has. The tiers beyond
	 * what layers 1 and 2 pay for keep that ladder, and are for the M3 play-test to tune.
	 */
	private static Map<ComponentTrack, Tiers> defaultTracks() {
		Map<ComponentTrack, Tiers> tracks = new EnumMap<>(ComponentTrack.class);
		List<Long> standardPrices = List.of(0L, 200L, 500L, 1250L, 5000L, 25000L, 125000L);
		tracks.put(ComponentTrack.DRILL, new Tiers(List.of(2f, 2.8f, 4f, 5f, 7f, 9.5f, 12f), standardPrices));
		tracks.put(ComponentTrack.HULL, new Tiers(List.of(10f, 17f, 30f, 50f, 80f, 120f, 180f), standardPrices));
		tracks.put(ComponentTrack.ENGINE, new Tiers(List.of(150f, 160f, 170f, 180f, 190f, 200f, 210f), standardPrices));
		tracks.put(ComponentTrack.FUEL_TANK, new Tiers(List.of(10f, 15f, 25f, 40f, 60f, 100f, 150f), standardPrices));
		tracks.put(ComponentTrack.RADIATOR, new Tiers(List.of(1.0f, 0.9f, 0.75f, 0.6f, 0.4f, 0.2f),
				List.of(0L, 500L, 1250L, 5000L, 25000L, 125000L)));
		tracks.put(ComponentTrack.CARGO_BAY, new Tiers(List.of(7f, 15f, 25f, 40f, 70f, 120f),
				List.of(0L, 200L, 500L, 1250L, 5000L, 25000L)));
		// Invented: the original has no scanner and no lights. The scanner is bought in the onboarding (handbook chapter 6), so
		// it takes the standard ladder. Tier 2, $500, is the thermal tier that marks lava (#300): priced with the other tier 2 parts, it
		// takes two runs in layer 2 at most (EconomyAffordabilityTest), and a Mole can fit it. Lights are the cheaper part, a quarter of the first guess.
		tracks.put(ComponentTrack.SCANNER, new Tiers(List.of(0f, 0.5f, 0.75f, 1f, 1.5f), List.of(0L, 200L, 500L, 1250L, 5000L)));
		tracks.put(ComponentTrack.LIGHTS, new Tiers(List.of(0f, 6f, 9f, 12f, 15f), List.of(0L, 125L, 375L, 1250L, 3750L)));
		// Invented (#313): the original keeps no stone. The hopper is SPEC section 7's "keep stone" upgrade, as a bay for spoil. One tier, 1 for
		// "fitted"; $100 is one early run of a stock Mole in layer 1 (EconomyAffordabilityTest), so it is bought before Deep Claim, where lava starts.
		tracks.put(ComponentTrack.SPOIL_HOPPER, new Tiers(List.of(0f, 1f), List.of(0L, 100L)));
		// Invented (#339): the liner has no stock part. Its value is the tier number; what each tier does is in PodLinerTuning. Both tiers fit a Mole (cap 2).
		// Tier 1 is priced with the other tier 2 parts, tier 2 a little under the tier 3 parts: a layer 2 buy each (EconomyAffordabilityTest).
		tracks.put(ComponentTrack.LINER, new Tiers(List.of(0f, 1f, 2f), List.of(0L, 500L, 1000L)));
		// Invented (#373): the seep sounder has no stock part. Its value is the tier number; what each tier does is in PodSounderTuning. Both tiers fit a Mole (cap 2).
		// Tier 1 is $400, two layer 2 runs, and tier 2 is $1,000, as the liner's tier 2 (EconomyAffordabilityTest).
		tracks.put(ComponentTrack.SOUNDER, new Tiers(List.of(0f, 1f, 2f), List.of(0L, 400L, 1000L)));
		return tracks;
	}
}
