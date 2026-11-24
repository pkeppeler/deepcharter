package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.Failures.failure;

import io.github.pkeppeler.deepcharter.layer.LightSync;
import io.github.pkeppeler.deepcharter.test.support.CampaignEnvironments;
import io.github.pkeppeler.deepcharter.test.support.FarChunks;
import io.github.pkeppeler.deepcharter.test.support.TestTicks;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunk;
import org.jspecify.annotations.Nullable;

/**
 * A hold on a chunk keeps its neighbours unsent until the light tasks of the blocks just placed have run (#494). Vanilla's hold makes
 * each neighbour wait only for the light tasks queued at its own position, and the light engine runs the queues of different
 * positions in no fixed order, so a neighbour could be sent the light it had before a draw. The order is not the test's to set: it
 * draws into many chunks as each becomes sendable, while the rest of the area is still being generated, and checks every tick that no
 * neighbour of a drawn chunk is sendable while the light tasks queued by the draw are waiting.
 */
public class LightSyncRingHoldTest {
    private static final String SAMPLE = "deepcharter:campaign_sample";
    /** Far from the cells other tests use. */
    private static final int HOME_X = (-40_000 >> 4) + 80;

    private static final int HOME_Z = 40_000 >> 4;
    /** Chunks on a side of the area; the draws go in the ones that have a full ring inside it. */
    private static final int SIDE = 10;
    /** Rows the draw rewrites. */
    private static final int DRAW_ROWS = 6;

    private static final int MAX_TICKS = FarChunks.AWAIT_BUDGET_TICKS + 1_200;

    /** A draw's chunk, and the light tasks queued at it after the draw. */
    private record Drawn(ChunkPos chunk, CompletableFuture<?> tasks) {}

    /** The draws of the running case, filled by the chunk load callback; server thread only. Null when no case runs. */
    private static volatile @Nullable Case running;

    private static final class Case {
        final ServerLevel level;
        final List<Drawn> drawn = new ArrayList<>();

        Case(ServerLevel level) {
            this.level = level;
        }
    }

    static {
        // Fabric events cannot be unregistered: the callback acts only while a case runs, in its level and area.
        ServerChunkEvents.CHUNK_LOAD.register((level, chunk, newlyGenerated) -> {
            Case current = running;
            ChunkPos pos = chunk.getPos();
            if (current != null
                    && current.level == level
                    && newlyGenerated
                    && pos.x() >= HOME_X
                    && pos.x() < HOME_X + SIDE
                    && pos.z() >= HOME_Z
                    && pos.z() < HOME_Z + SIDE) {
                draw(chunk);
                LightSync.holdSendUntilLit(level, pos);
                current.drawn.add(new Drawn(
                        pos, level.getChunkSource().getLightEngine().waitForPendingTasks(pos.x(), pos.z())));
            }
        });
    }

    @GameTest(environment = CampaignEnvironments.C, dimension = SAMPLE, maxTicks = MAX_TICKS)
    public void aNeighbourIsNotSentWhileTheLightTasksOfTheDrawAreQueued(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        FarChunks.Deadline deadline = FarChunks.deadline(120);
        Case current = new Case(level);
        boolean[] started = {false};
        TestTicks.each(helper, () -> {
            if (!started[0]) {
                started[0] = true;
                running = current;
                for (int x = 0; x < SIDE; x++) {
                    for (int z = 0; z < SIDE; z++) {
                        FarChunks.force(helper, level, HOME_X + x, HOME_Z + z);
                    }
                }
                return;
            }
            try {
                for (Drawn each : current.drawn) {
                    if (each.tasks().isDone()) {
                        continue;
                    }
                    ChunkPos.rangeClosed(each.chunk(), 1)
                            .filter(near -> !near.equals(each.chunk()))
                            .filter(near -> level.getChunkSource().chunkMap.getChunkToSend(near.pack()) != null)
                            .findFirst()
                            .ifPresent(early -> {
                                throw failure(
                                        helper,
                                        "%s is sendable while the light tasks queued by the draw of %s are waiting",
                                        early,
                                        each.chunk());
                            });
                }
                boolean finished = current.drawn.size() >= SIDE * SIDE
                        && current.drawn.stream().allMatch(each -> each.tasks().isDone());
                deadline.await(
                        helper,
                        level,
                        finished,
                        () -> "only " + current.drawn.size() + " chunks were drawn and settled");
                if (finished) {
                    running = null;
                    helper.succeed();
                }
            } catch (RuntimeException e) {
                running = null;
                throw e;
            }
        });
    }

    /** Rewrites rows of the chunk with stone and glowstone in turn, so each write queues a light task at its position. */
    private static void draw(LevelChunk chunk) {
        ChunkPos at = chunk.getPos();
        int y = chunk.getMinY() + 8;
        for (int dy = 0; dy < DRAW_ROWS; dy++) {
            for (int dx = 0; dx < 16; dx++) {
                for (int dz = 0; dz < 16; dz++) {
                    BlockPos pos = new BlockPos(at.getMinBlockX() + dx, y + dy, at.getMinBlockZ() + dz);
                    chunk.setBlockState(
                            pos,
                            (dx + dz + dy) % 2 == 0
                                    ? Blocks.STONE.defaultBlockState()
                                    : Blocks.GLOWSTONE.defaultBlockState(),
                            0);
                }
            }
        }
    }
}
