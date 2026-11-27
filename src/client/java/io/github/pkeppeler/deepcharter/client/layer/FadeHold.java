package io.github.pkeppeler.deepcharter.client.layer;

/**
 * Whether a breach fade waits at the end of its black stretch (#1185, ADR 0089): the plain logic of {@link BreachEffects}' hold, with
 * no client in it so a server GameTest can run it. A fading crossing arms it; once the fade has reached the end of its black stretch
 * it holds until the client has seen the respawn into the level it crossed to and reports itself loaded, and at most
 * {@link #MAX_HOLD_TICKS} ticks. A respawn that came {@value #RESPAWN_WINDOW_TICKS} ticks or less before the crossing began is the
 * crossing's own; a {@code loaded} that was true before the respawn does not count, since only a respawn seen since arms it.
 */
public final class FadeHold {
    /** The most ticks the fade is held black, so a level that never reports itself loaded does not leave the screen black (10 s). */
    public static final int MAX_HOLD_TICKS = 200;

    /** A respawn this many client ticks before the payload is the crossing's own: the server sends the respawn, then the payload. */
    public static final int RESPAWN_WINDOW_TICKS = 40;

    private boolean armed;
    private boolean sawRespawn;
    private int ticksSinceRespawn = Integer.MAX_VALUE;
    private int heldTicks;

    /** A crossing begins; only one that fades is held. */
    public void begin(boolean fades) {
        armed = fades;
        heldTicks = 0;
        sawRespawn = fades && ticksSinceRespawn <= RESPAWN_WINDOW_TICKS;
    }

    /** The client has handled a respawn. */
    public void respawned() {
        ticksSinceRespawn = 0;
        if (armed) {
            sawRespawn = true;
        }
    }

    /** One client tick passes, fade or no fade. */
    public void clockTick() {
        if (ticksSinceRespawn < Integer.MAX_VALUE) {
            ticksSinceRespawn++;
        }
    }

    /**
     * Whether the fade, {@code elapsed} ticks in with its black stretch over at {@code blackEnd}, waits this tick. The arm is kept through
     * the fade-in and the black stretch, and let go only when the hold ends.
     */
    public boolean holds(int elapsed, int blackEnd, boolean clientLoaded) {
        if (!armed || elapsed < blackEnd) {
            return false;
        }
        if (heldTicks >= MAX_HOLD_TICKS || (sawRespawn && clientLoaded)) {
            armed = false;
            return false;
        }
        heldTicks++;
        return true;
    }

    /** Ticks the latest fade was held, kept after it ends. */
    public int heldTicks() {
        return heldTicks;
    }

    /** The fade is over: nothing is armed. */
    public void end() {
        armed = false;
        sawRespawn = false;
    }

    /** A disconnect: nothing runs on, and a respawn seen before it is not a later crossing's. */
    public void reset() {
        end();
        ticksSinceRespawn = Integer.MAX_VALUE;
    }
}
