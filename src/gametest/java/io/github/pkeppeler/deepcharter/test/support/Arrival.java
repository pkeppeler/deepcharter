package io.github.pkeppeler.deepcharter.test.support;

import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

/**
 * A value a test rig has only once a later step has run, such as a pod created when a far chunk ticks entities
 * ({@link FarChunks#awaitEntityTicking}, which returns before its callback runs). The callback {@link #set}s it; a
 * poller reads it with {@link #ifPresent}, or with {@link #get} once it knows the step has run.
 *
 * @param <T> the arriving value
 */
public final class Arrival<T> {
    private final String what;
    private final String step;
    private @Nullable T value;

    public Arrival(String what, String step) {
        this.what = what;
        this.step = step;
    }

    public void set(T value) {
        if (this.value != null) {
            throw new IllegalStateException(what + ": already arrived, set a second time");
        }
        this.value = value;
    }

    /**
     * Swaps an arrived value for another. For last-seen tracking, as in {@code LavaBoreTest}'s bore re-seating its
     * pod; a value that is only set once uses {@link #set}. Throws before the first value arrives.
     */
    public void replace(T value) {
        if (this.value == null) {
            throw new IllegalStateException(what + ": replaced before it arrived (set once " + step + ")");
        }
        this.value = value;
    }

    public boolean isPresent() {
        return value != null;
    }

    public T get() {
        T arrived = value;
        if (arrived == null) {
            throw new IllegalStateException(what + ": set once " + step + ", read before it did");
        }
        return arrived;
    }

    public void ifPresent(Consumer<? super T> action) {
        T arrived = value;
        if (arrived != null) {
            action.accept(arrived);
        }
    }
}
