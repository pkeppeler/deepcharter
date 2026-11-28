package io.github.pkeppeler.deepcharter.pod;

/**
 * Tunables for the pod feature, one nested record per part so that parallel changes do not touch
 * the same lines. Add one component per tunable to your part's record and give it its value where
 * that record's {@code DEFAULT} is built; read it as {@code PodTuning.DEFAULT.movement().thing()}.
 */
public record PodTuning(Shell shell, Movement movement, Drill drill, Cargo cargo, Fuel fuel) {
    public static final PodTuning DEFAULT =
            new PodTuning(Shell.DEFAULT, Movement.DEFAULT, Drill.DEFAULT, Cargo.DEFAULT, Fuel.DEFAULT);

    /** Stock hull points (and the stock maximum, see PodStats) and fuel percentage of a new pod. */
    public record Shell(float fullHull, float fullFuel) {
        public static final Shell DEFAULT = new Shell(100f, 100f);
    }

    /**
     * Treads and rotor. Speeds are blocks per tick, accelerations blocks per tick squared, powers and
     * masses share one unit, damage is in hull points.
     *
     * @param horizontalSpeed     speed along the one axis the pilot drives: 4 blocks a second, which sprint doubles (#1594)
     * @param enginePower         rotor power; lift is this minus the cargo mass
     * @param thrustAcceleration  upward acceleration at full lift (lift equal to engine power)
     * @param maxClimbSpeed       the rotor cannot push the pod up faster than this
     * @param gravity             downward acceleration; entities default to none, so the pod brings its own
     * @param verticalDrag        per-tick factor on vertical speed, so a fall has a terminal speed
     * @param hardLandingSpeed    sink speed at landing, in blocks per tick, up to which the pod takes no damage (HardLanding)
     * @param hullDamagePerSpeed  hull damage for each block per tick of landing speed beyond the threshold
     */
    public record Movement(
            float horizontalSpeed,
            float enginePower,
            float thrustAcceleration,
            float maxClimbSpeed,
            float gravity,
            float verticalDrag,
            float hardLandingSpeed,
            float hullDamagePerSpeed) {
        public static final Movement DEFAULT = new Movement(0.2f, 100f, 0.16f, 0.35f, 0.08f, 0.98f, 0.7f, 70f);

        /** Next tick's vertical speed from {@code vy}, with {@code pull} of gravity and the drag: the one fall rule, for the pod and for HardLanding's sums. */
        public double fall(double vy, double pull) {
            return (vy - pull) * verticalDrag;
        }
    }

    /**
     * Drill rates. Hardness is the block's vanilla destroy speed, so stone (1.5) takes 24 ticks at the surface, the
     * original stock drill's 1.2 s per tile.
     *
     * @param ticksPerHardness ticks at the surface per point of hardness
     * @param crustHullDamage  hull points lost for each crust slab bored
     * @param alignSpeed       blocks per tick the pod slides to centre itself in its bore
     */
    public record Drill(float ticksPerHardness, float crustHullDamage, double alignSpeed) {
        public static final Drill DEFAULT = new Drill(16f, 8f, 0.1);
    }

    /** Bay size. Ore mass shares a unit with enginePower (100 = no lift at all) and lives in {@code OreType}. */
    public record Cargo(int slots) {
        public static final Cargo DEFAULT = new Cargo(7);
    }

    /**
     * Stock-pod tank and burn rates; FUEL is a percentage of the tank (litres / tankLitres * 100). The tank is the original's 10 L, but the rates
     * are about a seventh of the original's per-second 0.063, 0.19 and 0.32 (#963): our drill takes 2.4 s a block in layer 1, so the original's
     * rates left a first tank 13 blocks and 31 s of drilling. {@code PodFuelTripTest} measures what a tank buys now.
     * What a fuel item gives is data (PodFuelItems). The beep sounds at lowFuelPercent and below, faster at urgentFuelPercent.
     */
    public record Fuel(
            float tankLitres,
            float idleLitresPerSecond,
            float movingLitresPerSecond,
            float drillingLitresPerSecond,
            float lowFuelPercent,
            float urgentFuelPercent) {
        public static final Fuel DEFAULT = new Fuel(10f, 0.009f, 0.027f, 0.046f, 21f, 6f);
    }
}
