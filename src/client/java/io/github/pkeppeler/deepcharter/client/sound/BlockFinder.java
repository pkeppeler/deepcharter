package io.github.pkeppeler.deepcharter.client.sound;

import io.github.pkeppeler.deepcharter.sound.ambience.BlockSearch;
import java.util.Optional;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import org.jspecify.annotations.Nullable;

/**
 * Keeps the answer of {@link BlockSearch} for one kind of block (a Company horn or clock) for {@link #REFRESH_TICKS} ticks, since such
 * a block does not move, and forgets it when the player changes level or layer, so a block of the layer left is never heard from the
 * next.
 */
final class BlockFinder {
    private static final int REFRESH_TICKS = 40;

    private final Block kind;
    private Optional<BlockPos> found = Optional.empty();
    private long foundAt = Long.MIN_VALUE / 2;
    private @Nullable ClientLevel foundIn;
    private int foundLayer;

    BlockFinder(Block kind) {
        this.kind = kind;
    }

    /** The nearest block to {@code from} within range, as of at most {@link #REFRESH_TICKS} ticks ago. */
    Optional<BlockPos> nearest(ClientLevel level, int layer, BlockPos from, long now, int range) {
        if (level != foundIn || layer != foundLayer) {
            clear();
            foundIn = level;
            foundLayer = layer;
        }
        if (now - foundAt >= REFRESH_TICKS) {
            found = BlockSearch.nearest(level, from, range, kind);
            foundAt = now;
        }
        return found.filter(block -> block.distSqr(from) <= (double) range * range);
    }

    void clear() {
        found = Optional.empty();
        foundAt = Long.MIN_VALUE / 2;
        foundIn = null;
    }
}
