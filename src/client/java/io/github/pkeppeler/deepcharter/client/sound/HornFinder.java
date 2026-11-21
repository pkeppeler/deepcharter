package io.github.pkeppeler.deepcharter.client.sound;

import io.github.pkeppeler.deepcharter.sound.ambience.HornSearch;
import java.util.Optional;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import org.jspecify.annotations.Nullable;

/**
 * Keeps the answer of {@link HornSearch} for {@link #REFRESH_TICKS} ticks, since a horn does not move, and forgets it when the player
 * changes level or layer, so a horn of the layer left is never heard from the next.
 */
final class HornFinder {
    private static final int REFRESH_TICKS = 40;

    private Optional<BlockPos> found = Optional.empty();
    private long foundAt = Long.MIN_VALUE / 2;
    private @Nullable ClientLevel foundIn;
    private int foundLayer;

    /** The nearest horn to {@code from} within range, as of at most {@link #REFRESH_TICKS} ticks ago. */
    Optional<BlockPos> nearest(ClientLevel level, int layer, BlockPos from, long now, int range) {
        if (level != foundIn || layer != foundLayer) {
            clear();
            foundIn = level;
            foundLayer = layer;
        }
        if (now - foundAt >= REFRESH_TICKS) {
            found = HornSearch.nearest(level, from, range);
            foundAt = now;
        }
        return found.filter(horn -> horn.distSqr(from) <= (double) range * range);
    }

    void clear() {
        found = Optional.empty();
        foundAt = Long.MIN_VALUE / 2;
        foundIn = null;
    }
}
