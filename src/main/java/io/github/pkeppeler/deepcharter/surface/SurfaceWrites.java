package io.github.pkeppeler.deepcharter.surface;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import net.fabricmc.fabric.api.event.lifecycle.v1.CommonLifecycleEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.lighting.LightEngine;
import org.jspecify.annotations.Nullable;

/**
 * Writes a surface rule's block straight into the chunk section when {@code ProtoChunk.setBlockState}'s bookkeeping would change
 * nothing; {@code -Ddeepcharter.fastSurfaceWrites=false} turns it off. Why that is safe: [ADR 0090](../../../../../../../../../docs/adr/0090-the-surface-rule-writes-a-block-for-a-block-of-the-same-bookkeeping-straight-into-the-section.md).
 */
public final class SurfaceWrites {
    private static final boolean ENABLED_BY_DEFAULT =
            !"false".equals(System.getProperty("deepcharter.fastSurfaceWrites"));
    private static final Heightmap.Types[] HEIGHTMAP_TYPES = Heightmap.Types.values();

    private static volatile boolean enabled = ENABLED_BY_DEFAULT;

    /** Bumped when tags reload, so a verdict judged before the reload is a miss after it, whenever it is stored. */
    private static final AtomicInteger TAGS_GENERATION = new AtomicInteger();

    private static final LongAdder FAST_WRITES = new LongAdder();

    /**
     * The last pair {@link #judge} ruled on, and the tag generation it read. A column writes one pair row after row, and the
     * heightmap predicates test tags (a hash lookup each), which cost more than the lock this path skips.
     */
    private record Verdict(int generation, BlockState was, BlockState now, boolean neutral) {}

    private static volatile @Nullable Verdict last;

    private SurfaceWrites() {}

    public static void init() {
        CommonLifecycleEvents.TAGS_LOADED.register((registries, client) -> TAGS_GENERATION.incrementAndGet());
    }

    // test-seam: SurfaceWritesTest generates with the fast path off to compare, and restores the value this returns.
    public static boolean setEnabled(boolean on) {
        boolean was = enabled;
        enabled = on;
        return was;
    }

    // test-seam: SurfaceWritesTest asserts the mixin routes a real chunk's writes through the fast path.
    public static long fastWrites() {
        return FAST_WRITES.sum();
    }

    /** What {@code chunk.setBlockState(pos, state)} returns and does, by the cheaper way when that is provably the same. */
    public static @Nullable BlockState set(ChunkAccess chunk, BlockPos pos, BlockState state) {
        if (enabled
                && chunk.getClass() == ProtoChunk.class
                && !chunk.getPersistedStatus().isOrAfter(ChunkStatus.INITIALIZE_LIGHT)) {
            int y = pos.getY();
            if (!chunk.isOutsideBuildHeight(y)) {
                LevelChunkSection section = chunk.getSection(chunk.getSectionIndex(y));
                int x = SectionPos.sectionRelative(pos.getX());
                int sectionY = SectionPos.sectionRelative(y);
                int z = SectionPos.sectionRelative(pos.getZ());
                if (neutral(section.getBlockState(x, sectionY, z), state)) {
                    // false: no ThreadingDetector lock (ADR 0090).
                    FAST_WRITES.increment();
                    return section.setBlockState(x, sectionY, z, state, false);
                }
            }
        }
        return chunk.setBlockState(pos, state);
    }

    /** Whether writing {@code now} over {@code was} leaves every side effect of {@code ProtoChunk.setBlockState} a no-op. */
    public static boolean neutral(BlockState was, BlockState now) {
        int generation = TAGS_GENERATION.get();
        Verdict seen = last;
        if (seen != null && seen.generation == generation && seen.was == was && seen.now == now) {
            return seen.neutral;
        }
        boolean neutral = judge(was, now);
        // The generation read before judging: a reload in between makes this a miss on its next read.
        last = new Verdict(generation, was, now, neutral);
        return neutral;
    }

    private static boolean judge(BlockState was, BlockState now) {
        if (was.isAir()
                || now.isAir()
                || was.hasBlockEntity()
                || now.hasBlockEntity()
                || !was.getFluidState().isEmpty()
                || !now.getFluidState().isEmpty()
                || LightEngine.hasDifferentLightProperties(was, now)) {
            return false;
        }
        for (Heightmap.Types type : HEIGHTMAP_TYPES) {
            if (type.isOpaque().test(was) != type.isOpaque().test(now)) {
                return false;
            }
        }
        return true;
    }
}
