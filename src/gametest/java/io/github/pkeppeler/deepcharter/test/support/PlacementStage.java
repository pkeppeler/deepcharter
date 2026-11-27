package io.github.pkeppeler.deepcharter.test.support;

import io.github.pkeppeler.deepcharter.layer.CampaignBands;
import io.github.pkeppeler.deepcharter.layer.LayerBlocks;
import io.github.pkeppeler.deepcharter.layer.LayerTuning;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * A stage for the placement readout (#989): a lit pocket of air cut into the rock of a layer (or of unbuilt depth), the first player in it facing a block of the layer's
 * rock that hangs three blocks east of them at eye height, so the crosshair rests on its west face and a block in hand would be placed in the air
 * west of it. Built from the layer's own geometry, so it names no world Y.
 */
public final class PlacementStage {
    private static final int X = 7600;
    private static final int Z = 7600;
    /** Blocks above the top of the layer's breach crust where the pocket's floor lies, clear of the crust's own rows. */
    private static final int ABOVE_CRUST = 10;

    private static final int REACH = 3;
    private static final double EYE = 1.62;
    private static final float EAST = -90f;

    /** The block the crosshair rests on, and the air beside it where a block in hand would go. */
    public record Spot(BlockPos aimed, BlockPos placed) {}

    private PlacementStage() {}

    /** Cuts the pocket in {@code layer}, puts the first player in it facing the aimed block with an empty hand, and returns the spot. Run on the server. */
    public static Spot build(MinecraftServer server, int layer) {
        ServerLevel level = TestLayers.level(server, layer);
        return stage(
                server,
                level,
                TestLayers.band(level, layer).minY() + LayerTuning.DEFAULT.crustThickness() + ABOVE_CRUST);
    }

    /**
     * As {@link #build} in the campaign world's unbuilt depth, the one depth that asks something today: nothing is rated there, so any block
     * in hand reads WILL NOT HOLD with a reason.
     */
    public static Spot buildUnbuilt(MinecraftServer server) {
        return stage(
                server,
                TestLayers.level(server, 1),
                CampaignBands.UNBUILT.minY() + LayerTuning.DEFAULT.crustThickness() + ABOVE_CRUST);
    }

    private static Spot stage(MinecraftServer server, ServerLevel level, int floorY) {
        BlockPos floor = new BlockPos(X, floorY, Z);
        BlockPos rockMin = floor.offset(-4, -1, -4);
        BlockPos rockMax = floor.offset(6, 4, 4);
        RoomCarver.carve(
                level,
                rockMin,
                rockMax,
                LayerBlocks.groundOf(level, floor.getY()).defaultBlockState());
        RoomCarver.carve(level, floor.offset(-3, 0, -3), floor.offset(5, 3, 3), Blocks.AIR.defaultBlockState());
        // A lamp over the pocket, so the stage reads on screen.
        level.setBlock(floor.offset(0, 3, 0), Blocks.GLOWSTONE.defaultBlockState(), 3);
        BlockPos aimed = floor.offset(REACH, 1, 0);
        level.setBlock(aimed, LayerBlocks.groundOf(level, floor.getY()).defaultBlockState(), 3);
        ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
        player.getInventory().clearContent();
        player.setPermanentlyInvulnerable(true);
        Vec3 d = Vec3.atCenterOf(aimed).subtract(Vec3.atBottomCenterOf(floor).add(0, EYE, 0));
        float pitch = (float) Math.toDegrees(Math.atan2(-d.y, Math.hypot(d.x, d.z)));
        player.teleportTo(level, floor.getX() + 0.5, floor.getY(), floor.getZ() + 0.5, Set.of(), EAST, pitch, true);
        return new Spot(aimed, aimed.west());
    }

    /** Puts {@code stack} in the first player's hand. Run on the server. */
    public static void hold(MinecraftServer server, ItemStack stack) {
        ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
        player.getInventory().setItem(player.getInventory().getSelectedSlot(), stack);
    }
}
