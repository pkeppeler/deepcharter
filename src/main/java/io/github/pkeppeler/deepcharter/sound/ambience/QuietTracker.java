package io.github.pkeppeler.deepcharter.sound.ambience;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Whether anything that holds the ambience back is sounding, or ended a moment ago. It is told of each sound as it starts, and asks
 * {@code isActive} whether it still plays. Times are ticks on any clock that only goes forward.
 *
 * @param <T> a sound as the caller knows it
 */
public final class QuietTracker<T> {
    private static final long NEVER = Long.MIN_VALUE / 2;

    private final List<T> playing = new ArrayList<>();
    private final Predicate<T> isActive;
    private final int graceTicks;
    private long lastHeard = NEVER;

    public QuietTracker(Predicate<T> isActive, int graceTicks) {
        this.isActive = isActive;
        this.graceTicks = graceTicks;
    }

    public void started(T sound, long now) {
        playing.add(sound);
        lastHeard = now;
    }

    /** True while a sound plays and for {@code graceTicks} after the last one was seen playing. */
    public boolean quiet(long now) {
        playing.removeIf(sound -> !isActive.test(sound));
        if (!playing.isEmpty()) {
            lastHeard = now;
            return true;
        }
        return now - lastHeard < graceTicks;
    }

    /** Forgets every sound, for a player who has left the world. */
    public void clear() {
        playing.clear();
        lastHeard = NEVER;
    }
}
