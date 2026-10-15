package io.github.pkeppeler.deepcharter.creature;

/**
 * Tunables for the creature feature, read as {@code CreatureTuning.DEFAULT.thing()}.
 *
 * @param walkSpeed            the figure's movement speed attribute; 0.22 is about 2 blocks a second
 * @param litBlockLight        block light at a place at which it counts as lit: no figure appears there
 * @param senseCheckTicks      how often a figure looks for a dark pod
 * @param spawnIntervalTicks   how often each rail level looks for a rail site that needs a figure
 * @param spawnRangeBlocks     a rail site spawns a figure when a player is within this many blocks of its centre
 * @param spawnClearanceBlocks how far from every player a rail figure may appear
 * @param maxPerSite           figures on one rail site
 * @param maxPerLevel          figures in one level, however they came
 * @param respawnDelayTicks    ticks after a figure was killed before the level spawns another
 * @param maxHealth            the figure's health: what the cutter, or a person's hand, has to take off it
 * @param cling                what a figure does to a dark pod
 * @param natural              how figures appear in the open dark of the layers
 */
public record CreatureTuning(
        float walkSpeed,
        int litBlockLight,
        int senseCheckTicks,
        int spawnIntervalTicks,
        double spawnRangeBlocks,
        double spawnClearanceBlocks,
        int maxPerSite,
        int maxPerLevel,
        int respawnDelayTicks,
        float maxHealth,
        Cling cling,
        Natural natural) {
    public static final CreatureTuning DEFAULT =
            new CreatureTuning(0.22f, 3, 5, 100, 96, 16, 1, 5, 400, 12f, Cling.DEFAULT, Natural.DEFAULT);

    public CreatureTuning {
        if (walkSpeed <= 0
                || litBlockLight < 1
                || litBlockLight > 15
                || senseCheckTicks < 1
                || spawnIntervalTicks < 1
                || spawnRangeBlocks <= 0
                || spawnClearanceBlocks <= 0
                || maxPerSite < 1
                || maxPerLevel < maxPerSite
                || respawnDelayTicks < 0
                || maxHealth <= 0) {
            throw new IllegalArgumentException("creature tuning out of range: " + this);
        }
    }

    /**
     * A pod with a pilot, no lights and power draws a figure; it grabs the hull and drains the tank until it dies. It never takes the
     * tank below the floor. The pod's cutter, spinning while the pod drills, bites it.
     *
     * @param senseBlocks  a dark pod this close draws a figure that is not clinging
     * @param reachBlocks  a figure this close to the hull (the gap between the two boxes) grabs it
     * @param latchSpeed   blocks per tick the figure closes the gap to its place on the hull
     * @param drainLitres  litres a second that a clinging figure takes
     * @param floorPercent fuel percent below which a figure takes nothing
     * @param biteDamage   health the cutter takes off a clinging figure at each bite
     * @param biteTicks    ticks between two bites while the pod drills; the first bite comes as the drill starts
     */
    public record Cling(
            double senseBlocks,
            double reachBlocks,
            double latchSpeed,
            float drainLitres,
            float floorPercent,
            float biteDamage,
            int biteTicks) {
        public static final Cling DEFAULT = new Cling(20, 2.5, 0.4, 0.15f, 5f, 2f, 10);

        public Cling {
            if (senseBlocks <= 0
                    || reachBlocks <= 0
                    || latchSpeed <= 0
                    || drainLitres <= 0
                    || floorPercent < 0
                    || floorPercent >= 100
                    || biteDamage <= 0
                    || biteTicks < 1) {
                throw new IllegalArgumentException("cling tuning out of range: " + this);
            }
        }
    }

    /**
     * The natural-spawn tunables of a figure.
     *
     * @param chancePerTry       the chance that a place the game tries for a natural spawn in layer 2, and that passes every rule, gets a figure; the deeper layer rolls more often
     * @param layerOneChancePerTry the same in layer 1, where a pilot is newer and the runs short: rarer than layer 2, and a real presence all the same (#506)
     * @param roomBlocks         air blocks above the floor that a figure needs to appear
     */
    public record Natural(double chancePerTry, double layerOneChancePerTry, int roomBlocks) {
        public static final Natural DEFAULT = new Natural(0.05, 0.01, 3);

        public Natural {
            if (chancePerTry <= 0
                    || chancePerTry > 1
                    || layerOneChancePerTry <= 0
                    || layerOneChancePerTry > chancePerTry
                    || roomBlocks < 1) {
                throw new IllegalArgumentException("natural spawn tuning out of range: " + this);
            }
        }
    }
}
