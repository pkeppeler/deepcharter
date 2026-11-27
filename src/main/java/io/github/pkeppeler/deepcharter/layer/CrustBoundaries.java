package io.github.pkeppeler.deepcharter.layer;

/**
 * Which boundaries between bands have grained crust and which have the breach crust (#216, ADR 0060). The answer for a boundary is a
 * function of the world seed, the boundary's own Y and its layer data, and of nothing else: not the chunk asking, not the order chunks
 * generate in, and not where the band sits in the list of bands. So a boundary is the same kind in every chunk, the same seed gives the same
 * boundaries, and a band added to the data redraws none of the others.
 *
 * <p>A boundary is named by the Y of the lowest row of its crust, the floor of the layer above it. The bands are fixed in Y
 * ({@link CampaignBands}), so that Y does not change when a band is added elsewhere.
 */
public final class CrustBoundaries {
    /** Separates this draw from any other that hashes the seed. */
    private static final long DOMAIN = 0x6772_6169_6E65_6401L;

    private static final long GOLDEN = 0x9E37_79B9_7F4A_7C15L;

    private CrustBoundaries() {}

    /** Whether the crust at {@code floorY}, the lowest row of a boundary's crust, is grained: the layer data's word, else the draw. */
    public static boolean grained(long seed, int floorY, FloorCrust setting) {
        return switch (setting) {
            case NORMAL -> false;
            case GRAINED -> true;
            case DRAW -> drawn(seed, floorY);
        };
    }

    /** The draw alone: one hash of the seed and the boundary's Y, read as a number from 0 up to 1 against {@code grainDecoyChance}. */
    static boolean drawn(long seed, int floorY) {
        long hash = mix(mix(seed ^ DOMAIN) + floorY * GOLDEN);
        return (hash >>> 11) * 0x1.0p-53 < LayerTuning.DEFAULT.grainDecoyChance();
    }

    /** Stafford's variant 13 of the murmur3 finalizer: every input bit reaches every output bit. */
    private static long mix(long value) {
        value = (value ^ (value >>> 30)) * 0xBF58_476D_1CE4_E5B9L;
        value = (value ^ (value >>> 27)) * 0x94D0_49BB_1331_11EBL;
        return value ^ (value >>> 31);
    }
}
