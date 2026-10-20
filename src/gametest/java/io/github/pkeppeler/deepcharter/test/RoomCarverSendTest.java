package io.github.pkeppeler.deepcharter.test;

import io.github.pkeppeler.deepcharter.layer.LayerChain;
import io.github.pkeppeler.deepcharter.layer.LightSync;
import io.github.pkeppeler.deepcharter.test.support.FarChunks;
import io.github.pkeppeler.deepcharter.test.support.MockPlayer;
import io.github.pkeppeler.deepcharter.test.support.MockPlayers;
import io.github.pkeppeler.deepcharter.test.support.RoomCarver;
import io.github.pkeppeler.deepcharter.test.support.TestTicks;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
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
                if (LightSync.heldChunks().stream()
                        .noneMatch(line -> line.contains(chunks.get(0).toString()))) {
                    throw helper.assertionException(Component.literal(
                            "the carve should hold " + chunks.get(0) + ", held: " + LightSync.heldChunks()));
                }
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

    /** A player who has the chunk before the carve gets block updates, not the chunk again: a resend to everyone would pass the tests above. */
    @GameTest(maxTicks = MAX_TICKS)
    public void aPlayerWhoHadTheChunkBeforeTheCarveIsNotSentItAgain(GameTestHelper helper) {
        int layer = 1;
        BlockPos min = new BlockPos(6800 + 4, FLOOR, 6800 + 4);
        BlockPos max = new BlockPos(6800 + 10, FLOOR + 4, 6800 + 10);
        ServerLevel level = helper.getLevel().getServer().getLevel(LayerChain.dimension(layer));
        MockPlayer player = MockPlayers.join(helper, "had-" + min.getX());
        ChunkPos room = ChunkPos.containing(min);
        int[] stage = {0};
        int[] settledAt = {0};
        FarChunks.awaitEntityTicking(helper, level, List.of(min), index -> {
            player.teleportTo(level, Vec3.atBottomCenterOf(min), 0f, 0f);
            stage[0] = 1;
        });
        FarChunks.Deadline deadline = FarChunks.deadline(SECONDS);
        TestTicks.each(helper, () -> {
            if (stage[0] == 1 && player.timesSent(room) == 1) {
                RoomCarver.carve(level, min, max, Blocks.AIR.defaultBlockState());
                stage[0] = 2;
            } else if (stage[0] == 2 && LightSync.heldChunks().isEmpty()) {
                settledAt[0] = (int) helper.getTick();
                stage[0] = 3;
            } else if (stage[0] == 3 && helper.getTick() - settledAt[0] > 20) {
                int times = player.timesSent(room);
                if (times != 1) {
                    throw helper.assertionException(Component.literal("the player had " + room
                            + " before the carve and was sent it " + times + " times, not once"));
                }
                helper.succeed();
            } else {
                deadline.await(
                        helper,
                        level,
                        stage[0] == 3,
                        () -> "stage " + stage[0] + " of the carve did not finish; " + room + " was sent "
                                + player.timesSent(room) + " times");
            }
        });
    }

    @GameTest
    public void aHoldIsStuckAtTheLimitAndAfterIt(GameTestHelper helper) {
        for (int ticks : new int[] {600, 601, 5000}) {
            if (!LightSync.timedOut(ticks)) {
                throw helper.assertionException(Component.literal("a hold of " + ticks + " ticks should be stuck"));
            }
        }
        for (int ticks : new int[] {0, 1, 599}) {
            if (LightSync.timedOut(ticks)) {
                throw helper.assertionException(Component.literal("a hold of " + ticks + " ticks should not be stuck"));
            }
        }
        helper.succeed();
    }
}
