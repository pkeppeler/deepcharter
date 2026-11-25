package io.github.pkeppeler.deepcharter.sound.phonograph;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;

/**
 * A phonograph. Using it with a wax cylinder puts the cylinder in (a cylinder already in comes out into the player's hands) and plays
 * it; using it with an empty hand starts or stops the playback; sneaking with an empty hand ejects the cylinder. The block entity
 * holds the cylinder and does all of it. {@link #LOADED} only draws the cylinder on the spindle: a player's phonograph shows it while a
 * cylinder is in, the colony's while its rack plays.
 */
public final class PhonographBlock extends BaseEntityBlock {
    public static final BooleanProperty LOADED = BooleanProperty.create("loaded");
    /** The cabinet, and the mandrel and horn above it (turned to any facing, so one shape serves all four). */
    static final VoxelShape SHAPE = Shapes.or(Block.box(1, 0, 1, 15, 8, 15), Block.box(3, 8, 1, 13, 16, 15));

    private final boolean colony;

    PhonographBlock(Properties properties, boolean colony) {
        super(properties);
        this.colony = colony;
        registerDefaultState(stateDefinition
                .any()
                .setValue(HorizontalDirectionalBlock.FACING, Direction.NORTH)
                .setValue(LOADED, false));
    }

    /** True for the colony's phonograph, which holds its rack and gives nothing back. */
    boolean isColony() {
        return colony;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(HorizontalDirectionalBlock.FACING, LOADED);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState()
                .setValue(
                        HorizontalDirectionalBlock.FACING,
                        context.getHorizontalDirection().getOpposite());
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new PhonographBlockEntity(pos, state);
    }

    @Override
    public <T extends BlockEntity> @Nullable BlockEntityTicker<T> getTicker(
            Level level, BlockState state, BlockEntityType<T> type) {
        return level.isClientSide()
                ? null
                : createTickerHelper(type, PhonographRegistry.BLOCK_ENTITY, PhonographBlockEntity::tick);
    }

    @Override
    protected InteractionResult useItemOn(
            ItemStack stack,
            BlockState state,
            Level level,
            BlockPos pos,
            Player player,
            InteractionHand hand,
            BlockHitResult hit) {
        if (!stack.is(PhonographRegistry.WAX_CYLINDER)) {
            return InteractionResult.TRY_WITH_EMPTY_HAND;
        }
        if (!level.isClientSide() && level.getBlockEntity(pos) instanceof PhonographBlockEntity phonograph) {
            phonograph.insert(player, hand);
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    protected InteractionResult useWithoutItem(
            BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!level.isClientSide() && level.getBlockEntity(pos) instanceof PhonographBlockEntity phonograph) {
            if (player.isSecondaryUseActive()) {
                phonograph.eject(player);
            } else {
                phonograph.toggle();
            }
        }
        return InteractionResult.SUCCESS;
    }
}
