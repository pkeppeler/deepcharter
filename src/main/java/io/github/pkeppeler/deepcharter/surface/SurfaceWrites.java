package io.github.pkeppeler.deepcharter.surface;

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
 * Writes one block of a surface rule's column straight into the chunk's section when the write is bookkeeping-neutral
 * ([ADR 0090](../../../../../../../../../docs/adr/0090-the-surface-rule-writes-a-block-for-a-block-of-the-same-bookkeeping-straight-into-the-section.md)).
 * {@code -Ddeepcharter.fastSurfaceWrites=false} turns it off.
 *
 * <p>{@code ProtoChunk.setBlockState} (26.3) does four things after it writes the section: it tells the light engine when the
 * section's only-air flag flips and when {@link LightEngine#hasDifferentLightProperties} (both only from {@code INITIALIZE_LIGHT}
 * on), and it offers the new state to every {@link Heightmap} the status keeps. {@link #neutral} is true only when all four are
 * no-ops: neither state is air (so the only-air flag cannot flip and {@code WORLD_SURFACE}'s predicate agrees), no light property
 * differs, and every heightmap type's predicate gives the same answer for both states. {@code Heightmap.update} reads only the
 * new state's predicate and, on a rescan, the chunk's blocks, so with equal predicates on a consistent heightmap it changes
 * nothing. The caller {@code MaterialSystem$1.setBlock} then marks a fluid for post-processing, so a state with a fluid is out
 * too. Neither state may have a block entity (this write never touches one, and a block entity would be a case the check was not
 * written for). The section keeps its own counts through the same {@code setBlockState} the slow path calls.
 */
public final class SurfaceWrites {
    private static final boolean ENABLED_BY_DEFAULT =
            !"false".equals(System.getProperty("deepcharter.fastSurfaceWrites"));
    private static final Heightmap.Types[] HEIGHTMAP_TYPES = Heightmap.Types.values();

    private static volatile boolean enabled = ENABLED_BY_DEFAULT;

    /**
     * The last pair {@link #neutral} judged. A column writes the same two states row after row, and the heightmap predicates
     * test tags, a hash lookup each, which cost more than the lock this path skips. A pair is two immutable states, so a race
     * between workers only loses a hit. A tag reload empties it ({@link #init}), since a predicate reads tags.
     */
    private record Verdict(BlockState was, BlockState now, boolean neutral) {}

    private static volatile @Nullable Verdict last;

    private SurfaceWrites() {}

    public static void init() {
        CommonLifecycleEvents.TAGS_LOADED.register((registries, client) -> last = null);
    }

    // test-seam: SurfaceWritesTest generates and writes with the fast path both on and off, and compares the chunks.
    public static void setEnabled(boolean on) {
        enabled = on;
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
                    // false: no ThreadingDetector lock, which only detects a second thread and never excludes one; one
                    // worker owns the chunk's sections in this step (ADR 0090).
                    return section.setBlockState(x, sectionY, z, state, false);
                }
            }
        }
        return chunk.setBlockState(pos, state);
    }

    /** Whether writing {@code now} over {@code was} leaves every side effect of {@code ProtoChunk.setBlockState} a no-op. */
    public static boolean neutral(BlockState was, BlockState now) {
        Verdict seen = last;
        if (seen != null && seen.was == was && seen.now == now) {
            return seen.neutral;
        }
        boolean neutral = judge(was, now);
        last = new Verdict(was, now, neutral);
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
