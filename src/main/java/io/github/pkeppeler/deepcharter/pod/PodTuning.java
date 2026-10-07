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

	/**
	 * The cargo bay. Mass shares a unit with {@link Movement#enginePower}. The pod climbs only while
	 * thrust beats gravity, so with more mass than enginePower * gravity / thrustAcceleration it cannot
	 * take off (see {@code PodCargo.takeoffMassLimit}).
	 *
	 * @param slots           ore the bay holds, one per slot whatever its mass
	 * @param defaultOreMass  mass of an ore whose caller gives none; placeholder until ores have their own
	 *                        (the original's 1 to 12, scaled by three, puts a bay of Diamond well past the power)
	 */
	public record Cargo(int slots, float defaultOreMass) {
		public static final Cargo DEFAULT = new Cargo(7, 20f);
	}

	/**
	 * Tank and burn rates, from the original's stock pod: a 10 L tank, idle 0.063 L/s, flying 0.19 L/s,
	 * drilling 0.32 L/s. {@link PodData#FUEL} holds a percentage of the tank: litres / tankLitres * 100.
	 *
	 * @param tankLitres            what 100% means
	 * @param idleLitresPerSecond   burn with the engine on and the pod still, always on
	 * @param movingLitresPerSecond burn while driving or flying
	 * @param drillingLitresPerSecond burn while drilling
	 * @param lowFuelPercent        the beep sounds at this percentage and below
	 * @param refuelLitres          what one coal or charcoal puts in the tank
	 */
	public record Fuel(float tankLitres, float idleLitresPerSecond, float movingLitresPerSecond,
			float drillingLitresPerSecond, float lowFuelPercent, float refuelLitres) {
		public static final Fuel DEFAULT = new Fuel(10f, 0.063f, 0.19f, 0.32f, 21f, 2f);
	}
}
