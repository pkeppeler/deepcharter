package io.github.pkeppeler.deepcharter.sound.phonograph;

/**
 * When a wreck's phonograph starts its song: the one decision it makes at each look for a player, with no world in it.
 *
 * <p>It starts when a player comes within range while it is armed, and is then spent. A song that has ended, and a phonograph a player
 * switched off, stay quiet however long the player lingers (a right click starts it again by hand). It is armed again once no player has
 * been within range for {@code rearmTicks}, so a visitor who turns it off is not sung to again on the way out of the bay, and the next visitor
 * (or the same one, much later) still hears the tune. A restart finds it armed, as the state is not saved.
 */
public final class WreckSongArming {
    private final int rearmTicks;
    private boolean armed = true;
    private int awayTicks;

    public WreckSongArming(int rearmTicks) {
        this.rearmTicks = rearmTicks;
    }

    public boolean isArmed() {
        return armed;
    }

    /**
     * One look, {@code elapsed} ticks after the last. Returns true when the song starts now.
     *
     * @param playerNear whether any player is within the trigger range
     */
    public boolean look(boolean playerNear, int elapsed) {
        if (playerNear) {
            awayTicks = 0;
            boolean start = armed;
            armed = false;
            return start;
        }
        if (!armed) {
            awayTicks += elapsed;
            if (awayTicks >= rearmTicks) {
                armed = true;
                awayTicks = 0;
            }
        }
        return false;
    }

    /** A player started or stopped the song by hand: it is spent, and re-arms only after the long absence. */
    public void byHand() {
        armed = false;
        awayTicks = 0;
    }
}
