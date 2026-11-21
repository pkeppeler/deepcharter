package io.github.pkeppeler.deepcharter.client.sound;

import io.github.pkeppeler.deepcharter.sound.ambience.AmbienceRegistry;
import io.github.pkeppeler.deepcharter.sound.ambience.AmbienceTuning;
import java.util.Optional;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.jspecify.annotations.Nullable;

/**
 * Finds the Company horn nearest a player, within {@link AmbienceTuning#hornRange()} blocks, in the chunks the client has loaded. A horn is
 * a block and not a block entity, so the client has nothing to ask: this reads each nearby chunk section's palette first, and only a section
 * that holds a horn is walked. The answer is kept {@link #REFRESH_TICKS} ticks, since a horn does not move.
 */
final class HornFinder {
    private static final int REFRESH_TICKS = 40;

    private Optional<BlockPos> found = Optional.empty();
    private long foundAt = Long.MIN_VALUE / 2;

    /** The nearest horn to {@code from}, if one is within range, as of at most {@link #REFRESH_TICKS} ticks ago. */
    Optional<BlockPos> nearest(ClientLevel level, BlockPos from, long now) {
        if (now - foundAt >= REFRESH_TICKS) {
            found = search(level, from);
            foundAt = now;
        }
        double range = AmbienceTuning.DEFAULT.hornRange();
        return found.filter(horn -> horn.distSqr(from) <= range * range);
    }

    void clear() {
        found = Optional.empty();
        foundAt = Long.MIN_VALUE / 2;
    }

    private static Optional<BlockPos> search(ClientLevel level, BlockPos from) {
        int range = AmbienceTuning.DEFAULT.hornRange();
        @Nullable BlockPos nearest = null;
        double nearestDistance = Double.MAX_VALUE;
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        for (int chunkX = (from.getX() - range) >> 4; chunkX <= (from.getX() + range) >> 4; chunkX++) {
            for (int chunkZ = (from.getZ() - range) >> 4; chunkZ <= (from.getZ() + range) >> 4; chunkZ++) {
                ChunkAccess chunk = level.getChunkSource().getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);
                if (chunk == null) {
                    continue;
                }
                LevelChunkSection[] sections = chunk.getSections();
                for (int i = 0; i < sections.length; i++) {
                    if (!sections[i].maybeHas(state -> state.is(AmbienceRegistry.COMPANY_HORN))) {
                        continue;
                    }
                    int baseY = chunk.getSectionYFromSectionIndex(i) << 4;
                    for (int x = 0; x < 16; x++) {
                        for (int y = 0; y < 16; y++) {
                            for (int z = 0; z < 16; z++) {
                                if (sections[i].getBlockState(x, y, z).is(AmbienceRegistry.COMPANY_HORN)) {
                                    at.set((chunkX << 4) + x, baseY + y, (chunkZ << 4) + z);
                                    double distance = at.distSqr(from);
                                    if (distance < nearestDistance) {
                                        nearestDistance = distance;
                                        nearest = at.immutable();
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        return Optional.ofNullable(nearest);
    }
}
