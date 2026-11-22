package io.github.pkeppeler.deepcharter.test;

import io.github.pkeppeler.deepcharter.terminal.TerminalBlock;
import io.github.pkeppeler.deepcharter.terminal.TerminalType;
import io.github.pkeppeler.deepcharter.terminal.TerminalTypes;
import io.github.pkeppeler.deepcharter.texture.TextureProperties;
import java.util.function.BiConsumer;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Server GameTests for #857: the cabinet (lower half) and the CRT monitor (upper half) of a terminal have the shape of their
 * model in every facing. Pixel positions are the model's: the monitor's casing is 10 deep from 2 to 12, and its glass stands out
 * of the front in two steps.
 */
public class TerminalShapeTest {
    private static final double PIXEL = 1.0 / 16;

    /** Every type, facing, state and half. */
    private static void forEachState(BiConsumer<BlockState, DoubleBlockHalf> body) {
        for (TerminalType type : TerminalTypes.all()) {
            for (Direction facing : Direction.Plane.HORIZONTAL) {
                for (boolean active : new boolean[] {false, true}) {
                    for (DoubleBlockHalf half : DoubleBlockHalf.values()) {
                        body.accept(
                                type.block()
                                        .defaultBlockState()
                                        .setValue(BlockStateProperties.HORIZONTAL_FACING, facing)
                                        .setValue(TextureProperties.ACTIVE, active)
                                        .setValue(TerminalBlock.HALF, half),
                                half);
                    }
                }
            }
        }
    }

    private static VoxelShape shape(GameTestHelper helper, BlockState state) {
        BlockPos at = helper.absolutePos(new BlockPos(0, 1, 0));
        return state.getShape(helper.getLevel(), at);
    }

    /** The point {@code front} pixels toward the terminal's front from the block's centre, {@code y} pixels up. */
    private static Vec3 point(Direction facing, double front, double y) {
        return new Vec3(0.5, y * PIXEL, 0.5)
                .add(facing.getStepX() * front * PIXEL, 0, facing.getStepZ() * front * PIXEL);
    }

    /** The point {@code side} pixels to the right of the terminal's front from {@link #point}. */
    private static Vec3 point(Direction facing, double front, double y, double side) {
        Direction right = facing.getClockWise();
        return point(facing, front, y).add(right.getStepX() * side * PIXEL, 0, right.getStepZ() * side * PIXEL);
    }

    private static boolean filled(VoxelShape shape, Vec3 at) {
        return Shapes.joinIsNotEmpty(shape, Shapes.create(new AABB(at, at).inflate(0.004)), BooleanOp.AND);
    }

    @GameTest
    public void theCabinetIsTheLowerHalfAndStandsInFromTheBlocksEdges(GameTestHelper helper) {
        forEachState((state, half) -> {
            if (half != DoubleBlockHalf.LOWER) {
                return;
            }
            VoxelShape shape = shape(helper, state);
            if (shape.max(Direction.Axis.Y) != 1.0 || shape.min(Direction.Axis.Y) != 0.0) {
                throw helper.assertionException("%s: the cabinet should be a block high, is %s", state, shape.bounds());
            }
            if (!filled(shape, new Vec3(0.5, 0.5, 0.5)) || filled(shape, new Vec3(0.02, 0.5, 0.5))) {
                throw helper.assertionException(
                        "%s: the cabinet should fill the middle and stand in from the sides", state);
            }
        });
        helper.succeed();
    }

    @GameTest
    public void theMonitorIsTheUpperHalfWithItsGlassOutFrontAndItsTubeBehind(GameTestHelper helper) {
        forEachState((state, half) -> {
            if (half != DoubleBlockHalf.UPPER) {
                return;
            }
            VoxelShape shape = shape(helper, state);
            Direction facing = state.getValue(BlockStateProperties.HORIZONTAL_FACING);
            // The casing is the full width, from z 2 to 12.
            if (!filled(shape, point(facing, 0, 8)) || !filled(shape, point(facing, 0, 15.5))) {
                throw helper.assertionException("%s: the casing should fill the middle of the block", state);
            }
            if (!filled(shape, point(facing, 0, 8, 7.9))) {
                throw helper.assertionException("%s: the casing should run the full width", state);
            }
            // The glass rim stands out of the front by half a pixel (to z 1.5), its middle by a whole pixel more (to
            // z 0.5).
            if (!filled(shape, point(facing, 6.25, 10)) || !filled(shape, point(facing, 7.25, 10))) {
                throw helper.assertionException(
                        "%s: the glass should stand out of the front, facing %s", state, facing);
            }
            // The tube taper is behind (z 12 to 15) and not in front: the same point mirrored.
            if (!filled(shape, point(facing, -6.5, 3.5)) || filled(shape, point(facing, 6.5, 3.5))) {
                throw helper.assertionException("%s: the tube should taper behind, facing %s", state, facing);
            }
            // Nothing stands out past the glass, and the front of the casing corner is clear of the bulge.
            if (filled(shape, point(facing, 7.9, 10)) || filled(shape, point(facing, 7, 1))) {
                throw helper.assertionException("%s: nothing should stand out past the glass", state);
            }
        });
        helper.succeed();
    }

    @GameTest
    public void aRayAtTheScreenHitsTheUpperHalfAndAtTheCabinetTheLower(GameTestHelper helper) {
        for (TerminalType type : TerminalTypes.all()) {
            BlockPos relative = new BlockPos(1, 1, 1);
            BlockState lower =
                    type.block().defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.SOUTH);
            TerminalBlock.place(helper.getLevel(), helper.absolutePos(relative), lower, 3);
            BlockPos at = helper.absolutePos(relative);
            for (int up = 0; up <= 1; up++) {
                Vec3 target = Vec3.atBottomCenterOf(at.above(up)).add(0, 0.6, 0);
                BlockHitResult hit = helper.getLevel()
                        .clip(new ClipContext(
                                target.add(0, 0, 2),
                                target,
                                ClipContext.Block.OUTLINE,
                                ClipContext.Fluid.NONE,
                                CollisionContext.empty()));
                if (hit.getType() != HitResult.Type.BLOCK || !hit.getBlockPos().equals(at.above(up))) {
                    throw helper.assertionException(
                            "a ray at the %s half of %s should hit it, hit %s",
                            up == 0 ? "lower" : "upper", type.id(), hit);
                }
            }
        }
        helper.succeed();
    }
}
