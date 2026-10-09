package io.github.pkeppeler.deepcharter.pod;

import java.util.List;

/**
 * Tunables for the liner part (#339), read as {@code PodLinerTuning.DEFAULT.thing()}. A liner lines a ring round the bore with the
 * rack's slag brick each time the pod has sunk a number of slabs. Its prices are {@code UpgradeTuning}'s.
 *
 * @param tiers one entry for each part tier from 1
 */
public record PodLinerTuning(List<Tier> tiers) {
	/**
	 * What one tier of the liner does.
	 *
	 * @param ringEverySlabs     slabs the pod sinks between two rings
	 * @param cellsPerBrick      cells one brick of the rack lines: 1 is a brick for each cell, as by hand; more is the cheaper fused lining
	 * @param drillSpeedPenalty  share of the drill's speed the liner takes, 0 up to but not including 1
	 * @param linesWhileFalling  whether the ring is also laid when the pod is off the ground; otherwise a ring that falls due in a fall waits until the pod rests
	 */
	public record Tier(int ringEverySlabs, int cellsPerBrick, float drillSpeedPenalty, boolean linesWhileFalling) {
		public Tier {
			if (ringEverySlabs < 1 || cellsPerBrick < 1) {
				throw new IllegalArgumentException("a liner tier rings every 1 slab or more and a brick lines 1 cell or more");
			}
			if (!(drillSpeedPenalty >= 0f && drillSpeedPenalty < 1f)) {
				throw new IllegalArgumentException("the drill speed penalty is a share from 0 up to 1, got " + drillSpeedPenalty);
			}
		}

		/** The ticks a hardness takes with this liner fitted, from the ticks without it. */
		public float slowedDrill(float ticksPerHardness) {
			return ticksPerHardness / (1 - drillSpeedPenalty);
		}
	}

	/** (A) Tier 1 rings every 4 slabs, at rest only. Tier 2 rings every 3, also in a fall, and a brick lines 2 cells. A better liner is the slower drill. */
	public static final PodLinerTuning DEFAULT = new PodLinerTuning(List.of(
			new Tier(4, 1, 0.10f, false),
			new Tier(3, 2, 0.15f, true)));

	public PodLinerTuning {
		tiers = List.copyOf(tiers);
	}

	/** The tier's numbers; {@code tier} is 1 or more and no more than the part's best. */
	public Tier tier(int tier) {
		if (tier < 1 || tier > tiers.size()) {
			throw new IllegalArgumentException("the liner has tiers 1 to " + tiers.size() + ", got " + tier);
		}
		return tiers.get(tier - 1);
	}
}
