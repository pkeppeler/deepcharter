package io.github.pkeppeler.deepcharter.sound.ambience;

import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.jspecify.annotations.Nullable;

/**
 * Finds the Company horn nearest a place, within a range, in the chunks a level has loaded. A horn is a block and not a block entity, so
 * there is nothing to ask: this reads each nearby chunk section's palette first, and only a section that holds a horn is walked. It takes
 * a {@link LevelReader}, so the client's level and a server's level answer alike. A chunk that is not loaded holds no horn.
 */
public final class HornSearch {
    private HornSearch() {}

    /** The nearest horn to {@code from} that is within {@code range} blocks, or empty. */
    public static Optional<BlockPos> nearest(LevelReader level, BlockPos from, int range) {
        @Nullable BlockPos nearest = null;
        double nearestDistance = (double) range * range;
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        for (int chunkX = (from.getX() - range) >> 4; chunkX <= (from.getX() + range) >> 4; chunkX++) {
            for (int chunkZ = (from.getZ() - range) >> 4; chunkZ <= (from.getZ() + range) >> 4; chunkZ++) {
                ChunkAccess chunk = level.getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);
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
                                if (!sections[i].getBlockState(x, y, z).is(AmbienceRegistry.COMPANY_HORN)) {
                                    continue;
                                }
                                at.set((chunkX << 4) + x, baseY + y, (chunkZ << 4) + z);
                                double distance = at.distSqr(from);
                                if (distance <= nearestDistance) {
                                    nearestDistance = distance;
                                    nearest = at.immutable();
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
