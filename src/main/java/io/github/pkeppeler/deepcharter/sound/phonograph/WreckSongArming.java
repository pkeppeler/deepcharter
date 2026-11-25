package io.github.pkeppeler.deepcharter.sound.phonograph;

/**
 * When a wreck's phonograph starts its song. It starts for a player in range while armed, and is spent by that, by a switch-off or by a
 * hand. It is armed again once nobody has been in range for {@code rearmTicks}, counted from the later of the time it was spent and the
 * last time anyone was near, so a visitor who lingers and steps out and back is not sung to again. Both times are game times, saved with the
 * block, so leaving the chunk's simulation or a restart does not make a switched-off phonograph sing at once.
 */
public final class WreckSongArming {
    /** The saved value for a time that never was: armed. */
    public static final long NEVER = -1;

    private final int rearmTicks;
    private long spentAt = NEVER;
    private long lastNearAt = NEVER;

    public WreckSongArming(int rearmTicks) {
        this.rearmTicks = rearmTicks;
    }

    /** The game time it was last spent, or {@link #NEVER}. */
    public long spentAt() {
        return spentAt;
    }

    /** The last game time a player was near at a look, or {@link #NEVER}. */
    public long lastNearAt() {
        return lastNearAt;
    }

    /**
     * One look. Returns true when the song starts now.
     *
     * @param playerNear whether any player is within the trigger range
     * @param now the game time
     */
    public boolean look(boolean playerNear, long now) {
        if (playerNear) {
            lastNearAt = now;
        }
        if (spentAt != NEVER && !playerNear && rearmed(now)) {
            spentAt = NEVER;
            lastNearAt = NEVER;
        }
        if (playerNear && spentAt == NEVER) {
            spentAt = now;
            return true;
        }
        return false;
    }

    /** A time later than now is a clock that went back (a copied world): it does not hold the phonograph spent for ever. */
    private boolean rearmed(long now) {
        return now < spentAt || now < lastNearAt || now - Math.max(spentAt, lastNearAt) >= rearmTicks;
    }

    /** A player started or stopped the song by hand at game time {@code now}. */
    public void byHand(long now) {
        spentAt = now;
        lastNearAt = now;
    }

    /** Sets the saved times back, when the block entity loads. */
    public void restore(long savedSpentAt, long savedLastNearAt) {
        spentAt = savedSpentAt;
        lastNearAt = savedLastNearAt;
    }
}
