package io.github.pkeppeler.deepcharter.creature;

/**
 * Tunables for the Tallies (#917), read as {@code TallyTuning.DEFAULT.thing()}. A Tally is a tax, not a run-ender: it takes one ore, once.
 *
 * @param casingRangeBlocks     a person or pod this close to the Conduit's axis (blocks, square) in layer 1 or 2 may draw a Tally; the casing is solid
 *                              rock, so the zone round it is where a Tally is met
 * @param checkIntervalTicks    how often each layer looks at the marks near the casing for a Tally to send
 * @param layerOneChance        the chance of a check that finds a mark, in layer 1, that a Tally comes
 * @param layerTwoChance        the same in layer 2, which the Company worked harder
 * @param maxNear               Tallies within {@code senseBlocks} of one mark, counting the one it already has
 * @param senseBlocks           how far a Tally notices a pod that holds ore, or a person who carries it
 * @param spawnMinBlocks        the nearest to its mark a Tally appears
 * @param spawnMaxBlocks        the farthest
 * @param trailGapBlocks        how close a trailing Tally keeps to its mark
 * @param trailTicks            ticks a Tally must trail a mark, within sense, before it clamps on: about 30 seconds
 * @param forgetTicks           ticks out of sense after which a Tally forgets its mark and its count starts again
 * @param idleTicks             ticks without a mark after which a Tally goes back into the casing and is gone
 * @param podSampleTicks        ticks a Tally holds the cargo hatch before it takes its sample, if the cutter has not broken it
 * @param footSampleTicks       the same on a person's pack: shorter, a person has only a hand to break it with
 * @param leaveTicks            ticks a Tally with its sample flies off before it is gone
 * @param maxHealth             a Tally's health: what the cutter, one bite at a time, has to take off it. A person's blow takes all of it
 * @param biteDamage            health the cutter takes off a clamped Tally at each bite
 * @param biteTicks             ticks between two bites while the pod drills; the first comes as the drill starts
 * @param flySpeed              the flying speed attribute
 * @param latchSpeed            blocks per tick a Tally closes the gap to its place on the hatch or the pack
 */
public record TallyTuning(
        double casingRangeBlocks,
        int checkIntervalTicks,
        double layerOneChance,
        double layerTwoChance,
        int maxNear,
        double senseBlocks,
        double spawnMinBlocks,
        double spawnMaxBlocks,
        double trailGapBlocks,
        int trailTicks,
        int forgetTicks,
        int idleTicks,
        int podSampleTicks,
        int footSampleTicks,
        int leaveTicks,
        float maxHealth,
        float biteDamage,
        int biteTicks,
        double flySpeed,
        double latchSpeed) {
    public static final TallyTuning DEFAULT =
            new TallyTuning(48, 200, 0.03, 0.10, 1, 24, 10, 20, 3, 600, 100, 1200, 60, 40, 40, 6f, 2f, 10, 0.6, 0.4);

    public TallyTuning {
        if (casingRangeBlocks <= 0
                || checkIntervalTicks < 1
                || layerOneChance <= 0
                || layerTwoChance < layerOneChance
                || layerTwoChance > 1
                || maxNear < 1
                || senseBlocks <= 0
                || spawnMinBlocks <= 0
                || spawnMaxBlocks < spawnMinBlocks
                || spawnMaxBlocks > senseBlocks
                || trailGapBlocks <= 0
                || trailTicks < 1
                || forgetTicks < 1
                || idleTicks < 1
                || podSampleTicks < 1
                || footSampleTicks < 1
                || leaveTicks < 1
                || maxHealth <= 0
                || biteDamage <= 0
                || biteTicks < 1
                || flySpeed <= 0
                || latchSpeed <= 0) {
            throw new IllegalArgumentException("tally tuning out of range: " + this);
        }
    }
}
