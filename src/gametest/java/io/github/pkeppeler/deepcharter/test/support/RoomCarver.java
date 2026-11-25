package io.github.pkeppeler.deepcharter.test.support;

import io.github.pkeppeler.deepcharter.layer.LightSync;
import io.github.pkeppeler.deepcharter.layer.RoomSeal;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The one way a test or scenario may open a room in the rock of a layer dimension. It seals the shell with {@link RoomSeal}
 * first and fills the box after, because worldgen leaves lava in layer rock and a room that is opened unsealed floods
 * (PR 229). The gametest generator in {@code gradle/gametest.gradle.kts} fails the build on a direct air write in a file that
 * touches a layer dimension. It also holds the sending of the room's chunks until their light is computed ({@link LightSync}), so that
 * a client does not keep the light of the rock that was there. A solid fill (a stone bed) goes through here too, so that its shell is sealed to the same depth.
 */
public final class RoomCarver {
    private static final int ARRIVAL_RADIUS = 4;
    private static final int ARRIVAL_HEIGHT = 5;
    private static final int POCKET_HEIGHT = 8;

    private RoomCarver() {}

    /** Seals the shell around the box between the corners {@code a} and {@code b} in either order, both inclusive, then fills the box with {@code fill}, notifying clients only. */
    public static void carve(ServerLevel level, BlockPos a, BlockPos b, BlockState fill) {
        carve(level, a, b, fill, Block.UPDATE_CLIENTS);
    }

    /** As {@link #carve(ServerLevel, BlockPos, BlockPos, BlockState)}, for a box given as inclusive coordinate ranges. */
    public static void carve(ServerLevel level, int x1, int x2, int y1, int y2, int z1, int z2, Block fill) {
        carve(level, new BlockPos(x1, y1, z1), new BlockPos(x2, y2, z2), fill.defaultBlockState());
    }

    /** As {@link #carve(ServerLevel, BlockPos, BlockPos, BlockState)}, with the update flags of each block written. */
    public static void carve(ServerLevel level, BlockPos a, BlockPos b, BlockState fill, int flags) {
        BlockPos min =
                new BlockPos(Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ()));
        BlockPos max =
                new BlockPos(Math.max(a.getX(), b.getX()), Math.max(a.getY(), b.getY()), Math.max(a.getZ(), b.getZ()));
        RoomSeal.seal(level, min, max);
        for (BlockPos pos : BlockPos.betweenClosed(min, max)) {
            level.setBlock(pos, fill, flags);
        }
        LightSync.holdSendUntilLit(level, min.offset(-1, -1, -1), max.offset(1, 1, 1));
    }

    /**
     * Opens a pocket in the top of the band under {@code layer}'s floor (a bed of stone with rows of air over it), for a
     * pod or player that has broken through the crust of {@code layer} to fall into. A layer is a band of one level, so a crossing carries
     * nothing and the rows under the crust are the next layer's rock.
     */
    public static void carvePocketBelow(ServerLevel level, int layer, int x1, int x2, int z1, int z2) {
        carvePocketBelow(level, layer, x1, x2, z1, z2, POCKET_HEIGHT);
    }

    /** As {@link #carvePocketBelow(ServerLevel, int, int, int, int, int)}, with {@code rows} of air over the bed. */
    public static void carvePocketBelow(ServerLevel level, int layer, int x1, int x2, int z1, int z2, int rows) {
        if (rows < 1) {
            throw new IllegalArgumentException("a pocket needs at least one row of air, not " + rows);
        }
        int floor = TestLayers.band(level, layer).minY();
        carve(level, x1, x2, floor - rows - 1, floor - rows - 1, z1, z2, Blocks.STONE);
        carve(level, x1, x2, floor - rows, floor - 1, z1, z2, Blocks.AIR);
    }

    /** The layer 2 arrival point is in lava, which burns a pod seated there; a sealed room of air around the first player keeps it out. */
    public static void carveAroundFirstPlayer(TestServerContext server) {
        server.runOnServer(minecraftServer -> {
            ServerPlayer player = minecraftServer.getPlayerList().getPlayers().getFirst();
            BlockPos feet = player.blockPosition();
            carve(
                    player.level(),
                    feet.offset(-ARRIVAL_RADIUS, 0, -ARRIVAL_RADIUS),
                    feet.offset(ARRIVAL_RADIUS, ARRIVAL_HEIGHT, ARRIVAL_RADIUS),
                    Blocks.AIR.defaultBlockState());
        });
    }
}
