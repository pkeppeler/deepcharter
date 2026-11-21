package io.github.pkeppeler.deepcharter.test.support;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/** Scans generated chunks for a block, as the salvage tests and the gather scenario do. */
public final class BlockSurvey {
    private BlockSurvey() {}

    /** Every position of {@code wanted} in the chunks within {@code chunks} of the origin, generating them. */
    public static List<BlockPos> find(ServerLevel level, int chunks, Block wanted) {
        return find(level, chunks, wanted, ChunkStatus.FULL);
    }

    /**
     * As {@link #find(ServerLevel, int, Block)}, generating each chunk only as far as {@code status}. A tall world's chunks are costly to
     * take to {@link ChunkStatus#FULL} (light, promotion to a level chunk); a survey of what the features placed needs {@link
     * ChunkStatus#FEATURES}.
     */
    public static List<BlockPos> find(ServerLevel level, int chunks, Block wanted, ChunkStatus status) {
        return Objects.requireNonNull(
                findAll(level, chunks, status, wanted).get(wanted), "findAll answers each block asked");
    }

    /** As {@link #find(ServerLevel, int, Block, ChunkStatus)} for several blocks in one pass, so each chunk is generated once. */
    public static Map<Block, List<BlockPos>> findAll(
            ServerLevel level, int chunks, ChunkStatus status, Block... wanted) {
        Map<Block, List<BlockPos>> found = new LinkedHashMap<>();
        for (Block block : wanted) {
            found.put(block, new ArrayList<>());
        }
        Predicate<BlockState> isAny = state -> found.containsKey(state.getBlock());
        for (int chunkX = -chunks; chunkX <= chunks; chunkX++) {
            for (int chunkZ = -chunks; chunkZ <= chunks; chunkZ++) {
                ChunkAccess chunk = level.getChunk(chunkX, chunkZ, status);
                LevelChunkSection[] sections = chunk.getSections();
                for (int index = 0; index < sections.length; index++) {
                    if (!sections[index].maybeHas(isAny)) {
                        continue;
                    }
                    int baseY = chunk.getSectionYFromSectionIndex(index) << 4;
                    for (int x = 0; x < 16; x++) {
                        for (int y = 0; y < 16; y++) {
                            for (int z = 0; z < 16; z++) {
                                List<BlockPos> list = found.get(
                                        sections[index].getBlockState(x, y, z).getBlock());
                                if (list != null) {
                                    list.add(new BlockPos((chunkX << 4) + x, baseY + y, (chunkZ << 4) + z));
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
