package io.github.pkeppeler.deepcharter.pod;

import java.util.List;

/**
 * Tunables for the seep sounder part (#373), read as {@code PodSounderTuning.DEFAULT.thing()}. A sounder tells the pilot where a gas pocket
 * is before the drill opens it. Its prices are {@code UpgradeTuning}'s.
 *
 * @param tiers             one entry for each part tier from 1
 * @param hissTicksPerSlab  the hiss repeats every this many ticks for each slab between the pod and the nearest pocket below it, so it quickens as the drill nears
 */
public record PodSounderTuning(List<Tier> tiers, int hissTicksPerSlab) {
	/**
	 * What one tier of the sounder does.
	 *
	 * @param slabsBelow         slabs under the pod's feet in which it marks a pocket of the pod's footprint
	 * @param sideReach          blocks to each side of the footprint in which it marks a pocket a sidestep would bore or land on; 0 for none
	 * @param drillSpeedPenalty  share of the drill's speed the sounder takes, 0 up to but not including 1
	 * @param bleedPauseTicks    ticks the drill waits before it bores a slab with a pocket in it, while the pocket bleeds off; 0 for a sounder that does not bleed
	 * @param bleedHullShare     the least the blast of a pocket the drill bled can cost the pod, as a share of its most hull, above 0 up to and including 1 (1 for no bleed)
	 * @param bleedBlastShare    the share of the blast a bled pocket costs when that is more than the hull share, above 0 up to and including 1 (1 for no bleed), so the cost grows with depth
	 */
	public record Tier(int slabsBelow, int sideReach, float drillSpeedPenalty, int bleedPauseTicks, float bleedHullShare, float bleedBlastShare) {
		public Tier {
			if (slabsBelow < 1 || sideReach < 0 || bleedPauseTicks < 0) {
				throw new IllegalArgumentException("a sounder tier hears 1 slab or more below, 0 blocks or more aside, and bleeds after 0 ticks or more");
			}
			if (!(drillSpeedPenalty >= 0f && drillSpeedPenalty < 1f)) {
				throw new IllegalArgumentException("the drill speed penalty is a share from 0 up to 1, got " + drillSpeedPenalty);
			}
			if (!(bleedHullShare > 0f && bleedHullShare <= 1f)) {
				throw new IllegalArgumentException("the bleed hull share is above 0 and at most 1, got " + bleedHullShare);
			}
			if (!(bleedBlastShare > 0f && bleedBlastShare <= 1f)) {
				throw new IllegalArgumentException("the bleed blast share is above 0 and at most 1, got " + bleedBlastShare);
			}
		}

		/** The ticks a hardness takes with this sounder fitted, from the ticks without it. */
		public float slowedDrill(float ticksPerHardness) {
			return ticksPerHardness / (1 - drillSpeedPenalty);
		}
	}

	/**
	 * (A) Tier 1 hears the footprint's pockets 2 slabs down and takes 5% of the drill. Tier 2 hears 4 slabs down and 2 blocks to each side, bleeds a
	 * pocket in 3 seconds, and takes 10%. A bled blast costs the larger of 35% of the pod's hull and half the blast, and never more than the blast. A better sounder is the slower drill.
	 */
	public static final PodSounderTuning DEFAULT = new PodSounderTuning(List.of(
			new Tier(2, 0, 0.05f, 0, 1f, 1f),
			new Tier(4, 2, 0.10f, 60, 0.45f, 0.5f)), 12);

	public PodSounderTuning {
		tiers = List.copyOf(tiers);
		if (hissTicksPerSlab < 1) {
			throw new IllegalArgumentException("the hiss repeats every 1 tick per slab or more, got " + hissTicksPerSlab);
		}
	}

	/** The tier's numbers; {@code tier} is 1 or more and no more than the part's best. */
	public Tier tier(int tier) {
		if (tier < 1 || tier > tiers.size()) {
			throw new IllegalArgumentException("the sounder has tiers 1 to " + tiers.size() + ", got " + tier);
		}
		return tiers.get(tier - 1);
	}
}
