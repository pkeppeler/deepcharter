package io.github.pkeppeler.deepcharter.handbook;

import java.util.Locale;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * A sheet of paper lying in the world. Using it opens its Note to read ({@link OpenNotePayload}), and files it in the handbook
 * of the user's whole charter ({@link Notes#find}) when they are on one.
 * The block stays, so another charter can find the same Note. Which Note it is lies in the block state: the property
 * {@link #NOTE} is the Note's number (N07 is 7), so the world stores nothing more than a block. A number with no text yet shows
 * a message and files nothing. Where the blocks stand is level design (#79).
 */
public final class NoteBlock extends Block {
    public static final IntegerProperty NOTE = IntegerProperty.create("note", 1, Notes.MAX_NUMBER);
    private static final VoxelShape SHAPE = Shapes.box(0.125, 0, 0.125, 0.875, 0.0625, 0.875);

    NoteBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(NOTE, 1));
    }

    /** The state of the block for Note number {@code number}. */
    public static BlockState stateOf(int number) {
        return HandbookRegistry.NOTE.defaultBlockState().setValue(NOTE, number);
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(NOTE);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    @Override
    protected VoxelShape getCollisionShape(
            BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return Shapes.empty();
    }

    @Override
    protected InteractionResult useWithoutItem(
            BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (player instanceof ServerPlayer serverPlayer) {
            use(serverPlayer, Notes.id(state.getValue(NOTE)), payload -> OpenNotePayload.send(serverPlayer, payload));
        }
        return InteractionResult.SUCCESS;
    }

    /**
     * What using the Note {@code note} does: files it ({@link Notes#find}), hands {@code opener} the payload that opens it to read
     * when there is one ({@link OpenNotePayload#after}), and tells the player in the action bar. The block passes the real send;
     * a test passes a recorder, because a mock player cannot receive packets.
     */
    public static FindResult use(ServerPlayer player, Identifier note, Consumer<OpenNotePayload> opener) {
        FindResult result = Notes.find(player, note);
        OpenNotePayload.after(result, note).ifPresent(opener);
        player.sendSystemMessage(
                Component.translatable(
                        "deepcharter.handbook.note.use." + result.name().toLowerCase(Locale.ROOT)),
                true);
        return result;
    }
}
