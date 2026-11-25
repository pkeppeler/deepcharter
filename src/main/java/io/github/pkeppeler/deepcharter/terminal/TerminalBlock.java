package io.github.pkeppeler.deepcharter.terminal;

import io.github.pkeppeler.deepcharter.texture.TextureProperties;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.ScheduledTickAccess;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LevelEvent;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;

/**
 * A terminal, of whichever {@link TerminalType} owns this block. Using it, with or without an item in hand, asks the server to
 * open it ({@link Terminals#open}). It cannot be broken in survival: see {@link TerminalTypes}.
 *
 * <p>{@link TextureProperties#ACTIVE} says whether its type is online, which picks the lit screen or the dark one. The default
 * state has it true only for a type that needs no repair; {@link TerminalActivity} keeps it right from then on.
 */
public final class TerminalBlock extends BaseEntityBlock {
    /** The model's cabinet (lower half) and monitor (upper half), facing north; the knobs are too small to count. */
    private static final VoxelShape CABINET = Block.box(1, 0, 1, 15, 16, 15);

    private static final VoxelShape MONITOR = Shapes.or(
            Block.box(0, 0, 2, 16, 16, 12),
            Block.box(3, 3, 12, 13, 13, 15),
            Block.box(3, 6, 1.5, 13, 14, 2),
            Block.box(4, 7, 0.5, 12, 13, 1.5));

    private static final Map<Direction, VoxelShape> CABINET_SHAPES = Shapes.rotateHorizontal(CABINET);
    private static final Map<Direction, VoxelShape> MONITOR_SHAPES = Shapes.rotateHorizontal(MONITOR);

    /** A terminal is two blocks high, like a door: the cabinet below and the monitor above. The block entity is the lower half's. */
    public static final EnumProperty<DoubleBlockHalf> HALF = BlockStateProperties.DOUBLE_BLOCK_HALF;

    TerminalBlock(Properties properties, boolean alwaysOnline) {
        super(properties);
        registerDefaultState(stateDefinition
                .any()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH)
                .setValue(TextureProperties.ACTIVE, alwaysOnline)
                .setValue(HALF, DoubleBlockHalf.LOWER));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(BlockStateProperties.HORIZONTAL_FACING, TextureProperties.ACTIVE, HALF);
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

    /** A player places it already showing whether its type is online, and only where the upper half has room. */
    @Override
    public @Nullable BlockState getStateForPlacement(BlockPlaceContext context) {
        BlockPos pos = context.getClickedPos();
        Level level = context.getLevel();
        if (pos.getY() >= level.getMaxY() || !level.getBlockState(pos.above()).canBeReplaced(context)) {
            return null;
        }
        BlockState state = defaultBlockState()
                .setValue(
                        BlockStateProperties.HORIZONTAL_FACING,
                        context.getHorizontalDirection().getOpposite());
        return level instanceof ServerLevel serverLevel
                ? TerminalActivity.corrected(serverLevel.getServer(), state)
                : state;
    }

    @Override
    public void setPlacedBy(
            Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        level.setBlock(pos.above(), state.setValue(HALF, DoubleBlockHalf.UPPER), Block.UPDATE_ALL);
    }

    /** Sets {@code lower} at {@code pos} and its upper half over it: the one way code places a terminal, as a player's item does. */
    public static void place(LevelAccessor level, BlockPos pos, BlockState lower, int flags) {
        // Neighbour shape updates would have a half see the other one missing, or an old terminal's, and remove itself.
        level.setBlock(pos, lower.setValue(HALF, DoubleBlockHalf.LOWER), flags | Block.UPDATE_KNOWN_SHAPE);
        level.setBlock(pos.above(), lower.setValue(HALF, DoubleBlockHalf.UPPER), flags | Block.UPDATE_KNOWN_SHAPE);
    }

    /** {@code pos}, or the block under it when it is the upper half of a terminal: where the block entity is. */
    public static BlockPos lowerHalf(BlockGetter level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return state.getBlock() instanceof TerminalBlock && state.getValue(HALF) == DoubleBlockHalf.UPPER
                ? pos.below()
                : pos;
    }

