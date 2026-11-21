package io.github.pkeppeler.deepcharter.colony;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * An ore car on the colony's track. It still holds the last shift's ration tins: using it empties it, once, for whoever gets there
 * first, and every later use says it is empty. That the car is empty is its block state {@link #LOOTED}, so the world stores
 * nothing beyond the block, as for a Note ({@code NoteBlock}); a car's state is the chunk's and needs no version of its own. The
 * empty car is drawn without its heaped ore, so a crew sees which cars are done.
 */
public final class OreCarBlock extends KitFacingBlock {
    public static final BooleanProperty LOOTED = BooleanProperty.create("looted");
    /** The tins a full car holds. */
    static final int TINS = 2;

    OreCarBlock(Properties properties, VoxelShape north) {
        super(properties, north);
        registerDefaultState(defaultBlockState().setValue(LOOTED, false));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(LOOTED);
    }

    @Override
    protected InteractionResult useWithoutItem(
            BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (player instanceof ServerPlayer serverPlayer) {
            if (state.getValue(LOOTED)) {
                serverPlayer.sendOverlayMessage(Component.translatable("colony.deepcharter.ore_car.empty"));
            } else {
                level.setBlock(pos, state.setValue(LOOTED, true), Block.UPDATE_ALL);
                ColonyRations.give(serverPlayer, TINS);
                serverPlayer.sendOverlayMessage(Component.translatable("colony.deepcharter.ore_car.found"));
            }
        }
        return InteractionResult.SUCCESS;
    }
}
