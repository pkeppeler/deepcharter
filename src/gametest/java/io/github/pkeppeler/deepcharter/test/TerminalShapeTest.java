package io.github.pkeppeler.deepcharter.test;

import io.github.pkeppeler.deepcharter.terminal.TerminalType;
import io.github.pkeppeler.deepcharter.terminal.TerminalTypes;
import io.github.pkeppeler.deepcharter.texture.TextureProperties;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Server GameTests for #857: a terminal is a cabinet with a CRT monitor on top ({@code models/block/terminal.json}), not a full
 * cube, and its collision and outline shape follow the model in every facing. The pixel positions below are the model's: the
 * cabinet is 6 high, the monitor's casing runs to the top, and the glass stands out of its front by half a pixel and then a whole pixel more.
 */
public class TerminalShapeTest {
    private static final double PIXEL = 1.0 / 16;

    /** A terminal of every type, in every facing, in both states. */
    private static void forEachState(Consumer<BlockState> body) {
        for (TerminalType type : TerminalTypes.all()) {
            for (Direction facing : Direction.Plane.HORIZONTAL) {
                for (boolean active : new boolean[] {false, true}) {
                    body.accept(type.block()
                            .defaultBlockState()
                            .setValue(BlockStateProperties.HORIZONTAL_FACING, facing)
                            .setValue(TextureProperties.ACTIVE, active));
                }
            }
        }
    }

    private static VoxelShape shape(GameTestHelper helper, BlockState state) {
        helper.setBlock(BlockPos.ZERO.above(), state);
        return helper.getLevel()
                .getBlockState(helper.absolutePos(BlockPos.ZERO.above()))
                .getShape(helper.getLevel(), helper.absolutePos(BlockPos.ZERO.above()));
    }

    /** The point {@code front} pixels toward the terminal's front from the block's centre, {@code y} pixels up. */
    private static Vec3 point(Direction facing, double front, double y) {
        return new Vec3(0.5, y * PIXEL, 0.5)
                .add(facing.getStepX() * front * PIXEL, 0, facing.getStepZ() * front * PIXEL);
    }

    private static boolean filled(VoxelShape shape, Vec3 at) {
        AABB probe = new AABB(at, at).inflate(0.004);
        return Shapes.joinIsNotEmpty(shape, Shapes.create(probe), BooleanOp.AND);
    }

    @GameTest
    public void noTerminalIsAFullCube(GameTestHelper helper) {
        forEachState(state -> {
            VoxelShape shape = shape(helper, state);
            if (!Shapes.joinIsNotEmpty(Shapes.block(), shape, BooleanOp.ONLY_FIRST)) {
                throw helper.assertionException("%s is still a full cube", state);
            }
            if (shape.max(Direction.Axis.Y) != 1.0 || shape.min(Direction.Axis.Y) != 0.0) {
                throw helper.assertionException("%s should span the block's height, spans %s", state, shape.bounds());
            }
        });
        helper.succeed();
    }

    @GameTest
    public void theCabinetFillsTheFootprintAndTheMonitorSitsBackFromTheEdges(GameTestHelper helper) {
        AABB cabinet = new AABB(0, 0, 0, 1, 6 * PIXEL, 1);
        forEachState(state -> {
            VoxelShape shape = shape(helper, state);
            if (Shapes.joinIsNotEmpty(Shapes.create(cabinet), shape, BooleanOp.ONLY_FIRST)) {
                throw helper.assertionException("the cabinet of %s should fill its whole footprint", state);
            }
            Direction facing = state.getValue(BlockStateProperties.HORIZONTAL_FACING);
            if (!filled(shape, point(facing, 0, 12))) {
                throw helper.assertionException("the monitor of %s should fill the middle of the block", state);
            }
            // Above the cabinet, past the monitor's side: free to walk against, and nothing is there.
            if (filled(shape, new Vec3(0.02, 12 * PIXEL, 0.5))) {
                throw helper.assertionException("the monitor of %s should stand in from the block's sides", state);
            }
        });
        helper.succeed();
    }

    @GameTest
    public void theGlassStandsOutOfTheFrontAndTheTubeTapersBehind(GameTestHelper helper) {
        forEachState(state -> {
            VoxelShape shape = shape(helper, state);
            Direction facing = state.getValue(BlockStateProperties.HORIZONTAL_FACING);
            // The middle of the glass stands a whole pixel proud of its rim: 1.7 pixels from the casing front.
            if (!filled(shape, point(facing, 6.3, 12))) {
                throw helper.assertionException(
                        "%s: the middle of the glass should stand 1 pixel proud of its rim", state);
            }
            // y 14.5: the glass (to 15) is there in front, the tube taper (to 14) is not behind.
            if (!filled(shape, point(facing, 5.7, 14.5))) {
                throw helper.assertionException(
                        "%s: the glass should stand out of the front, facing %s", state, facing);
            }
            if (filled(shape, point(facing, -5.7, 14.5))) {
                throw helper.assertionException(
                        "%s: the glass is on the wrong side, or the tube is too tall, facing %s", state, facing);
            }
            if (filled(shape, point(facing, 7, 12))) {
                throw helper.assertionException("%s: nothing stands more than 2.5 pixels out of the monitor", state);
            }
        });
        helper.succeed();
    }

    @GameTest
    public void aRayAtTheScreenHitsTheTerminal(GameTestHelper helper) {
        for (TerminalType type : TerminalTypes.all()) {
            BlockState state =
                    type.block().defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.SOUTH);
            BlockPos relative = new BlockPos(1, 1, 1);
            helper.setBlock(relative, state);
            BlockPos at = helper.absolutePos(relative);
            Vec3 screen = Vec3.atBottomCenterOf(at).add(0, 11 * PIXEL, 0);
            Vec3 from = screen.add(0, 0, 2);
            BlockHitResult hit = helper.getLevel()
                    .clip(new ClipContext(
                            from, screen, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, CollisionContext.empty()));
            if (hit.getType() != HitResult.Type.BLOCK || !hit.getBlockPos().equals(at)) {
                throw helper.assertionException("a ray at the screen of %s should hit it, hit %s", type.id(), hit);
            }
        }
        helper.succeed();
    }
}
