package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.Failures.failure;

import io.github.pkeppeler.deepcharter.layer.LightSync;
import io.github.pkeppeler.deepcharter.test.support.CampaignEnvironments;
import io.github.pkeppeler.deepcharter.test.support.FarChunks;
import io.github.pkeppeler.deepcharter.test.support.LightEngineGate;
import io.github.pkeppeler.deepcharter.test.support.MockPlayer;
import io.github.pkeppeler.deepcharter.test.support.MockPlayers;
import io.github.pkeppeler.deepcharter.test.support.TestLayers;
import io.github.pkeppeler.deepcharter.test.support.TestTicks;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunk;
import org.jspecify.annotations.Nullable;

/**
 * A hold on a chunk keeps its neighbours unsent until the light tasks of the blocks just placed have run (#494). The
 * sampled case draws into many chunks as each becomes sendable and checks every tick that no neighbour of a drawn chunk
 * is sendable while the draw's tasks are waiting; it can pass when the engine happens to run the drawn chunk's queue
 * first. The two cases with a {@link LightEngineGate} set the state "the draw's tasks have not run, the neighbours' own
 * have" instead. The player case brings a player into view of a neighbour while its send is extended: the chunk has to
 * be offered to them when it can be sent.
 */
public class LightSyncRingHoldTest {
    private static final String SAMPLE = "deepcharter:campaign_sample";
    /** Far from the cells other tests use. */
    private static final int HOME_X = (-40_000 >> 4) + 200;

    private static final int HOME_Z = 40_000 >> 4;
    /** Chunks on a side of the area; a draw goes into every newly generated chunk of it, so most have a ring. */
    private static final int SIDE = 10;
    /** Rows the draw rewrites. */
    private static final int DRAW_ROWS = 6;

    private static final int MAX_TICKS = FarChunks.AWAIT_BUDGET_TICKS + 1_200;

    /** A draw's chunk, and the light tasks queued at it after the draw. */
    private record Drawn(ChunkPos chunk, CompletableFuture<?> tasks) {}

    /** The draws of the running case, filled by the chunk load callback. Null when no case runs. */
    private static volatile @Nullable Case running;

    private static final class Case {
        final ServerLevel level;
        /** The server tick after which the case is over, whatever became of its test: a timeout runs no exit path. */
        final int lastTick;

        final List<Drawn> drawn = new ArrayList<>();

        Case(ServerLevel level) {
            this.level = level;
            this.lastTick = level.getServer().getTickCount() + MAX_TICKS;
        }

        boolean open() {
            return level.getServer().getTickCount() <= lastTick;
        }
    }

