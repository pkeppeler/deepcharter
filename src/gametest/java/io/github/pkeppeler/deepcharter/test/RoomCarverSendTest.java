package io.github.pkeppeler.deepcharter.test;

import io.github.pkeppeler.deepcharter.layer.LayerChain;
import io.github.pkeppeler.deepcharter.test.support.FarChunks;
import io.github.pkeppeler.deepcharter.test.support.MockPlayer;
import io.github.pkeppeler.deepcharter.test.support.MockPlayers;
import io.github.pkeppeler.deepcharter.test.support.RoomCarver;
import io.github.pkeppeler.deepcharter.test.support.TestTicks;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * A room that {@link RoomCarver} carves holds the sending of its chunks until their light is computed (#524). A player who comes into
 * view while the hold is pending must still be sent the chunk once it ends. Each test carves in a ticking chunk of layer rock, as the
 * evidence scenarios and the breach do, brings a player into view in the same tick, and waits for the server to have sent every chunk
 * of the room.
 */
public class RoomCarverSendTest {
    private static final int SECONDS = 30;
    private static final int FLOOR = 60;
    private static final int MAX_TICKS = FarChunks.AWAIT_BUDGET_TICKS + 200;

    /** Carves the box once its chunks tick, brings the player in the same tick, and fails naming the chunks that are never sent. */
    private static void carveThenArrive(GameTestHelper helper, int layer, BlockPos min, BlockPos max) {
        ServerLevel level = helper.getLevel().getServer().getLevel(LayerChain.dimension(layer));
        MockPlayer player = MockPlayers.join(helper, "send-" + min.getX());
        List<ChunkPos> chunks = ChunkPos.rangeClosed(
                        ChunkPos.containing(min.offset(-1, -1, -1)), ChunkPos.containing(max.offset(1, 1, 1)))
                .toList();
        List<BlockPos> columns = chunks.stream()
                .map(chunk -> new BlockPos(chunk.getMinBlockX(), FLOOR, chunk.getMinBlockZ()))
                .toList();
        int[] ticking = {0};
        FarChunks.awaitEntityTicking(helper, level, columns, index -> {
            if (++ticking[0] == columns.size()) {
                RoomCarver.carve(level, min, max, Blocks.AIR.defaultBlockState());
                player.teleportTo(level, Vec3.atBottomCenterOf(min), 0f, 0f);
            }
        });
        FarChunks.Deadline deadline = FarChunks.deadline(SECONDS);
        TestTicks.each(helper, () -> {
            if (ticking[0] < columns.size()) {
                return;
            }
            List<ChunkPos> unsent = chunks.stream()
                    .filter(chunk -> !player.chunksSent().contains(chunk))
                    .toList();
            if (unsent.isEmpty()) {
                helper.succeed();
                return;
            }
            deadline.await(
                    helper,
                    level,
                    false,
                    () -> "RoomCarver held the sending of " + unsent + " in " + level.dimension()
                            + " and never released it: "
                            + SECONDS + " s after carving " + min + " to " + max
                            + " a player in view of it has not been sent it");
        });
    }

    @GameTest(maxTicks = MAX_TICKS)
    public void aPlayerWhoArrivesDuringTheHoldIsSentTheRoomOfLayerOne(GameTestHelper helper) {
        carveThenArrive(
                helper, 1, new BlockPos(6400 + 4, FLOOR, 6400 + 4), new BlockPos(6400 + 10, FLOOR + 4, 6400 + 10));
    }

    @GameTest(maxTicks = MAX_TICKS)
    public void aPlayerWhoArrivesDuringTheHoldIsSentARoomAcrossFourChunksOfLayerTwo(GameTestHelper helper) {
        carveThenArrive(
                helper, 2, new BlockPos(6600 - 6, FLOOR, 6600 - 6), new BlockPos(6600 + 6, FLOOR + 4, 6600 + 6));
    }
}