    /** The other half of the terminal {@code state} is at {@code pos}. */
    static BlockPos otherHalf(BlockState state, BlockPos pos) {
        return state.getValue(HALF) == DoubleBlockHalf.LOWER ? pos.above() : pos.below();
    }

    /** A half whose other half is gone goes too, as a door does. */
    @Override
    protected BlockState updateShape(
            BlockState state,
            LevelReader level,
            ScheduledTickAccess ticks,
            BlockPos pos,
            Direction direction,
            BlockPos neighborPos,
            BlockState neighborState,
            RandomSource random) {
        DoubleBlockHalf half = state.getValue(HALF);
        boolean towardOtherHalf = direction.getAxis() == Direction.Axis.Y
                && (half == DoubleBlockHalf.LOWER) == (direction == Direction.UP);
        if (towardOtherHalf && !(neighborState.is(this) && neighborState.getValue(HALF) != half)) {
            return Blocks.AIR.defaultBlockState();
        }
        return super.updateShape(state, level, ticks, pos, direction, neighborPos, neighborState, random);
    }

    /** Breaking the upper half in creative takes the lower one without the particles of a second break; the lower one's loss takes the upper by {@link #updateShape}. */
    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        if (!level.isClientSide() && player.preventsBlockDrops() && state.getValue(HALF) == DoubleBlockHalf.UPPER) {
            BlockPos lower = pos.below();
            BlockState below = level.getBlockState(lower);
            if (below.is(this) && below.getValue(HALF) == DoubleBlockHalf.LOWER) {
                level.setBlock(lower, Blocks.AIR.defaultBlockState(), Block.UPDATE_SUPPRESS_DROPS | Block.UPDATE_ALL);
                level.levelEvent(player, LevelEvent.PARTICLES_DESTROY_BLOCK, lower, Block.getId(below));
            }
        }
        return super.playerWillDestroy(level, pos, state, player);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        Map<Direction, VoxelShape> shapes =
                state.getValue(HALF) == DoubleBlockHalf.LOWER ? CABINET_SHAPES : MONITOR_SHAPES;
        return Objects.requireNonNull(
                shapes.get(state.getValue(BlockStateProperties.HORIZONTAL_FACING)), "the shapes cover every facing");
    }

    /** It is not a full cube: light and neighbouring faces follow its shape. */
    @Override
    protected boolean useShapeForLightOcclusion(BlockState state) {
        return true;
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return state.getValue(HALF) == DoubleBlockHalf.LOWER ? new TerminalBlockEntity(pos, state) : null;
    }

    /** {@link BaseEntityBlock} makes its blocks invisible, because most block entities draw themselves. This one is a model. */
    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    /**
     * The shortcut of the repair menu: using one of an unrepaired terminal's own parts on it puts the part in
     * ({@link Terminals#insert}) and opens the terminal. Any other item, or any item on a working terminal, uses the terminal
     * as an empty hand does.
     */
    @Override
    protected InteractionResult useItemOn(
            ItemStack stack,
            BlockState state,
            Level level,
            BlockPos pos,
            Player player,
            InteractionHand hand,
            BlockHitResult hit) {
        Optional<TerminalType> type =
                TerminalTypes.of(state.getBlock()).filter(owner -> owner.parts().contains(stack.getItem()));
        if (type.isEmpty()) {
            return InteractionResult.TRY_WITH_EMPTY_HAND;
        }
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return InteractionResult.SUCCESS;
        }
        RepairState repairs = RepairState.get(serverPlayer.level().getServer());
        if (!repairs.isReadable() || repairs.online(type.get())) {
            return InteractionResult.TRY_WITH_EMPTY_HAND;
        }
        if (Terminals.insert(serverPlayer, pos, stack.getItem()).isEmpty()) {
            Terminals.open(serverPlayer, pos);
        }
        return InteractionResult.SUCCESS;
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
