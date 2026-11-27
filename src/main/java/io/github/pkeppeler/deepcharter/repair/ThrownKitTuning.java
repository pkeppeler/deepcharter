package io.github.pkeppeler.deepcharter.repair;

/**
 * Tunables for the thrown kit (#985), read as {@code ThrownKitTuning.DEFAULT.thing()}: the flares, decoys and charges a person on foot throws by hand
 * (CREATURES section 8). Prices live in {@link Consumable}; the blast's damage to pods and reach are {@link RepairTuning}'s, because a thrown
 * charge blasts as the pod's does. Its volume is its own, and smaller than a seated charge's (#1458).
 *
 * <p>A throw carries the tuning it was made with, so a test can shorten a duration without touching the shipped numbers.
 *
 * @param throwSpeed blocks a tick that an item leaves the hand at; with the projectile's gravity it sets the range, which {@code ThrownKitPacingTest} measures
 * @param flare      what a thrown flare does
 * @param decoy      what a thrown decoy does
 * @param charge     what a thrown charge does
 */
public record ThrownKitTuning(float throwSpeed, Flare flare, Decoy decoy, Charge charge) {
    public static final ThrownKitTuning DEFAULT =
            new ThrownKitTuning(1.2f, Flare.DEFAULT, Decoy.DEFAULT, Charge.DEFAULT);

    public ThrownKitTuning {
        if (throwSpeed <= 0) {
            throw new IllegalArgumentException("thrown kit tuning out of range: " + this);
        }
    }

    /**
     * A flare lands, stands in the floor and burns: a block of light that the light engine spreads, so everything that reads light (the Retained's
     * rule of block light under 3, the natural spawn) sees it. A pod within {@code keepOffBlocks} of it draws no Retained: a lit pod would not.
     *
     * @param lightLevel   the flare block's light, read once when the block is registered
     * @param keepOffBlocks blocks from the flare within which a pod counts as lit
     * @param burnTicks    ticks the flare burns before it goes out
     */
    public record Flare(int lightLevel, double keepOffBlocks, int burnTicks) {
        public static final Flare DEFAULT = new Flare(13, 10, 1800);

        public Flare {
            if (lightLevel < 1 || lightLevel > 15 || keepOffBlocks <= 0 || burnTicks < 1) {
                throw new IllegalArgumentException("flare tuning out of range: " + this);
            }
        }
    }

    /**
     * A decoy lands and makes a noise, a source that things drawn to the drill read ({@code DrawSources}).
     *
     * @param noiseTicks ticks the decoy sounds
     * @param pullBlocks blocks from the decoy within which a creature drawn to the drill goes to it instead
     * @param pulseTicks ticks between two sounds the player hears, so the noise is something to hear
     */
    public record Decoy(int noiseTicks, double pullBlocks, int pulseTicks) {
        public static final Decoy DEFAULT = new Decoy(900, 40, 20);

        public Decoy {
            if (noiseTicks < 1 || pullBlocks <= 0 || pulseTicks < 1) {
                throw new IllegalArgumentException("decoy tuning out of range: " + this);
            }
        }
    }

    /**
     * A charge lands, a fuse burns, and it blasts as the pod's blasting charge does, round where it lies.
     *
     * @param fuseTicks    ticks from landing to the blast
     * @param blastRadius  blocks each side of the charge, so 1 is a 3 x 3 column, as wide as a bore
     * @param depth        rows under the charge that the blast clears: 1, so a throw clears 9 cells, under the 16 of a Mole's seated
     *                     charge ({@code RepairTuning.chargeDepth} rows of its 2 x 2 bore); a throw is a fighting round, not the shaft tool
     * @param personDamage health a person at the blast takes, less with distance; it never kills (it leaves a person at half a heart at worst)
     */
    public record Charge(int fuseTicks, int blastRadius, int depth, float personDamage) {
        public static final Charge DEFAULT = new Charge(60, 1, 1, 8f);

        public Charge {
            if (fuseTicks < 1 || blastRadius < 0 || depth < 1 || personDamage < 0) {
                throw new IllegalArgumentException("charge tuning out of range: " + this);
            }
        }
    }
}
