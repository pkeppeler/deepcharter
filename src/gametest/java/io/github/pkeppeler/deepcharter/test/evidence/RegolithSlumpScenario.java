package io.github.pkeppeler.deepcharter.test.evidence;

import io.github.pkeppeler.deepcharter.layer.LayerBlocks;
import io.github.pkeppeler.deepcharter.layer.LayerTuning;
import io.github.pkeppeler.deepcharter.surface.SurfaceBlocks;
import io.github.pkeppeler.deepcharter.test.support.RoomCarver;
import io.github.pkeppeler.deepcharter.test.support.TestLayers;
import java.util.Set;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Evidence scenario "regolith-slump" (#836): a skin of loose regolith over packed regolith, in a lit room. The packed layer under the
 * skin is cut away a row at a time, as a shallow bore would, and the skin falls like sand into the cut. Stills: the skin standing,
 * the cut half made, and the skin down.
 */
public class RegolithSlumpScenario extends EvidenceScenario {
    private static final int X = 4000;
    private static final int Z = 4000;
    private static final int FLOOR_Y = LayerTuning.DEFAULT.crustThickness() + 1;
    /** Half the side of the cut block, in blocks. */
    private static final int HALF = 1;

    private static final int PACKED_HEIGHT = 3;
    private static final int TICKS_PER_FRAME = 4;
    private static final int FRAMES_PER_STEP = 6;
    private static final float LOOK_PITCH = 12f;
    private static final double VIEW_DISTANCE = 5.5;

    @Override
    protected String name() {
        return "regolith-slump";
    }

    @Override
    protected void run(ClientGameTestContext context) {
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            context.waitTicks(40);
            singleplayer.getServer().runOnServer(server -> {
                ServerLevel one = TestLayers.level(server, 1);
                buildRoom(one);
                ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
                player.setGameMode(GameType.SPECTATOR);
                player.teleportTo(one, X + 0.5, FLOOR_Y + 2, Z - VIEW_DISTANCE, Set.of(), 0, LOOK_PITCH, true);
            });
            awaitWorld(context);
            context.waitTicks(40);
            screenshot(context, "regolith-slump-before");
            frame(context);
            for (int row = -HALF; row <= HALF; row++) {
                int z = Z + row;
                singleplayer.getServer().runOnServer(server -> {
                    ServerLevel one = TestLayers.level(server, 1);
                    for (int x = X - HALF; x <= X + HALF; x++) {
                        one.destroyBlock(new BlockPos(x, FLOOR_Y + PACKED_HEIGHT - 1, z), false);
                    }
                });
                for (int i = 0; i < FRAMES_PER_STEP; i++) {
                    context.waitTicks(TICKS_PER_FRAME);
                    frame(context);
                }
                if (row == -HALF) {
                    screenshot(context, "regolith-slump-cut");
                }
            }
            context.waitTicks(20);
            screenshot(context, "regolith-slump-after");
            frame(context);
        }
    }

    /** A sealed lit room on a stone floor, with a block of packed regolith under a skin of loose regolith in its middle. */
    private static void buildRoom(ServerLevel level) {
        RoomCarver.carve(level, X - 5, X + 5, 0, 2, Z - 5, Z + 5, LayerBlocks.BREACH_CRUST);
        RoomCarver.carve(level, X - 5, X + 5, 3, FLOOR_Y - 1, Z - 5, Z + 5, Blocks.STONE);
        // Worldgen scatters lava through layer 1's rock, and lava beside the room would flow in, so carve it sealed.
        RoomCarver.carve(
                level,
                new BlockPos(X - 5, FLOOR_Y, Z - 5),
                new BlockPos(X + 5, FLOOR_Y + 9, Z + 5),
                Blocks.AIR.defaultBlockState(),
                Block.UPDATE_ALL);
        BlockState packed = SurfaceBlocks.REGOLITH_PACKED.defaultBlockState();
        BlockState skin = SurfaceBlocks.REGOLITH.defaultBlockState();
        for (int x = X - HALF; x <= X + HALF; x++) {
            for (int z = Z - HALF; z <= Z + HALF; z++) {
                for (int y = FLOOR_Y; y < FLOOR_Y + PACKED_HEIGHT; y++) {
                    level.setBlock(new BlockPos(x, y, z), packed, Block.UPDATE_ALL);
                }
                level.setBlock(new BlockPos(x, FLOOR_Y + PACKED_HEIGHT, z), skin, Block.UPDATE_ALL);
            }
        }
        for (BlockPos lamp : new BlockPos[] {
            new BlockPos(X + 5, FLOOR_Y + 2, Z),
            new BlockPos(X - 5, FLOOR_Y + 2, Z),
            new BlockPos(X, FLOOR_Y + 2, Z + 5),
            new BlockPos(X + 3, FLOOR_Y + 5, Z - 4),
            new BlockPos(X - 3, FLOOR_Y + 5, Z - 4)
        }) {
            level.setBlock(lamp, Blocks.GLOWSTONE.defaultBlockState(), Block.UPDATE_ALL);
        }
    }
}
