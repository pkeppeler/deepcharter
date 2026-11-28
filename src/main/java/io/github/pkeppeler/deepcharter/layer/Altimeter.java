package io.github.pkeppeler.deepcharter.layer;

import java.util.Locale;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;

/**
 * The depth readout, for example "-1,234 m": the HUD's altimeter and the Continuity Event's chat line (#1337).
 * Below sea level is negative. A block is a metre (#1604).
 */
public final class Altimeter {
    private Altimeter() {}

    /** The reading for a player at block height {@code blockY} in {@code level}. */
    public static Component reading(Level level, int blockY) {
        return Component.translatable("deepcharter.breach.altimeter", readingNumber(level, blockY));
    }

    /** The number of the reading, for example "-1,234": the scanner's depth ruler labels its rows with it. */
    public static String readingNumber(Level level, int blockY) {
        return number(Depth.of(level, blockY));
    }

    /** The reading's number for {@code depth} blocks below sea level, which is that many metres. */
    public static String number(int depth) {
        // Locale.US so the thousands separator is the same on every machine.
        return String.format(Locale.US, "%,d", -depth);
    }
}
