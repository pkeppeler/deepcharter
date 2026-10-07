package io.github.pkeppeler.deepcharter.pod;

/**
 * Tunables for the tow cable, read as {@code TowTuning.DEFAULT.thing()}. It is not a record of {@link PodTuning}, which takes no
 * more changes.
 *
 * @param reach         blocks from the tower within which a cable can be fitted to a pod, and within which a tower feels the pod it tows
 * @param trailDistance blocks the towed pod trails behind its tower; it is held still while nearer and pulled in when further
 * @param baseMass      mass a towed pod adds to its tower besides its cargo; shares a unit with {@code PodTuning.Movement#enginePower}
 */
public record TowTuning(double reach, double trailDistance, float baseMass) {
	public static final TowTuning DEFAULT = new TowTuning(8.0, 2.5, 25f);

	public TowTuning {
		if (!(trailDistance > 0)) {
			throw new IllegalArgumentException("the trail distance must be positive, got " + trailDistance);
		}
		if (!(reach > trailDistance)) {
			throw new IllegalArgumentException("the reach must be longer than the trail distance " + trailDistance + ", got " + reach);
		}
		if (!(baseMass >= 0f)) {
			throw new IllegalArgumentException("the base mass must not be negative, got " + baseMass);
		}
	}
}
