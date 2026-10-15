package io.github.pkeppeler.deepcharter.ore;

import io.github.pkeppeler.deepcharter.layer.LayerBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.IntegerProperty;

/**
 * Company rock (ADR 0056). Two states change how it looks and nothing else about it:
 *
 * <ul>
 *   <li>{@link OreBlock#HOST}, the rock of the layer it sits in, as an ore has (ADR 0055): worldgen sets it, and in layer 2 the joints glow like lava.
 *   <li>{@link #CRACK}, how far a charge has widened the joints. A blasting charge raises it twice before the blast clears the rock ({@code PodCharge}),
 *       so the rock is a state a pilot can read and not a block entity or a ticking block. The widened joints close again if the blast never comes.
 * </ul>
 */
public final class CompanyRockBlock extends Block {
    /** The most a blast widens the joints; 0 is the rock as worldgen left it. */
    public static final int MAX_CRACK = 2;

    public static final IntegerProperty CRACK = IntegerProperty.create("crack", 0, MAX_CRACK);

    public CompanyRockBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition
                .any()
                .setValue(OreBlock.HOST, OreBlock.Host.PACKED_SHALE)
                .setValue(CRACK, 0));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(OreBlock.HOST, CRACK);
    }

    /** This rock as it sits in {@code rock}, the block it replaces. */
    public BlockState in(BlockState rock) {
        return defaultBlockState().setValue(OreBlock.HOST, OreBlock.Host.of(rock));
    }

    /** As for an ore (ADR 0055): a placed block takes the rock of its layer, and a level that is no layer keeps the default host. */
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        Block ground = LayerBlocks.groundOf(context.getLevel());
        return ground == Blocks.STONE ? defaultBlockState() : in(ground.defaultBlockState());
    }

    /**
     * Widens the joints of the Company rock at {@code pos} to {@code stage} and sets a tick {@code closeAfterTicks} later that closes them if the rock is
     * still there (a blast that never came). Does nothing for a block that is not Company rock, or already at this stage or beyond.
     */
    public static void widen(ServerLevel level, BlockPos pos, int stage, int closeAfterTicks) {
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof CompanyRockBlock rock) || state.getValue(CRACK) >= stage) {
            return;
        }
        level.setBlock(pos, state.setValue(CRACK, stage), Block.UPDATE_CLIENTS);
        // scheduleTick ignores a pos that already has a tick, so an overlapping charge's crack may close early: a
        // flicker only (ADR 0056).
        level.scheduleTick(pos, rock, closeAfterTicks);
    }

    @Override
    protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        if (state.getValue(CRACK) > 0) {
            level.setBlock(pos, state.setValue(CRACK, 0), Block.UPDATE_CLIENTS);
        }
    }
}
