package io.github.pkeppeler.deepcharter.surface;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import net.fabricmc.fabric.api.event.lifecycle.v1.CommonLifecycleEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
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

    private static final LongAdder WATCHED_WRITES = new LongAdder();

    private static volatile @Nullable ChunkPos watched;

    /**
     * A pair {@link #judge} ruled on, and the tag generation it read. The heightmap predicates test tags (a hash lookup
     * each), which cost more than the lock this path skips, so verdicts are kept.
     */
    private record Verdict(int generation, BlockState was, BlockState now, boolean neutral) {}

    /** The most verdicts kept: worldgen writes a few dozen pairs, so more means a leak, and the table starts again. */
    private static final int MOST_PAIRS = 64;

    /**
     * Every pair judged so far, oldest first, shared by every worker thread and replaced whole when a pair is added. A
     * column writes a few pairs in turn (layer 1's strata, #1277) and each thread writes its own chunk's, so the one
     * last pair this used to keep missed on most writes and doubled layer 1's cost; a table read through one volatile
     * field is as cheap as that one pair on a hit.
     */
    private static volatile Verdict[] known = new Verdict[0];

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

    // test-seam: SurfaceWritesTest counts the fast writes of one chunk position, so concurrent worldgen elsewhere
    // cannot move it.
    public static void watch(@Nullable ChunkPos pos) {
        watched = pos;
        WATCHED_WRITES.reset();
    }

    // test-seam: the fast writes into the watched chunk position since watch.
    public static long watchedWrites() {
        return WATCHED_WRITES.sum();
    }

    /** What {@code chunk.setBlockState(pos, state)} returns and does, by the cheaper way when that is provably the same. */
    public static @Nullable BlockState set(ChunkAccess chunk, BlockPos pos, BlockState state) {
        LevelChunkSection section = enabled ? fastSection(chunk, pos, state) : null;
        if (section == null) {
            return chunk.setBlockState(pos, state);
        }
        ChunkPos watching = watched;
        if (watching != null && watching.equals(chunk.getPos())) {
            WATCHED_WRITES.increment();
        }
        // false: no ThreadingDetector lock (ADR 0090).
        return section.setBlockState(
                SectionPos.sectionRelative(pos.getX()),
                SectionPos.sectionRelative(pos.getY()),
                SectionPos.sectionRelative(pos.getZ()),
                state,
                false);
    }

    /**
     * The section to write {@code state} into at {@code pos}, when the fast path may, else null. It does not read the toggle, so a
     * test of the guard does not depend on it.
     */
    public static @Nullable LevelChunkSection fastSection(ChunkAccess chunk, BlockPos pos, BlockState state) {
        if (chunk.getClass() != ProtoChunk.class
                || chunk.getPersistedStatus().isOrAfter(ChunkStatus.INITIALIZE_LIGHT)) {
            return null;
        }
        int y = pos.getY();
        if (chunk.isOutsideBuildHeight(y)) {
            return null;
        }
        LevelChunkSection section = chunk.getSection(chunk.getSectionIndex(y));
        BlockState was = section.getBlockState(
                SectionPos.sectionRelative(pos.getX()),
                SectionPos.sectionRelative(y),
                SectionPos.sectionRelative(pos.getZ()));
        return neutral(was, state) ? section : null;
    }

    /** Whether writing {@code now} over {@code was} leaves every side effect of {@code ProtoChunk.setBlockState} a no-op. */
    public static boolean neutral(BlockState was, BlockState now) {
        int generation = TAGS_GENERATION.get();
        for (Verdict verdict : known) {
            if (verdict.was == was && verdict.now == now && verdict.generation == generation) {
                return verdict.neutral;
            }
        }
        boolean neutral = judge(was, now);
        // The generation read before judging: a reload in between makes this a miss on its next read.
        remember(new Verdict(generation, was, now, neutral));
        return neutral;
    }

    private static synchronized void remember(Verdict verdict) {
        Verdict[] was = known;
        Verdict[] now = was.length < MOST_PAIRS ? Arrays.copyOf(was, was.length + 1) : new Verdict[1];
        now[now.length - 1] = verdict;
        known = now;
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
