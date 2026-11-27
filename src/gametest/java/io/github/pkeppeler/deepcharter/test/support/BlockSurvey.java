package io.github.pkeppeler.deepcharter.test.support;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

/** Scans generated chunks for a block, as the salvage tests and the gather scenario do. */
public final class BlockSurvey {
    private BlockSurvey() {}

    /** The chunks within {@code chunks} of the origin, for {@link FarChunks#whenBlocksLoaded}. */
    public static List<FarChunks.At> area(ServerLevel level, int chunks) {
        List<FarChunks.At> area = new ArrayList<>();
        for (int chunkX = -chunks; chunkX <= chunks; chunkX++) {
            for (int chunkZ = -chunks; chunkZ <= chunks; chunkZ++) {
                area.add(new FarChunks.At(level, chunkX, chunkZ));
            }
        }
        return area;
    }

    /**
     * Every position of {@code wanted} in the chunks within {@code chunks} of the origin, which must be loaded already: a server
     * GameTest loads them with {@code FarChunks.whenBlocksLoaded(helper, BlockSurvey.area(...), ...)}, so the generation does not run on the
     * server thread (#1016).
     */
    public static List<BlockPos> find(ServerLevel level, int chunks, Block wanted) {
        return find(chunks, wanted, (chunkX, chunkZ) -> {
            LevelChunk chunk = level.getChunkSource().getChunkNow(chunkX, chunkZ);
            if (chunk == null) {
                throw new IllegalStateException(
                        "chunk (" + chunkX + ", " + chunkZ + ") of "
                                + level.dimension().identifier()
                                + " is not loaded; call FarChunks.whenBlocksLoaded(helper, BlockSurvey.area(level, n), ...) first");
            }
            return chunk;
        });
    }

    /**
     * As {@link #find}, generating the chunks on the calling thread. For a scenario that has the server to itself (the evidence world's
     * client run), never for a server GameTest, whose tests share the server thread.
     */
    public static List<BlockPos> generateAndFind(ServerLevel level, int chunks, Block wanted) {
        // sync-load: for the evidence world's own client run, where no other test shares the server thread
        return find(chunks, wanted, level::getChunk);
    }

    private static List<BlockPos> find(int chunks, Block wanted, BiFunction<Integer, Integer, LevelChunk> chunkAt) {
        List<BlockPos> found = new ArrayList<>();
        Predicate<BlockState> isIt = state -> state.is(wanted);
        for (int chunkX = -chunks; chunkX <= chunks; chunkX++) {
            for (int chunkZ = -chunks; chunkZ <= chunks; chunkZ++) {
                LevelChunk chunk = chunkAt.apply(chunkX, chunkZ);
                LevelChunkSection[] sections = chunk.getSections();
                for (int index = 0; index < sections.length; index++) {
                    if (!sections[index].maybeHas(isIt)) {
                        continue;
                    }
                    int baseY = chunk.getSectionYFromSectionIndex(index) << 4;
                    for (int x = 0; x < 16; x++) {
                        for (int y = 0; y < 16; y++) {
                            for (int z = 0; z < 16; z++) {
                                if (isIt.test(sections[index].getBlockState(x, y, z))) {
                                    found.add(new BlockPos((chunkX << 4) + x, baseY + y, (chunkZ << 4) + z));
                                }
                            }
                        }
                    }
                }
            }
        }
        return found;
    }
}
