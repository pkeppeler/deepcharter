package io.github.pkeppeler.deepcharter.colony;

import io.github.pkeppeler.deepcharter.layer.LayerChain;
import io.github.pkeppeler.deepcharter.layer.LayerChain.Band;
import java.util.Optional;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * The Conduit: a Company pipe that runs from the colony's ore processor straight down through every layer, at the same X and Z
 * in each (lore canon, section 11; ADR 0016). Its casing is {@link ColonyBlocks#CONDUIT}, a block nothing can break.
 *
 * <p>It is not worldgen. When a chunk of the overworld or a layer loads and it holds part of the casing, the missing blocks
 * are set, so a chunk that generates later, or one that lost a block, gets the casing back. The casing is a square of
 * {@link ColonyTuning#conduitRadius()} blocks out from the centre column on each side. It fills the layer from its lowest
 * block to its highest, and in the overworld from the lowest block up to {@link ColonyTuning#conduitStack()} blocks above
 * the pad's ground.
 */
public final class Conduit {
    private Conduit() {}

    public static void init() {
        ServerChunkEvents.CHUNK_LOAD.register((level, chunk, newlyGenerated) -> onChunkLoad(level, chunk));
    }

    private static void onChunkLoad(ServerLevel level, LevelChunk chunk) {
        // A dimension type given inline by a data pack has no key: it is no layer. A callback never throws.
        if (level.dimensionTypeRegistration().unwrapKey().isEmpty()) {
            return;
        }
        Optional<Band> band = LayerChain.bandAt(level, level.getMinY());
        if (band.isEmpty()) {
            return;
        }
        Optional<ColonySite.Placed> placed = Colony.placed(level.getServer());
        if (placed.isEmpty()) {
            return;
        }
        place(level, chunk, placed.get(), band.get().layer());
    }

    /** Sets the missing casing blocks that lie in {@code chunk}. */
    static void place(ServerLevel level, LevelChunk chunk, ColonySite.Placed colony, int layer) {
        BlockPos centre = ColonyAnchor.CONDUIT.in(colony.anchors());
        int radius = ColonyTuning.DEFAULT.conduitRadius();
        ChunkPos chunkPos = chunk.getPos();
        int fromX = Math.max(centre.getX() - radius, chunkPos.getMinBlockX());
        int toX = Math.min(centre.getX() + radius, chunkPos.getMaxBlockX());
        int fromZ = Math.max(centre.getZ() - radius, chunkPos.getMinBlockZ());
        int toZ = Math.min(centre.getZ() + radius, chunkPos.getMaxBlockZ());
        if (fromX > toX || fromZ > toZ) {
            return;
        }
        int top =
                layer == LayerChain.SURFACE ? colony.groundY() + ColonyTuning.DEFAULT.conduitStack() : level.getMaxY();
        BlockState casing = ColonyBlocks.CONDUIT.defaultBlockState();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int x = fromX; x <= toX; x++) {
            for (int z = fromZ; z <= toZ; z++) {
                for (int y = level.getMinY(); y <= top; y++) {
                    pos.set(x, y, z);
                    if (!chunk.getBlockState(pos).is(ColonyBlocks.CONDUIT)) {
                        chunk.setBlockState(pos, casing, 0);
                    }
                }
            }
        }
    }
}
