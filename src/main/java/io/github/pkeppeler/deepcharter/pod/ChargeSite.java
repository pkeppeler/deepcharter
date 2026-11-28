package io.github.pkeppeler.deepcharter.pod;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Where a charge blasts, whatever set it (#1613): the box of cells it may clear (inclusive corners) and the way it
  * bores,
 * {@link Direction#DOWN} or one of the four horizontal directions. The blast is computed from this alone, never from
  * a pod
 * at rest, so a charge dropped from a flying pod or set by hand on foot is another way to build a site, not another
  * blast.
 * The face is the layer of cells nearest the setter, where a charge starts; the runs of a shaped charge go from it
  * along
 * {@link #facing}, and the box is {@link #depth} layers deep.
 */
public record ChargeSite(BlockPos low, BlockPos high, Direction facing) {
    public ChargeSite {
        if (facing == Direction.UP) {
            throw new IllegalArgumentException("a charge bores down or sideways, never up");
        }
    }

    /** The site whose face is the box {@code faceLow} to {@code faceHigh} (inclusive corners, one layer thick) and
     * that is {@code depth} layers deep along {@code facing}. */
    public static ChargeSite ahead(BlockPos faceLow, BlockPos faceHigh, Direction facing, int depth) {
        BlockPos far = new BlockPos(
                faceLow.getX() + facing.getStepX() * (depth - 1),
                faceLow.getY() + facing.getStepY() * (depth - 1),
                faceLow.getZ() + facing.getStepZ() * (depth - 1));
        BlockPos farHigh = new BlockPos(
                faceHigh.getX() + facing.getStepX() * (depth - 1),
                faceHigh.getY() + facing.getStepY() * (depth - 1),
                faceHigh.getZ() + facing.getStepZ() * (depth - 1));
        return new ChargeSite(
                new BlockPos(
                        Math.min(faceLow.getX(), far.getX()),
                        Math.min(faceLow.getY(), far.getY()),
                        Math.min(faceLow.getZ(), far.getZ())),
                new BlockPos(
                        Math.max(faceHigh.getX(), farHigh.getX()),
                        Math.max(faceHigh.getY(), farHigh.getY()),
                        Math.max(faceHigh.getZ(), farHigh.getZ())),
                facing);
    }

    /** Layers of cells along the facing: the rows of a downward blast, the slabs of a sideways one. */
    public int depth() {
        return facing.getAxis().choose(high.getX() - low.getX(), high.getY() - low.getY(), high.getZ() - low.getZ())
                + 1;
    }

    /** The cells of the face, the layer nearest the setter, each one the start of a run along the facing. */
    public List<BlockPos> faceCells() {
        Direction back = facing.getOpposite();
        boolean positive = facing.getAxisDirection() == Direction.AxisDirection.POSITIVE;
        BlockPos start = positive ? low : low.relative(back, depth() - 1);
        BlockPos end = positive ? high.relative(back, depth() - 1) : high;
        List<BlockPos> cells = new ArrayList<>();
        for (BlockPos cell : BlockPos.betweenClosed(start, end)) {
            cells.add(cell.immutable());
        }
        return cells;
    }

    public AABB volume() {
        return new AABB(low.getX(), low.getY(), low.getZ(), high.getX() + 1, high.getY() + 1, high.getZ() + 1);
    }

    /** Where the fuse sparks: the middle of the face, a little back toward the setter. */
    Vec3 sparkPoint() {
        AABB volume = volume();
        Vec3 middle = volume.getCenter();
        boolean positive = facing.getAxisDirection() == Direction.AxisDirection.POSITIVE;
        double plane = facing.getAxis()
                .choose(
                        positive ? volume.minX : volume.maxX,
                        positive ? volume.minY : volume.maxY,
                        positive ? volume.minZ : volume.maxZ);
        return new Vec3(
                facing.getAxis() == Direction.Axis.X ? plane : middle.x,
                facing.getAxis() == Direction.Axis.Y ? plane : middle.y,
                facing.getAxis() == Direction.Axis.Z ? plane : middle.z);
    }
}