    static {
        // Fabric events cannot be unregistered: the callback acts only while a case runs (it ends by its tick limit if
        // its test times out), in its level and area.
        ServerChunkEvents.CHUNK_LOAD.register((level, chunk, newlyGenerated) -> {
            Case current = running;
            ChunkPos pos = chunk.getPos();
            if (current != null
                    && current.open()
                    && current.level == level
                    && newlyGenerated
                    && pos.x() >= HOME_X
                    && pos.x() < HOME_X + SIDE
                    && pos.z() >= HOME_Z
                    && pos.z() < HOME_Z + SIDE) {
                draw(chunk);
                // Queued before the hold's own marker, so it is done whenever a neighbour that waits for the hold's is.
                CompletableFuture<?> tasks =
                        level.getChunkSource().getLightEngine().waitForPendingTasks(pos.x(), pos.z());
                LightSync.holdSendUntilLit(level, pos);
                current.drawn.add(new Drawn(pos, tasks));
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
                running = current; // replaces whatever an earlier case left
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
                    // The light engine completes the tasks on its own thread, so the tasks can finish between the check
                    // above and the read of a neighbour. Read the neighbour first and the tasks after: a done future
                    // stays done, so a future still pending after a neighbour was seen sendable was pending when it
                    // was seen.
                    ChunkPos.rangeClosed(each.chunk(), 1)
                            .filter(near -> !near.equals(each.chunk()))
                            .filter(near -> level.getChunkSource().chunkMap.getChunkToSend(near.pack()) != null)
                            .findFirst()
                            .filter(early -> !each.tasks().isDone())
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
                    endCase(current);
                    helper.succeed();
                }
            } catch (RuntimeException e) {
                endCase(current);
                throw e;
            }
        });
    }

    private static void endCase(Case current) {
        if (running == current) {
            running = null;
        }
    }

    /** The gated cases' site: the drawn chunk, its ring and a margin that ticks. Disjoint from other light tests. */
    private static final ChunkPos GATED = new ChunkPos((-40_000 >> 4) + 250, 40_000 >> 4);

    private static final int GATED_REACH_X = 3;
    private static final int GATED_REACH_Z = 2;
    /** Ticks the ring is watched for after the neighbours' own waits are done. */
    private static final int GATED_WATCH_TICKS = 20;
    /** Ticks a released send may take to reach the player: the light engine runs in wall time. */
    private static final int GATED_SEND_TICKS = 400;

    /**
     * With the drawn chunk's tasks held back and the neighbours' own done, no neighbour is sendable until the gate
     * opens.
     */
    @GameTest(environment = CampaignEnvironments.C, dimension = SAMPLE, maxTicks = MAX_TICKS)
    public void aNeighbourCannotBeSentWhileTheDrawnChunksLightTasksAreHeldBack(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        FarChunks.Deadline deadline = FarChunks.deadline(120);
        List<ChunkPos> ring = ring(GATED);
        List<CompletableFuture<?>> own = new ArrayList<>();
        int[] phase = {0};
        int[] waited = {0};
        TestTicks.each(helper, () -> {
            try {
                switch (phase[0]) {
                    case 0 -> {
                        forceAround(helper, level, GATED);
                        phase[0] = 1;
                    }
                    case 1 -> {
                        if (!allSendable(level, GATED, deadline, helper)) {
                            return;
                        }
                        LightEngineGate.close(GATED);
                        LightSync.holdSendUntilLit(level, GATED);
                        for (ChunkPos near : ring) {
                            own.add(level.getChunkSource().getLightEngine().waitForPendingTasks(near.x(), near.z()));
                        }
                        phase[0] = 2;
                    }
                    case 2 -> {
                        if (!own.stream().allMatch(CompletableFuture::isDone)) {
                            deadline.await(helper, level, false, () -> "the neighbours' own light tasks did not run");
                            return;
                        }
                        // The neighbours' own waits are done and the drawn chunk's is not: the state the engine's order
                        // can make.
                        for (ChunkPos near : ring) {
                            if (level.getChunkSource().chunkMap.getChunkToSend(near.pack()) != null) {
                                throw failure(
                                        helper,
                                        "%s is sendable while the light tasks queued at %s are held back and its own are done",
                                        near,
                                        GATED);
                            }
                        }
                        if (waited[0]++ < GATED_WATCH_TICKS) {
                            return;
                        }
                        LightEngineGate.release(GATED);
                        phase[0] = 3;
                    }
                    default -> {
                        boolean sendable = allSendable(level, GATED, deadline, helper);
                        if (sendable) {
                            LightEngineGate.release(GATED);
                            helper.succeed();
                        }
                    }
                }
            } catch (RuntimeException e) {
                LightEngineGate.release(GATED);
                throw e;
            }
        });
    }

    /** A player who comes into view of a neighbour whose send another hold extends is sent it once it can be. */
    @GameTest(environment = CampaignEnvironments.C, dimension = SAMPLE, maxTicks = MAX_TICKS)
    public void aPlayerWhoComesIntoViewWhileTheSendIsExtendedIsSentTheChunk(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ChunkPos drawn = new ChunkPos(GATED.x() + 20, GATED.z());
        ChunkPos neighbour = new ChunkPos(drawn.x() + 1, drawn.z());
        ChunkPos standAt = new ChunkPos(drawn.x() + 2, drawn.z());
        List<ChunkPos> ring = ring(drawn);
        MockPlayer mock = MockPlayers.join(helper, "ring-late-viewer");
        mock.player().setGameMode(GameType.SPECTATOR);
        FarChunks.Deadline deadline = FarChunks.deadline(120);
        CompletableFuture<Void> otherHold = new CompletableFuture<>();
        int[] phase = {0};
        int[] waited = {0};
        Runnable finish = () -> {
            LightEngineGate.release(drawn);
            otherHold.complete(null);
            mock.leave();
        };
        TestTicks.each(helper, () -> {
            try {
                switch (phase[0]) {
                    case 0 -> {
                        forceAround(helper, level, drawn);
                        phase[0] = 1;
                    }
                    case 1 -> {
                        if (!allSendable(level, drawn, deadline, helper)) {
                            return;
                        }
                        // Held by something else, so that the draw's hold finds the neighbour unsendable.
                        ChunkHolder holder = Objects.requireNonNull(
                                level.getChunkSource().chunkMap.getUpdatingChunkIfPresent(neighbour.pack()),
                                "the neighbour is loaded: allSendable");
                        holder.addSendDependency(otherHold);
                        LightEngineGate.close(drawn);
                        LightSync.holdSendUntilLit(level, drawn);
                        phase[0] = 2;
                    }
                    case 2 -> {
                        boolean ticking = level.isPositionEntityTicking(standAt.getWorldPosition());
                        deadline.await(helper, level, ticking, () -> "the chunk " + standAt + " did not tick");
                        if (!ticking) {
                            return;
                        }
                        // The player comes into view of the neighbour while it cannot be sent.
                        mock.teleportTo(
                                level,
                                TestLayers.vec(
                                        TestLayers.SURFACE,
                                        standAt.getMiddleBlockX() + 0.5,
                                        100,
                                        standAt.getMiddleBlockZ()),
                                0f,
                                0f);
                        phase[0] = 3;
                    }
                    case 3 -> {
                        if (!mock.player().getChunkTrackingView().contains(neighbour)) {
                            deadline.await(helper, level, false, () -> "the player did not come to track " + neighbour);
                            return;
                        }
                        if (level.getChunkSource().chunkMap.getChunkToSend(neighbour.pack()) != null) {
                            throw failure(helper, "%s can be sent, so the case proves nothing", neighbour);
                        }
                        // The other hold ends first, as a vanilla light wait does; the draw's tasks run last.
                        otherHold.complete(null);
                        waited[0] = 0;
                        phase[0] = 4;
                    }
                    case 4 -> {
                        if (waited[0]++ < GATED_WATCH_TICKS) {
                            return;
                        }
                        LightEngineGate.release(drawn);
                        waited[0] = 0;
                        phase[0] = 5;
                    }
                    default -> {
                        if (!mock.chunksSent().containsAll(ring) && waited[0]++ < GATED_SEND_TICKS) {
                            return;
                        }
                        List<ChunkPos> unsent = ring.stream()
                                .filter(near -> !mock.chunksSent().contains(near))
                                .filter(near ->
                                        mock.player().getChunkTrackingView().contains(near))
                                .toList();
                        if (!unsent.isEmpty()) {
                            throw failure(
                                    helper,
                                    "the player tracks %s and was never sent them, %d ticks after the draw's light tasks ran",
                                    unsent,
                                    GATED_SEND_TICKS);
                        }
                        finish.run();
                        helper.succeed();
                    }
                }
            } catch (RuntimeException e) {
                finish.run();
                throw e;
            }
        });
    }

    /** A chunk that unloads while its tasks or its offer are pending leaves nothing behind in {@link LightSync}. */
    @GameTest(environment = CampaignEnvironments.C, dimension = SAMPLE, maxTicks = MAX_TICKS)
    public void anUnloadedChunkLeavesNoPendingWorkInTheHold(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ChunkPos drawn = new ChunkPos(GATED.x() + 40, GATED.z());
        ChunkPos neighbour = new ChunkPos(drawn.x() + 1, drawn.z());
        FarChunks.Deadline deadline = FarChunks.deadline(120);
        CompletableFuture<Void> otherHold = new CompletableFuture<>();
        int[] phase = {0};
        Runnable finish = () -> {
            LightEngineGate.release(drawn);
            otherHold.complete(null);
        };
        TestTicks.each(helper, () -> {
            try {
                switch (phase[0]) {
                    case 0 -> {
                        forceAround(helper, level, drawn);
                        phase[0] = 1;
                    }
                    case 1 -> {
                        if (!allSendable(level, drawn, deadline, helper)) {
                            return;
                        }
                        ChunkHolder holder = Objects.requireNonNull(
                                level.getChunkSource().chunkMap.getUpdatingChunkIfPresent(neighbour.pack()),
                                "the neighbour is loaded: allSendable");
                        holder.addSendDependency(otherHold);
                        LightEngineGate.close(drawn);
                        LightSync.holdSendUntilLit(level, drawn);
                        if (!LightSync.hasPending(level, drawn) || !LightSync.hasPending(level, neighbour)) {
                            throw failure(helper, "the hold left nothing pending at %s or %s", drawn, neighbour);
                        }
                        for (int x = drawn.x() - GATED_REACH_X; x <= drawn.x() + GATED_REACH_X; x++) {
                            for (int z = drawn.z() - GATED_REACH_Z; z <= drawn.z() + GATED_REACH_Z; z++) {
                                FarChunks.release(helper, level, x, z);
                            }
                        }
                        phase[0] = 2;
                    }
                    default -> {
                        boolean unloaded = level.getChunkSource().getChunkNow(drawn.x(), drawn.z()) == null
                                && level.getChunkSource().getChunkNow(neighbour.x(), neighbour.z()) == null;
                        deadline.await(helper, level, unloaded, () -> drawn + " and " + neighbour + " did not unload");
                        if (!unloaded) {
                            return;
                        }
                        boolean pending = LightSync.hasPending(level, drawn) || LightSync.hasPending(level, neighbour);
                        // The chunk reads as absent before its save finishes and the unload event runs.
                        deadline.await(
                                helper,
                                level,
                                !pending,
                                () -> drawn + " and " + neighbour
                                        + " are unloaded but the hold still has work pending");
                        if (pending) {
                            return;
                        }
                        finish.run();
                        helper.succeed();
                    }
                }
            } catch (RuntimeException e) {
                finish.run();
                throw e;
            }
        });
    }

    private static List<ChunkPos> ring(ChunkPos chunk) {
        return ChunkPos.rangeClosed(chunk, 1)
                .filter(near -> !near.equals(chunk))
                .toList();
    }

    /** Forces the chunks round {@code chunk}, so that its 3x3 loads and ticks. */
    private static void forceAround(GameTestHelper helper, ServerLevel level, ChunkPos chunk) {
        for (int x = chunk.x() - GATED_REACH_X; x <= chunk.x() + GATED_REACH_X; x++) {
            for (int z = chunk.z() - GATED_REACH_Z; z <= chunk.z() + GATED_REACH_Z; z++) {
                FarChunks.force(helper, level, x, z);
            }
        }
    }

    /** Whether every chunk of the 3x3 can be sent and the light engine is idle; waits under the deadline. */
    private static boolean allSendable(
            ServerLevel level, ChunkPos chunk, FarChunks.Deadline deadline, GameTestHelper helper) {
        boolean ready = ChunkPos.rangeClosed(chunk, 1)
                        .allMatch(near -> level.getChunkSource().chunkMap.getChunkToSend(near.pack()) != null)
                && !level.getLightEngine().hasLightWork();
        deadline.await(helper, level, ready, () -> "the chunks round " + chunk + " were not all sendable");
        return ready;
    }

    /** Rewrites rows of the chunk with stone and glowstone in turn, so each write queues a light task there. */
    private static void draw(LevelChunk chunk) {
        ChunkPos at = chunk.getPos();
        // level-height: the sample dimension is one test world, not a layer of the campaign. The draw needs rows inside
        // the chunk's own height; a layer-relative Y would assume a layer this test never stages.
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
