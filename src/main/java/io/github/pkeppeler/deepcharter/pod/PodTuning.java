package io.github.pkeppeler.deepcharter.pod;

/**
 * Tunables for the pod feature, one nested record per part so that parallel changes do not touch
 * the same lines. Add one component per tunable to your part's record and give it its value where
 * that record's {@code DEFAULT} is built; read it as {@code PodTuning.DEFAULT.movement().thing()}.
 */
public record PodTuning(Shell shell, Movement movement, Drill drill, Cargo cargo, Fuel fuel) {
	public static final PodTuning DEFAULT = new PodTuning(Shell.DEFAULT, Movement.DEFAULT, Drill.DEFAULT, Cargo.DEFAULT, Fuel.DEFAULT);

	/** Gauges a new pod starts with, as percentages. */
	public record Shell(float fullHull, float fullFuel) {
		public static final Shell DEFAULT = new Shell(100f, 100f);
	}

	/**
	 * Treads and rotor. Speeds are blocks per tick, accelerations blocks per tick squared, powers and
	 * masses share one unit, damage is in hull percentage points.
	 *
	 * @param horizontalSpeed     speed along the one axis the pilot drives
	 * @param enginePower         rotor power; lift is this minus the cargo mass
	 * @param thrustAcceleration  upward acceleration at full lift (lift equal to engine power)
	 * @param maxClimbSpeed       the rotor cannot push the pod up faster than this
	 * @param gravity             downward acceleration; entities default to none, so the pod brings its own
	 * @param verticalDrag        per-tick factor on vertical speed, so a fall has a terminal speed
	 * @param hardLandingDistance a fall of this many blocks or fewer does no damage
	 * @param hullDamagePerBlock  hull damage for each block fallen beyond the threshold
	 */
	public record Movement(float horizontalSpeed, float enginePower, float thrustAcceleration, float maxClimbSpeed,
			float gravity, float verticalDrag, float hardLandingDistance, float hullDamagePerBlock) {
		public static final Movement DEFAULT = new Movement(0.2f, 100f, 0.16f, 0.35f, 0.08f, 0.98f, 4f, 5f);
	}

	/** Filled by #30. */
	public record Drill() {
		public static final Drill DEFAULT = new Drill();
	}

	/** Bay size and the placeholder ore mass (mass shares a unit with enginePower, 100 = no lift at all). */
	public record Cargo(int slots, float defaultOreMass) {
		public static final Cargo DEFAULT = new Cargo(7, 20f);
	}

	/**
	 * Original stock-pod tank and rates; FUEL is a percentage of the tank (litres / tankLitres * 100).
	 * refuelLitres is invented. The beep sounds at lowFuelPercent and below, faster at urgentFuelPercent.
	 */
	public record Fuel(float tankLitres, float idleLitresPerSecond, float movingLitresPerSecond,
			float drillingLitresPerSecond, float lowFuelPercent, float urgentFuelPercent, float refuelLitres) {
		public static final Fuel DEFAULT = new Fuel(10f, 0.063f, 0.19f, 0.32f, 21f, 6f, 2f);
	}
}
