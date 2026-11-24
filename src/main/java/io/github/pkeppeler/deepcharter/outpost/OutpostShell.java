package io.github.pkeppeler.deepcharter.outpost;

import io.github.pkeppeler.deepcharter.outpost.OutpostData.Outpost;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongPredicate;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/** STUB, replaced below. */
public final class OutpostShell {
    private OutpostShell() {}

    /** What the rule reads: the blocks, and which chunks are loaded (an unloaded chunk is outside). */
    public record Terrain(BlockGetter blocks, LongPredicate chunkLoaded) {
        public static Terrain of(Level level) {
            return new Terrain(
                    level,
                    chunk -> level.getChunkSource().hasChunk(ChunkPos.getX(chunk), ChunkPos.getZ(chunk)));
        }
    }

    /** The result of a survey. */
    public static final class Shell {
        public boolean isOutside(BlockPos pos) {
            return false;
        }

        public boolean reaches(BlockPos pos) {
            return false;
        }

        public List<BlockPos> candidates() {
            return new ArrayList<>();
        }

        public int gaps(Outpost outpost) {
            return 0;
        }

        public void opened(BlockPos pos) {}

        public int reads() {
            return 0;
        }
    }

    public static Shell survey(Terrain terrain, BoundingBox zone, ShellTuning tuning, Predicate<BlockState> candidate) {
        return new Shell();
    }

    public static boolean sheltered(
            Terrain terrain, BlockPos at, Predicate<BlockState> breakable, ShellTuning tuning) {
        return false;
    }
}
