package io.github.pkeppeler.deepcharter.test.support;

import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * A stage for the block readout (#859), built from where the first player stands, so it names no world position: a stone platform in the sky above
 * them, open air round it, and one spot three blocks east of the player at eye height where a block is set, with the player turned to face it.
 */
public final class BlockReadoutStage {
    /** How far above the player's feet the platform's floor lies, clear of the colony's tallest roofs. */
    private static final int RISE = 40;

    private static final int HALF = 5;
    private static final int REACH = 3;
    private static final double EYE = 1.62;
    private static final float EAST = -90f;

    private BlockReadoutStage() {}

    /** Builds the platform, stands the first player on it with an empty hand and daylight, and returns the spot. Run on the server. */
    public static BlockPos build(MinecraftServer server) {
        ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
        ServerLevel level = server.overworld();
        BlockPos stand = player.blockPosition().above(RISE);
        for (int dx = -HALF; dx <= HALF; dx++) {
            for (int dz = -HALF; dz <= HALF; dz++) {
                level.setBlock(stand.offset(dx, -1, dz), Blocks.STONE.defaultBlockState(), 3);
                for (int dy = 0; dy <= 3; dy++) {
                    level.setBlock(stand.offset(dx, dy, dz), Blocks.AIR.defaultBlockState(), 3);
                }
            }
        }
        player.getInventory().clearContent();
        player.setPermanentlyInvulnerable(true);
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "time set noon");
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "weather clear");
        BlockPos spot = stand.offset(REACH, 1, 0);
        face(player, level, stand, spot);
        return spot;
    }

    /** Sets {@code state} at {@code spot}. Run on the server. */
    public static void place(MinecraftServer server, BlockPos spot, BlockState state) {
        server.overworld().setBlock(spot, state, 3);
    }

    private static void face(ServerPlayer player, ServerLevel level, BlockPos stand, BlockPos spot) {
        Vec3 feet = Vec3.atBottomCenterOf(stand);
        Vec3 d = Vec3.atCenterOf(spot).subtract(feet.add(0, EYE, 0));
        float pitch = (float) Math.toDegrees(Math.atan2(-d.y, Math.hypot(d.x, d.z)));
        player.teleportTo(level, feet.x, feet.y, feet.z, Set.of(), EAST, pitch, true);
    }
}
