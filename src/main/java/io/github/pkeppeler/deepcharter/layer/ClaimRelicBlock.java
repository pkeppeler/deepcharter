package io.github.pkeppeler.deepcharter.layer;

import java.util.Map;
import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Something a first-generation miner left in a shaft of layer 1 (#1277, LORE section 11): a lunch pail, a prayer card.
 * It faces one of four ways; its model is drawn facing north and turned by its blockstate file, and its shape, given
 * facing north, turns with it. Placed by hand it faces the player. /
final class ClaimRelicBlock extends HorizontalDirectionalBlock {
    private final Map<Direction, VoxelShape> shapes;

    ClaimRelicBlock(Properties properties, VoxelShape north) {
        super(properties);
        this.shapes = Shapes.rotateHorizontal(north);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState()
                .setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return Objects.requireNonNull(shapes.get(state.getValue(FACING)), "the shapes cover every facing");
    }
}
