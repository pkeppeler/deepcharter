package io.github.pkeppeler.deepcharter.pod;

import io.github.pkeppeler.deepcharter.scanner.LoadedBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The void below (#1387): open space under the rock the drill bores, and what a free fall into it would cost. A braced
 * pod crosses the crust with a few hull to spare, and a cave under the crust lands it hard enough to wreck it; the
 * crust warning prices that fall ({@link PodBrace}), and the drill sounds hollow for the last rows before it breaks
 * through into a fall that lands hard. Both read the blocks under the pod's footprint, so the server and the client
 * agree from the blocks each has; an unloaded block or the bottom of the level ends the read as rock.
 */
public final class VoidBelow {
    private VoidBelow() {}

    /**
     * Hull a pod with {@code stats} loses landing after a free fall of {@code blocks} blocks from rest: the sink speed
     * {@link PodMovement} reaches over that distance, priced by {@link HardLanding#hullDamage}. 0 for a fall that lands
     * under the pod's hard landing speed.
     */
    public static float dropCost(PodStats stats, int blocks) {
        PodTuning.Movement movement = PodTuning.DEFAULT.movement();
        double sink = 0;
        double fallen = 0;
        while (fallen < blocks) {
            sink = (sink + movement.gravity()) * movement.verticalDrag();
            fallen += sink;
        }
        return HardLanding.hullDamage(stats, sink, 1f);
    }

    /**
     * Blocks the pod falls once the drill has bored the row at {@code rowY} under its footprint and stands on it no
     * longer: 1 when the row under that one is rock, up to {@link VoidBelowTuning#scanBlocks}. The pod lands on the
     * highest rock under any cell of its footprint.
     */
    static int fallAfter(PodEntity pod, int rowY) {
        Level level = pod.level();
        PodFootprint footprint = PodFootprint.of(pod);
        int scan = VoidBelowTuning.DEFAULT.scanBlocks();
        for (int fall = 1; fall < scan; fall++) {
            int y = rowY - fall;
            for (int dx = 0; dx < footprint.width(); dx++) {
                for (int dz = 0; dz < footprint.width(); dz++) {
                    if (holds(level, new BlockPos(footprint.lowX() + dx, y, footprint.lowZ() + dz))) {
                        return fall;
                    }
                }
            }
        }
        return scan;
    }

    /**
     * Whether the drill sounds hollow: within {@link VoidBelowTuning#hollowRows} rows of the pod's feet, the drill
     * would break through into a fall that lands hard. A fall that lands soft, or a gap under a nearer row, leaves the
     * drill as it is.
     */
    public static boolean hollow(PodEntity pod) {
        int feetY = PodFootprint.of(pod).feetY();
        for (int row = 1; row <= VoidBelowTuning.DEFAULT.hollowRows(); row++) {
            int rowY = feetY - row;
            int fall = fallAfter(pod, rowY);
            if (fall > 1) {
                return dropCost(PodStats.of(pod), fall) > 0f;
            }
        }
        return false;
    }

    /**
     * Whether the block at {@code pos} stops a falling pod: rock or a fluid, or a block the level cannot read, which is
     * never priced as open.
     */
    private static boolean holds(Level level, BlockPos pos) {
        if (level.isOutsideBuildHeight(pos) || !LoadedBlocks.isLoaded(level, pos)) {
            return true;
        }
        BlockState state = level.getBlockState(pos);
        return !state.getCollisionShape(level, pos).isEmpty()
                || !state.getFluidState().isEmpty();
    }
}
