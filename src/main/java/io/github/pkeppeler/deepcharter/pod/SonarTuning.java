package io.github.pkeppeler.deepcharter.pod;

/**
 * Tunables for the navigator's active sonar (#988), read as {@code SonarTuning.DEFAULT.thing()}. A ping is paid for in pod power, which
 * is the tank ({@link PodFuel}).
 *
 * @param rangeBlocks     how far from the pod a ping returns echoes, measured to the middle of an echo's cell
 * @param noiseBlocks     how far a ping is heard: the radius of its noise ({@link SonarEvents#PINGED}), more than the range
 * @param powerLitres     litres a ping takes from the tank
 * @param cooldownTicks   ticks after a ping before the next
 * @param lifetimeTicks   ticks the echoes of a ping show before they fade
 * @param cellBlocks      the side of the cube a block echo covers, a divisor of 16 so a cell never straddles chunks
 * @param blindBlocks     cells with their middle this close to the pod return nothing: the pod's own cab is not a cave
 * @param gasResolveBlocks gas pockets this near read as gas, farther off as an empty cave
 * @param movingSpeed     blocks per tick above which an echoing creature reads as moved
 * @param maxEchoes       the most echoes a ping returns; what moves goes first, open space last
 * @param markLifetimeTicks ticks a mark shows on the pilot's HUD
 * @param maxMarks        the most marks a pod holds; a new one beyond it drops the oldest
 * @param markCooldownTicks ticks a pod waits between two marks (or unmarks), so a client cannot force a sync at packet rate
 * @param aimDegrees      how far off the navigator's look an echo may be and still be the one they mean to mark
 * @param lureTicks       ticks a ping holds the creatures that heard it ({@link SonarLure}): the Sleeper's give up after about ten seconds of silence
 */
public record SonarTuning(
        int rangeBlocks,
        int noiseBlocks,
        float powerLitres,
        int cooldownTicks,
        int lifetimeTicks,
        int cellBlocks,
        int blindBlocks,
        int gasResolveBlocks,
        double movingSpeed,
        int maxEchoes,
        int markLifetimeTicks,
        int maxMarks,
        int markCooldownTicks,
        double aimDegrees,
        int lureTicks) {
    /**
     * (A) A ping reaches 24 blocks (the tier 1 scanner's half width), is heard 48, costs 0.4 L (4% of a stock 10 L tank, 23 pings from full to
     * the reserve, about 6 s of idle burn), and can come again after 4 s. Its echoes show 10 s, so a navigator reads one ping and the next
     * overlaps it. Cells are 4 blocks, and a cell with its middle under 6 blocks away is the pod's own cab and returns nothing; gas reads as gas inside 12 blocks. A mark holds a minute, three at a time, a pod marks at most every 5 ticks, and
     * is the echo the navigator looks within 12 degrees of. A ping holds the creatures that heard it 10 s (200 ticks), CREATURES.md's ten seconds of silence.
     */
    public static final SonarTuning DEFAULT =
            new SonarTuning(24, 48, 0.4f, 80, 200, 4, 6, 12, 0.05, 32, 1200, 3, 5, 12.0, 200);

    public SonarTuning {
        if (rangeBlocks < 1 || noiseBlocks < rangeBlocks) {
            throw new IllegalArgumentException(
                    "a ping is heard at least as far as it sees: range " + rangeBlocks + ", noise " + noiseBlocks);
        }
        if (!(powerLitres > 0f)) {
            throw new IllegalArgumentException("a ping costs power, got " + powerLitres + " litres");
        }
        if (markLifetimeTicks < 1 || maxMarks < 1 || markCooldownTicks < 0 || !(aimDegrees > 0 && aimDegrees < 90)) {
            throw new IllegalArgumentException(
                    "a mark holds 1 tick or more, a pod holds 1 mark or more, and the aim is under 90 degrees");
        }
        if (lureTicks < 0) {
            throw new IllegalArgumentException("a ping holds a creature 0 ticks or more, got " + lureTicks);
        }
        if (cooldownTicks < 0 || lifetimeTicks < 1 || maxEchoes < 1) {
            throw new IllegalArgumentException(
                    "a ping waits 0 ticks or more, shows 1 tick or more, and returns 1 echo or more");
        }
        if (cellBlocks < 1 || 16 % cellBlocks != 0 || blindBlocks < 0 || gasResolveBlocks < 0 || !(movingSpeed >= 0)) {
            throw new IllegalArgumentException("a sonar cell divides a chunk, and its distances are 0 or more");
        }
    }
}
