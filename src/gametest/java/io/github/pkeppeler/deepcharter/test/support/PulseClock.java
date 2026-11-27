package io.github.pkeppeler.deepcharter.test.support;

import io.github.pkeppeler.deepcharter.layer.LayerTuning;
import net.minecraft.world.level.Level;

/** The grained crust's pulse (#746) as a test or a recording waits for it: by the world clock and the tuned period. */
public final class PulseClock {
    private PulseClock() {}

    /** Ticks until the next pulse, 1 to a whole period: on a pulse tick, the next pulse is a whole period away. */
    public static int ticksToPulse(Level level) {
        int period = LayerTuning.DEFAULT.grainPulseTicks();
        return period - (int) (level.getGameTime() % period);
    }
}
