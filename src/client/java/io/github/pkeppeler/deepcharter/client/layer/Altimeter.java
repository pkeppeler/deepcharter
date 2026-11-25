package io.github.pkeppeler.deepcharter.client.layer;

import io.github.pkeppeler.deepcharter.layer.Depth;
import java.util.Locale;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;

/** The depth readout, for example "-1,234 ft.". Below sea level is negative. */
public final class Altimeter {
    private Altimeter() {}

    /** The reading for a player at block height {@code blockY} in {@code level}. */
    public static Component reading(Level level, int blockY) {
        return Component.translatable("deepcharter.breach.altimeter", feet(level, blockY));
    }

    /** The number of the reading, for example "-1,234": the scanner's depth ruler labels its rows with it. */
    public static String feet(Level level, int blockY) {
        int feet = Depth.feet(Depth.of(level, blockY));
        // Locale.US so the thousands separator is the same on every machine.
        return String.format(Locale.US, "%,d", -feet);
    }
}
