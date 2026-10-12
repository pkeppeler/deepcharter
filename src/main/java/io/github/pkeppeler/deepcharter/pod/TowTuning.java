package io.github.pkeppeler.deepcharter.pod;

/**
 * Tunables for the tow cable, read as {@code TowTuning.DEFAULT.thing()}. It is not a record of {@link PodTuning}, which takes no
 * more changes.
 *
 * @param reach          least blocks from the tower within which a cable can be fitted to a pod, and within which a tower feels the pod it tows;
 *                       the reach in use is {@link #reachFor}, which is longer when the widest chassis trails further
 * @param lineLength     blocks of free line between the faces of the two hulls when the cable is taut: in the air the towed pod hangs from
 *                       it like a sling load, on the ground it trails on it like a tow rope (see {@link #trailDistance}, which adds the two
 *                       pods' half widths, 6.9 for two Moles). Longer than a tow rope, shorter than a helicopter's sling
 * @param reachSpare     blocks of reach beyond the longest taut line, so a pod at the end of its line is in reach
 * @param baseMass       mass a towed pod adds to its tower besides its cargo; shares a unit with {@code PodTuning.Movement#enginePower}
 * @param gravity        blocks a tick, each tick, that a towed pod gains in falling; the line holds it up once it is taut
 * @param drag           share of its speed a towed pod keeps each tick; it is what damps a swing, so it is under 1
 * @param groundFriction share of its speed a towed pod keeps each tick that it rests on a floor
 */
public record TowTuning(double reach, double lineLength, double reachSpare, float baseMass, double gravity, double drag, double groundFriction) {
	public static final TowTuning DEFAULT = new TowTuning(8.0, 5.0, 0.5, 25f, 0.08, 0.97, 0.6);

	public TowTuning {
		if (!(lineLength > 0)) {
			throw new IllegalArgumentException("the line length must be positive, got " + lineLength);
		}
		if (!(reach > 0)) {
			throw new IllegalArgumentException("the reach must be positive, got " + reach);
		}
		if (!(reachSpare >= 0)) {
			throw new IllegalArgumentException("the reach spare must not be negative, got " + reachSpare);
		}
		if (!(baseMass >= 0f)) {
			throw new IllegalArgumentException("the base mass must not be negative, got " + baseMass);
		}
		if (!(gravity >= 0)) {
			throw new IllegalArgumentException("the gravity must not be negative, got " + gravity);
		}
		if (!(drag > 0 && drag < 1)) {
			throw new IllegalArgumentException("the drag must be above 0 and under 1, or a swing never dies away, got " + drag);
		}
		if (!(groundFriction >= 0 && groundFriction <= 1)) {
			throw new IllegalArgumentException("the ground friction must be from 0 to 1, got " + groundFriction);
		}
	}

	/** The reach with {@code widest}, the widest chassis registered: {@link #reach}, or the taut line of two such hulls plus {@link #reachSpare} if that is longer, so no trailing pod is out of reach. */
	public double reachFor(Chassis widest) {
		return Math.max(reach, trailDistance(widest, widest) + reachSpare);
	}

	/** Blocks from the tower's middle to the middle of the pod it tows when the line is taut: half of each hull's width and {@link #lineLength}, so wide pods never overlap. */
	public double trailDistance(Chassis tower, Chassis towed) {
		return (tower.width() + towed.width()) / 2.0 + lineLength;
	}

	/** As {@link #trailDistance(Chassis, Chassis)} for the chassis of two pods. */
	public double trailDistance(PodEntity tower, PodEntity towed) {
		return trailDistance(tower.chassis(), towed.chassis());
	}
}
