package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.Failures.failure;

import io.github.pkeppeler.deepcharter.layer.LightSync;
import io.github.pkeppeler.deepcharter.test.support.CampaignEnvironments;
import io.github.pkeppeler.deepcharter.test.support.FarChunks;
import io.github.pkeppeler.deepcharter.test.support.MockPlayer;
import io.github.pkeppeler.deepcharter.test.support.MockPlayers;
import io.github.pkeppeler.deepcharter.test.support.TestTicks;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

/**
 * #998: the client test's breach pocket, on the server. A sealed room is carved and held in the tick a player arrives, as a breach does;
 * the light the player's client would hold, built from the packets it was sent ({@link MockPlayer#clientLight}), must be the light the
 * server stores once the light engine is idle, for both layers.
 */
public class SealedRoomArrivalLightTest {
    private static final String SAMPLE = "deepcharter:campaign_sample";
    private static final int X = -60_000;
    private static final int Z = 60_000;
    private static final int RADIUS = 4;
    private static final int HEIGHT = 4;
    /** Ticks after the light engine is idle that the net may still send: its window of 100 ticks, with a margin (restates {@code LightSync}). */
    private static final int SETTLE_TICKS = 100 + 20;

    @GameTest(dimension = SAMPLE, environment = CampaignEnvironments.C, maxTicks = FarChunks.AWAIT_BUDGET_TICKS + 3_000)
    public void aRoomSealedInTheTickThePlayerArrivesIsSentItsDarkness(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MockPlayer mock = MockPlayers.join(helper, "sealed-arrival");
        mock.player().setGameMode(GameType.SPECTATOR);
        FarChunks.Deadline deadline = FarChunks.deadline(120);
        int[] phase = {0};
        int[] waited = {0};
        BlockPos[] floor = {null};
        List<ChunkPos> room = new ArrayList<>();
        for (int cx = (X - 12) >> 4; cx <= (X + 12) >> 4; cx++) {
            for (int cz = (Z - 12) >> 4; cz <= (Z + 12) >> 4; cz++) {
                FarChunks.force(helper, level, cx, cz);
            }
        }
        TestTicks.each(helper, () -> {
            switch (phase[0]) {
                case 0 -> {
                    boolean loaded = true;
                    for (int cx = (X - 12) >> 4; cx <= (X + 12) >> 4; cx++) {
                        for (int cz = (Z - 12) >> 4; cz <= (Z + 12) >> 4; cz++) {
                            loaded &= level.getChunkSource().getChunkNow(cx, cz) != null;
                        }
                    }
                    deadline.await(helper, level, loaded, () -> "the chunks round the room did not load");
                    if (!loaded) {
                        return;
                    }
                    floor[0] = new BlockPos(X, level.getHeight(Heightmap.Types.MOTION_BLOCKING, X, Z), Z);
                    ChunkPos.rangeClosed(
                                    ChunkPos.containing(floor[0].offset(-RADIUS, 0, -RADIUS)),
                                    ChunkPos.containing(floor[0].offset(RADIUS, 0, RADIUS)))
                            .forEach(room::add);
                    phase[0] = 1;
                }
                case 1 -> {
                    carveRoom(level, floor[0]);
                    LightSync.holdSendUntilLit(
                            level, floor[0].offset(-RADIUS, 1, -RADIUS), floor[0].offset(RADIUS, HEIGHT, RADIUS));
                    BlockPos stand = floor[0].above(HEIGHT + 3).offset(10, 0, 0);
                    mock.teleportTo(level, Vec3.atBottomCenterOf(stand), 0f, 0f);
                    phase[0] = 2;
                }
                case 2 -> {
                    boolean sent = room.stream().allMatch(chunk -> mock.timesSent(chunk) > 0);
                    deadline.await(helper, level, sent, () -> "the player was not sent the room's chunks " + room);
                    if (sent) {
                        phase[0] = 3;
                    }
                }
                case 3 -> {
                    boolean idle = !level.getLightEngine().hasLightWork();
                    deadline.await(helper, level, idle, () -> "light work stays pending");
                    if (!idle) {
                        waited[0] = 0;
                        return;
                    }
                    if (waited[0]++ < SETTLE_TICKS) {
                        return;
                    }
                    String stale = staleOf(mock, level, room);
                    mock.leave();
                    if (!stale.isEmpty()) {
                        throw failure(
                                helper,
                                "the player's client keeps light the server does not store, in the room's chunks:%s"
                                        + " packets: %s",
                                stale,
                                String.join("|", mock.clientLight().events()));
                    }
                    helper.succeed();
                }
            }
        });
    }

    private static String staleOf(MockPlayer mock, ServerLevel level, List<ChunkPos> room) {
        StringBuilder stale = new StringBuilder();
        for (ChunkPos chunk : room) {
            for (LightLayer layer : LightLayer.values()) {
                BitSet sections = mock.clientLight().staleSections(level.getLightEngine(), layer, chunk);
                if (!sections.isEmpty()) {
                    stale.append(String.format(" %s %s sections %s;", chunk, layer, sections));
                }
            }
        }
        return stale.toString();
    }

    /** A stone shell with air in it, written block by block with notification, as a breach pocket is. */
    private static void carveRoom(ServerLevel level, BlockPos floor) {
        for (int dx = -RADIUS - 1; dx <= RADIUS + 1; dx++) {
            for (int dz = -RADIUS - 1; dz <= RADIUS + 1; dz++) {
                for (int dy = 0; dy <= HEIGHT + 1; dy++) {
                    boolean shell =
                            dy == 0 || dy == HEIGHT + 1 || Math.abs(dx) == RADIUS + 1 || Math.abs(dz) == RADIUS + 1;
                    level.setBlock(
                            floor.offset(dx, dy, dz),
                            shell ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState(),
                            3);
                }
            }
        }
    }
}
