package io.github.pkeppeler.deepcharter.sound.ambience;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.random.RandomGenerator;
import org.jspecify.annotations.Nullable;

/**
 * When the punch clock calls an hour, and which (#938): a pure decision, with no client in it. It is told whether something else is
 * sounding and the world's hour on a twelve-hour dial, on ticks when a Company clock is near.
 *
 * <ul>
 *   <li>Rare: the gap is drawn between {@link ClockTuning#gapMinTicks()} and {@link ClockTuning#gapMaxTicks()}. The timer runs only on
 *       ticks near a clock and is kept when the player leaves, so a visitor passing through hears nothing, one who lingers hears about
 *       one call an hour, and walking out and back does not draw it again.
 *   <li>Right, wrong or skipped: a call is the world's own hour, another hour, or the hour before and the hour after with the world's
 *       hour missing, as Lambert's recording skips ten.
 *   <li>Never twice in a row: a call is never the same hours as the one before, whatever the world's hour.
 *   <li>Something sounding (a hymn, a phonograph, a transmission, a fragment, a call) holds a due call back for a drawn short time.
 * </ul>
 */
public final class ClockCaller {
    /** How a call relates to the world's hour. */
    public enum Kind {
        RIGHT,
        WRONG,
        SKIPPED
    }

    /** One chime of a call: the hour it calls, and how many ticks after the call starts. */
    public record Chime(int hour, int delayTicks) {}

    /** A call that is due now. */
    public record Call(Kind kind, List<Chime> chimes) {
        /** The hours it strikes, in order. */
        public List<Integer> hours() {
            return chimes.stream().map(Chime::hour).toList();
        }
    }

    private static final int HOURS = 12;
    // Lambert skips ten, so the recording has no word for it: the clock never says it, and at ten it skips as he does.
    private static final int UNSPOKEN_HOUR = 10;
    private static final int UNSET = -1;

    private final ClockTuning tuning;
    private final RandomGenerator random;
    private int ticksLeft = UNSET;
    private @Nullable List<Integer> last;

    public ClockCaller(ClockTuning tuning, RandomGenerator random) {
        this.tuning = tuning;
        this.random = random;
    }

    /** Forgets the timer, for a player who has left the world. The last call is kept: it still must not repeat. */
    public void reset() {
        ticksLeft = UNSET;
    }

    /** The hour {@code offset} hours from {@code hour} on a twelve-hour dial, 1 to 12. */
    public static int dial(int hour, int offset) {
        return Math.floorMod(hour - 1 + offset, HOURS) + 1;
    }

    /** The world's hour on a twelve-hour dial, 1 to 12, from the ticks since the sky's dawn (0 is six in the morning). */
    public static int worldHour(long dayTicks, long periodTicks) {
        return dial((int) ((dayTicks * 24 / periodTicks + 6) % 24), 0);
    }

    /**
     * One client tick near a clock ({@link PunchClock} asks only then). Returns the call that is due, if one is and nothing holds it back.
     *
     * @param quiet     whether something else is sounding
     * @param worldHour the world's hour on the dial, 1 to 12
     */
    public Optional<Call> tick(boolean quiet, int worldHour) {
        if (ticksLeft == UNSET) {
            ticksLeft = draw(tuning.firstMinTicks(), tuning.gapMaxTicks());
        }
        if (--ticksLeft > 0) {
            return Optional.empty();
        }
        if (quiet) {
            ticksLeft = draw(tuning.retryMinTicks(), tuning.retryMaxTicks());
            return Optional.empty();
        }
        Call call = choose(worldHour);
        last = call.hours();
        ticksLeft = draw(tuning.gapMinTicks(), tuning.gapMaxTicks());
        return Optional.of(call);
    }

    private Call choose(int worldHour) {
        int roll = random.nextInt(100);
        Kind kind = roll < tuning.rightPercent()
                ? Kind.RIGHT
                : roll < tuning.rightPercent() + tuning.skippedPercent() ? Kind.SKIPPED : Kind.WRONG;
        if (kind == Kind.RIGHT && worldHour == UNSPOKEN_HOUR) {
            kind = Kind.SKIPPED;
        }
        List<List<Integer>> options = options(kind, worldHour);
        if (options.isEmpty()) {
            kind = Kind.WRONG;
            options = options(kind, worldHour);
        }
        List<Integer> hours = options.get(random.nextInt(options.size()));
        List<Chime> chimes = new ArrayList<>();
        int delay = 0;
        for (int hour : hours) {
            chimes.add(new Chime(hour, delay));
            delay += tuning.wordTicks() + tuning.pauseTicks();
        }
        return new Call(kind, chimes);
    }

    /** Every call of a kind that is not the last one and does not strike the unspoken hour. A wrong call has ten others, so it is never empty. */
    private List<List<Integer>> options(Kind kind, int worldHour) {
        List<List<Integer>> all = new ArrayList<>();
        switch (kind) {
            case RIGHT -> all.add(List.of(worldHour));
            case SKIPPED -> all.add(List.of(dial(worldHour, -1), dial(worldHour, 1)));
            case WRONG -> {
                for (int hour = 1; hour <= HOURS; hour++) {
                    if (hour != worldHour) {
                        all.add(List.of(hour));
                    }
                }
            }
        }
        all.removeIf(hours -> hours.equals(last) || hours.contains(UNSPOKEN_HOUR));
        return all;
    }

    /** A whole number of ticks from {@code min} to {@code max}, both included. */
    private int draw(int min, int max) {
        return min + random.nextInt(max - min + 1);
    }
}
