package io.github.pkeppeler.deepcharter.sound.phonograph;

/**
 * When a wreck's phonograph starts its song, from the game time it was last spent (by a start, a switch-off or a hand): it starts for a
 * player in range while armed, and is armed again once no player is in range and {@code rearmTicks} have passed since. The time is saved,
 * so leaving the chunk's simulation and coming back does not make a switched-off phonograph sing at once.
 */
public final class WreckSongArming {
    /** The saved value for a phonograph that has never been spent: armed. */
    public static final long NEVER = -1;

    private final int rearmTicks;
    private long spentAt;

    public WreckSongArming(int rearmTicks, long spentAt) {
        this.rearmTicks = rearmTicks;
        this.spentAt = spentAt;
    }

    /** The game time it was last spent, or {@link #NEVER}. */
    public long spentAt() {
        return spentAt;
    }

    /**
     * One look. Returns true when the song starts now.
     *
     * @param playerNear whether any player is within the trigger range
     * @param now the game time
     */
    public boolean look(boolean playerNear, long now) {
        if (spentAt != NEVER && !playerNear && (now < spentAt || now - spentAt >= rearmTicks)) {
            spentAt = NEVER;
        }
        if (playerNear && spentAt == NEVER) {
            spentAt = now;
            return true;
        }
        return false;
    }

    /** Sets the saved time back, when the block entity loads. */
    public void restore(long saved) {
        spentAt = saved;
    }

    /** A player started or stopped the song by hand at game time {@code now}. */
    public void byHand(long now) {
        spentAt = now;
    }
}
