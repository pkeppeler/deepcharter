package io.github.pkeppeler.deepcharter.pod;

/**
 * Tunables for the tow cable, read as {@code TowTuning.DEFAULT.thing()}. It is not a record of {@link PodTuning}, which takes no
 * more changes.
 *
 * @param reach         blocks from the tower within which a cable can be fitted to a pod, and within which a tower feels the pod it tows
 * @param trailGap      blocks between the faces of the two hulls when the towed pod trails behind its tower; it is held still while nearer
 *                      and pulled in when further (see {@link #trailDistance}, which adds the two pods' half widths, 2.5 for two Moles)
 * @param baseMass      mass a towed pod adds to its tower besides its cargo; shares a unit with {@code PodTuning.Movement#enginePower}
 * @param cableInterval ticks between two draws of the cable's particles
 * @param cableSpacing  blocks between the particles of one draw
 * @param cableMaxParticles most particles in one draw, which bounds the cost of a long cable
 */
public record TowTuning(double reach, double trailGap, float baseMass, int cableInterval, double cableSpacing, int cableMaxParticles) {
	public static final TowTuning DEFAULT = new TowTuning(8.0, 0.6, 25f, 4, 0.4, 16);

	public TowTuning {
		if (!(trailGap > 0)) {
			throw new IllegalArgumentException("the trail gap must be positive, got " + trailGap);
		}
		if (!(reach > trailGap)) {
			throw new IllegalArgumentException("the reach must be longer than the trail gap " + trailGap + ", got " + reach);
		}
		if (!(baseMass >= 0f)) {
			throw new IllegalArgumentException("the base mass must not be negative, got " + baseMass);
		}
		if (cableInterval < 1) {
			throw new IllegalArgumentException("the cable interval must be at least 1 tick, got " + cableInterval);
		}
		if (!(cableSpacing > 0)) {
			throw new IllegalArgumentException("the cable spacing must be positive, got " + cableSpacing);
		}
		if (cableMaxParticles < 1) {
			throw new IllegalArgumentException("the cable must draw at least 1 particle, got " + cableMaxParticles);
		}
	}

	/** Blocks from the tower's middle to the middle of the pod it tows: half of each hull's width and the gap between the hulls, so wide pods never overlap. */
	public double trailDistance(Chassis tower, Chassis towed) {
		return (tower.width() + towed.width()) / 2.0 + trailGap;
	}

	/** As {@link #trailDistance(Chassis, Chassis)} for the chassis of two pods. */
	public double trailDistance(PodEntity tower, PodEntity towed) {
		return trailDistance(tower.chassis(), towed.chassis());
	}
}
