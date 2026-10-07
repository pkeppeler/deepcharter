package io.github.pkeppeler.deepcharter.upgrade;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Tunables for the upgrade feature, read as {@code UpgradeTuning.DEFAULT.thing()}.
 *
 * @param tracks     for each {@link ComponentTrack}, what each tier is worth and costs
 * @param tierCaps   the best part tier each chassis takes, by chassis id (SPEC section 7)
 * @param parkedRadius how many blocks from a terminal's centre a pod still counts as parked at it
 */
public record UpgradeTuning(Map<ComponentTrack, Tiers> tracks, Map<String, Integer> tierCaps, double parkedRadius) {
	public static final UpgradeTuning DEFAULT = new UpgradeTuning(defaultTracks(), Map.of("mole", 2), 8.0);

	/**
	 * One track's table, with one entry for each tier from 0 (stock) up. {@code values} are the original's reference
	 * numbers where it has them (drill speed, hull points, engine power, tank litres, radiator damage multiplier, bay
	 * slots) and invented ones for the scanner (a multiplier of the stock scanner area, 0 for none) and the lights (a
	 * light level, 0 for none). {@code prices} are the original's dollars, to be scaled when the economy is tuned.
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
		tierCaps = Map.copyOf(tierCaps);
		if (!(parkedRadius > 0.0)) {
			throw new IllegalArgumentException("the radius a pod is parked within must be above 0, got " + parkedRadius);
		}
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

	/** The best part tier the chassis takes. A chassis with no entry is a bug, so it throws. */
	public int tierCap(String chassisId) {
		Integer cap = tierCaps.get(chassisId);
		if (cap == null) {
			throw new IllegalArgumentException("no component tier cap for chassis " + chassisId);
		}
		return cap;
	}

	private static int requireTier(ComponentTrack track, int tier) {
		if (tier < 0 || tier > track.maxTier()) {
			throw new IllegalArgumentException("track " + track.id() + " has tiers 0 to " + track.maxTier() + ", got " + tier);
		}
		return tier;
	}

	private static Map<ComponentTrack, Tiers> defaultTracks() {
		Map<ComponentTrack, Tiers> tracks = new EnumMap<>(ComponentTrack.class);
		List<Long> standardPrices = List.of(0L, 750L, 2000L, 5000L, 20000L, 100000L, 500000L);
		tracks.put(ComponentTrack.DRILL, new Tiers(List.of(2f, 2.8f, 4f, 5f, 7f, 9.5f, 12f), standardPrices));
		tracks.put(ComponentTrack.HULL, new Tiers(List.of(10f, 17f, 30f, 50f, 80f, 120f, 180f), standardPrices));
		tracks.put(ComponentTrack.ENGINE, new Tiers(List.of(150f, 160f, 170f, 180f, 190f, 200f, 210f), standardPrices));
		tracks.put(ComponentTrack.FUEL_TANK, new Tiers(List.of(10f, 15f, 25f, 40f, 60f, 100f, 150f), standardPrices));
		tracks.put(ComponentTrack.RADIATOR, new Tiers(List.of(1.0f, 0.9f, 0.75f, 0.6f, 0.4f, 0.2f),
				List.of(0L, 2000L, 5000L, 20000L, 100000L, 500000L)));
		tracks.put(ComponentTrack.CARGO_BAY, new Tiers(List.of(7f, 15f, 25f, 40f, 70f, 120f),
				List.of(0L, 750L, 2000L, 5000L, 20000L, 100000L)));
		// Invented: the original has no scanner and no lights.
		tracks.put(ComponentTrack.SCANNER, new Tiers(List.of(0f, 0.5f, 0.75f, 1f, 1.5f), List.of(0L, 1500L, 4000L, 12000L, 40000L)));
		tracks.put(ComponentTrack.LIGHTS, new Tiers(List.of(0f, 6f, 9f, 12f, 15f), List.of(0L, 500L, 1500L, 5000L, 15000L)));
		return tracks;
	}
}
