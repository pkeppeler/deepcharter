package io.github.pkeppeler.deepcharter.terminal;

import io.github.pkeppeler.deepcharter.texture.TextureProperties;
import java.util.Map;
import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * A terminal, of whichever {@link TerminalType} owns this block. Using it, with or without an item in hand, asks the server to
 * open it ({@link Terminals#open}). It cannot be broken in survival: see {@link TerminalTypes}.
 *
 * <p>{@link TextureProperties#ACTIVE} says whether its type is online, which picks the lit screen or the dark one. The default
 * state has it true only for a type that needs no repair; {@link TerminalActivity} keeps it right from then on.
 */
public final class TerminalBlock extends BaseEntityBlock {
    /**
     * The cabinet with its CRT monitor on top, facing north (the model {@code block/terminal}): the cabinet, the monitor's
     * casing, its tube taper behind, and the two steps of the glass in front. Turned for the other facings.
     */
    private static final VoxelShape NORTH_SHAPE = Shapes.or(
            Block.box(0, 0, 0, 16, 6, 16),
            Block.box(1, 6, 3, 15, 16, 13),
            Block.box(3, 7, 13, 13, 14, 14),
            Block.box(3, 7, 2.5, 13, 15, 3),
            Block.box(4, 8, 2, 12, 14, 2.5));

    private static final Map<Direction, VoxelShape> SHAPES = Shapes.rotateHorizontal(NORTH_SHAPE);

    TerminalBlock(Properties properties, boolean alwaysOnline) {
        super(properties);
        registerDefaultState(stateDefinition
                .any()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH)
                .setValue(TextureProperties.ACTIVE, alwaysOnline));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(BlockStateProperties.HORIZONTAL_FACING, TextureProperties.ACTIVE);
    }

    /**
     * A block set in the wrong state (by the colony builder, a command, a test) is corrected on its next tick. Correcting it here,
     * inside the placement, would leave its block entity holding the state the placement set.
     */
    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
        super.onPlace(state, level, pos, oldState, movedByPiston);
        if (level instanceof ServerLevel serverLevel
                && TerminalActivity.corrected(serverLevel.getServer(), state) != state) {
            level.scheduleTick(pos, this, 1);
        }
    }

    @Override
    protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        TerminalActivity.sync(level, pos);
    }

    /** A player places it already showing whether its type is online. */
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        BlockState state = defaultBlockState()
                .setValue(
                        BlockStateProperties.HORIZONTAL_FACING,
                        context.getHorizontalDirection().getOpposite());
        return context.getLevel() instanceof ServerLevel level
                ? TerminalActivity.corrected(level.getServer(), state)
                : state;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return Objects.requireNonNull(
                SHAPES.get(state.getValue(BlockStateProperties.HORIZONTAL_FACING)), "the shapes cover every facing");
    }

    /** It is not a full cube: light and neighbouring faces follow its shape. */
    @Override
    protected boolean useShapeForLightOcclusion(BlockState state) {
        return true;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new TerminalBlockEntity(pos, state);
    }

    /** {@link BaseEntityBlock} makes its blocks invisible, because most block entities draw themselves. This one is a model. */
    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    protected InteractionResult useWithoutItem(
            BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (player instanceof ServerPlayer serverPlayer) {
            Terminals.open(serverPlayer, pos);
        }
        return InteractionResult.SUCCESS;
    }
}
