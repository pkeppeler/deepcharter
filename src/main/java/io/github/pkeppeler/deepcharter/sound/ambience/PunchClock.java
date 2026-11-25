package io.github.pkeppeler.deepcharter.sound.ambience;

import java.util.Optional;
import java.util.OptionalInt;
import net.minecraft.core.BlockPos;

/** The punch clock's one decision per tick: it speaks only with a Company clock near and the world's hour known (#938). */
public final class PunchClock {
    /** A call due now and the clock it comes from. */
    public record Strike(BlockPos at, ClockCaller.Call call) {}

    private PunchClock() {}

    /**
     * Ticks {@code caller} only when {@code clock} (the nearest Company clock in range, from {@link BlockSearch}) and {@code hour}
     * ({@link ClockHour}) are both there, so its timer runs only near a clock.
     */
    public static Optional<Strike> tick(ClockCaller caller, Optional<BlockPos> clock, OptionalInt hour, boolean quiet) {
        if (clock.isEmpty() || hour.isEmpty()) {
            return Optional.empty();
        }
        return caller.tick(quiet, hour.getAsInt()).map(call -> new Strike(clock.get(), call));
    }
}
