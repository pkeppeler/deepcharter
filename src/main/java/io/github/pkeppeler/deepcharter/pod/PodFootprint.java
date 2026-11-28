package io.github.pkeppeler.deepcharter.pod;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;

/**
 * The block-aligned cells a pod's box fills: the low corner of its square, its width and height in blocks, and the Y of its feet.
 * The drill bores a slab of it, and the pilot lines the rock around it.
 */
public record PodFootprint(int lowX, int lowZ, int width, int height, int feetY) {
    /** Keeps a pod resting exactly on a block boundary on the block above it. */
    static final double EPSILON = 1e-3;

    public static PodFootprint of(PodEntity pod) {
        Chassis chassis = pod.chassis();
        int width = chassis.boreWidth();
        int height = chassis.boreHeight();
        return new PodFootprint(
                Mth.floor(pod.getX() - width / 2.0 + 0.5),
                Mth.floor(pod.getZ() - width / 2.0 + 0.5),
                width,
                height,
                Mth.floor(pod.getY() + EPSILON));
    }

    /**
     * The cell an explosive's cube is centred on: the footprint's middle cell on the row the feet are in. A pod 2 wide has no middle
     * cell, so it is the one on the +x and +z side.
     */
    public BlockPos feetMiddle() {
        return new BlockPos(lowX + width / 2, feetY, lowZ + width / 2);
    }

    /**
     * The face of the bore in front of the pod along a horizontal {@code facing}: the {@code width} x {@code height}
     * cells of the next sideways slab, as {@code [low, high]} inclusive corners.
     */
    public BlockPos[] faceAhead(Direction facing) {
        boolean alongX = facing.getAxis() == Direction.Axis.X;
        int along = alongX ? lowX : lowZ;
        int front = facing.getAxisDirection() == Direction.AxisDirection.POSITIVE ? along + width : along - 1;
        return alongX
                ? new BlockPos[] {
                    new BlockPos(front, feetY, lowZ), new BlockPos(front, feetY + height - 1, lowZ + width - 1)
                }
                : new BlockPos[] {
                    new BlockPos(lowX, feetY, front), new BlockPos(lowX + width - 1, feetY + height - 1, front)
                };
    }
}
