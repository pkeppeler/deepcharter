package io.github.pkeppeler.deepcharter.layer;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.Dynamic2CommandExceptionType;
import io.github.pkeppeler.deepcharter.command.FeatureCommands;
import java.util.Set;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;

/** {@code /deepcharter layer ...}, op-only. */
public final class LayerCommands {
    private static final Dynamic2CommandExceptionType NO_SUCH_LAYER = new Dynamic2CommandExceptionType(
            (layer, count) -> Component.translatable("deepcharter.layer.goto.no_such_layer", layer, count));

    /** Blocks under the top of a band where a pocket is cut for a column of solid rock: clear of the roof, in the layer's rock. */
    private static final int POCKET_DEPTH = 3;

    private LayerCommands() {}

    public static void init() {
        FeatureCommands.register(
                "layer",
                layer -> layer.then(Commands.literal("goto")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .then(Commands.argument("n", IntegerArgumentType.integer(1))
                                .executes(context ->
                                        goTo(context.getSource(), IntegerArgumentType.getInteger(context, "n"))))));
    }

    /**
     * Moves the player to the surface of layer {@code n}, keeping their x and z: the top of the layer's dimension, or, in a campaign world
     * (one level, the layers as bands), the highest floor of the layer's band with room to stand, in the player's own level.
     */
    private static int goTo(CommandSourceStack source, int n) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        if (LayerChain.isCampaign(player.level())) {
            return goToBand(source, player, n);
        }
        int count = LayerChain.count(source.getServer().registryAccess());
        ServerLevel level = n > count ? null : source.getServer().getLevel(LayerChain.dimension(n));
        if (level == null) {
            throw NO_SUCH_LAYER.create(n, count);
        }
        int x = player.getBlockX();
        int z = player.getBlockZ();
        // The heightmap of an unloaded chunk reads as the layer's minY, so force the chunk first.
        level.getChunk(x >> 4, z >> 4, ChunkStatus.FULL);
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
        player.teleportTo(level, x + 0.5, y, z + 0.5, Set.of(), player.getYRot(), player.getXRot(), true);
        source.sendSuccess(() -> Component.translatable("deepcharter.layer.goto.success", n), true);
        return n;
    }

    private static int goToBand(CommandSourceStack source, ServerPlayer player, int n) throws CommandSyntaxException {
        ServerLevel level = player.level();
        int count = LayerChain.layerCount(level);
        if (n > count) {
            throw NO_SUCH_LAYER.create(n, count);
        }
        CampaignBands.Band band = CampaignBands.layer(n);
        int x = player.getBlockX();
        int z = player.getBlockZ();
        // A block of an unloaded chunk reads as air, so force the chunk first.
        level.getChunk(x >> 4, z >> 4, ChunkStatus.FULL);
        BlockPos feet = standingPlace(level, band, x, z);
        player.teleportTo(level, x + 0.5, feet.getY(), z + 0.5, Set.of(), player.getYRot(), player.getXRot(), true);
        source.sendSuccess(() -> Component.translatable("deepcharter.layer.goto.success", n), true);
        return n;
    }

    /**
     * The feet of the highest place in {@code band} at ({@code x}, {@code z}) with a floor under it and two cells of room above, clear of
     * the crust at the band's floor. A column of solid rock has none, so a pocket is cut near the band's top: sealed first, with the band's own
     * rock for its floor.
     */
    private static BlockPos standingPlace(ServerLevel level, CampaignBands.Band band, int x, int z) {
        int lowest = band.minY() + CampaignBands.crustRows();
        for (int y = band.maxY() - 1; y >= lowest; y--) {
            BlockPos feet = new BlockPos(x, y, z);
            if (hasFloor(level, feet.below()) && isRoom(level, feet) && isRoom(level, feet.above())) {
                return feet;
            }
        }
        BlockPos feet = new BlockPos(x, band.maxY() - POCKET_DEPTH, z);
        RoomSeal.seal(level, feet.below(), feet.above());
        BlockState air = Blocks.AIR.defaultBlockState();
        level.setBlock(feet, air, Block.UPDATE_CLIENTS);
        level.setBlock(feet.above(), air, Block.UPDATE_CLIENTS);
        if (!hasFloor(level, feet.below())) {
            level.setBlock(
                    feet.below(), LayerBlocks.groundOf(level, feet.getY()).defaultBlockState(), Block.UPDATE_CLIENTS);
        }
        LightSync.holdSendUntilLit(level, feet.offset(-1, -1, -1), feet.offset(1, 2, 1));
        return feet;
    }

    private static boolean hasFloor(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return state.getFluidState().isEmpty()
                && !state.getCollisionShape(level, pos).isEmpty();
    }

    private static boolean isRoom(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return state.getFluidState().isEmpty()
                && state.getCollisionShape(level, pos).isEmpty();
    }
}
