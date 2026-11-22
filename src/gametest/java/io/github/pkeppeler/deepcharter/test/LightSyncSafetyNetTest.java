package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.Failures.failure;

import io.github.pkeppeler.deepcharter.layer.LightSync;
import io.github.pkeppeler.deepcharter.test.support.BlockLightOracle;
import io.github.pkeppeler.deepcharter.test.support.CampaignEnvironments;
import io.github.pkeppeler.deepcharter.test.support.FarChunks;
import io.github.pkeppeler.deepcharter.test.support.LightBroadcastSuppression;
import io.github.pkeppeler.deepcharter.test.support.LightSyncSends;
import io.github.pkeppeler.deepcharter.test.support.MockPlayer;
import io.github.pkeppeler.deepcharter.test.support.MockPlayers;
import io.github.pkeppeler.deepcharter.test.support.TestLayers;
import io.github.pkeppeler.deepcharter.test.support.TestTicks;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.jspecify.annotations.Nullable;

/**
 * The safety net of {@link LightSync} (#863, ADR 0068). A player who tracks two chunks is sent both, a lamp beside their border is
 * placed and held as a draw is, and the hold ends. Vanilla's light broadcast is switched off for the player's chunk
 * ({@link LightBroadcastSuppression}), so a light change after the hold reaches the player by no other way. Then:
 *
 * <ul>
 *   <li>the lamp is removed: the light the server stores in the chunk is the light of the blocks, which {@link BlockLightOracle} works
 *       out without the light engine, and the net sends the player the sections that changed ({@link LightSyncSends} records what
 *       {@code LightSync} sends, so a vanilla update cannot stand in);
 *   <li>nothing is changed: the net sends nothing.
 * </ul>
 */
public class LightSyncSafetyNetTest {
    private static final String SAMPLE = "deepcharter:campaign_sample";
    /** Far from the cells other tests use. */
    private static final int PLAYER_X = -40_000;

    private static final int PLAYER_Z = 40_000;
    /** The chunks held loaded east and west of the player's chunk, and north and south of it. */
    private static final int REACH_X = 3;

    private static final int REACH_Z = 2;
    /** Rows round the lamp that the oracle compares; light fades to nothing within 15 blocks. */
    private static final int ROWS = 8;

    private static final int SETTLE_TICKS = 20;
    /** Ticks from the hold's request to the send of the neighbour in the late case: more than a window counted from the request. */
    private static final int LATE_SEND_TICKS = 800;

    /** Ticks the net compares for after the chunk can be sent, with a margin: restates its window. */
    private static final int NET_WAIT_TICKS = 100 + 40;
    /** Ticks the stored light must not change before the case takes it as settled. */
    private static final int STEADY_TICKS = 6;

    private static final int MAX_TICKS = FarChunks.AWAIT_BUDGET_TICKS + 1_200;

    @GameTest(environment = CampaignEnvironments.C, dimension = SAMPLE, maxTicks = MAX_TICKS)
    public void aLightChangeAfterAHoldNoBroadcastReachesAPlayerIsSent(GameTestHelper helper) {
        run(helper, true);
    }

    @GameTest(environment = CampaignEnvironments.C, dimension = SAMPLE, maxTicks = MAX_TICKS)
    public void noLightPacketIsSentWhenTheLightDidNotChangeAfterAHold(GameTestHelper helper) {
        run(helper, false);
    }

    /**
     * The light the player holds is neither the light before a change nor the light after it, and the published light returns to the
     * first. The chunk lies dark; a lamp is lit; the chunk is sent to the player lit; the lamp goes out. The holds that keep the net's
     * watch alive end last, so the net's own record from the hold's end is dark, as the published light is: only what the player was
     * sent, lit, tells it to send. A net that compares with its own last sample sees dark, then dark.
     */
    @GameTest(environment = CampaignEnvironments.C, dimension = SAMPLE, maxTicks = MAX_TICKS)
    public void aLightThatMovedAndCameBackAfterTheChunkWasSentIsSentAgain(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MockPlayer mock = MockPlayers.join(helper, "net-transient");
        UUID id = mock.player().getUUID();
        mock.player().setGameMode(GameType.SPECTATOR);
        ChunkPos home = new ChunkPos((PLAYER_X >> 4) + 40, PLAYER_Z >> 4);
        ChunkPos lampChunk = new ChunkPos(home.x() + 1, home.z());
        List<ChunkPos> pair = List.of(home, lampChunk);
        LightSyncSends.watch(id);
        FarChunks.Deadline deadline = FarChunks.deadline(120);
        int[] phase = {0};
        int[] waited = {0};
        int[] sentBefore = {0};
        BlockPos[] lamp = {null};
        int[][] dark = {null};
        int[][] steady = {null};
        AtomicReference<@Nullable CompletableFuture<?>> marker = new AtomicReference<>();
        BitSet[] changed = {null};
        boolean[] ended = {false};
        Runnable finish = () -> {
            ended[0] = true;
            LightBroadcastSuppression.restore(home);
            LightBroadcastSuppression.restore(lampChunk);
            LightSyncSends.unwatch(id);
            mock.leave();
        };
        TestTicks.each(helper, () -> {
            if (ended[0]) {
                return;
            }
            try {
                switch (phase[0]) {
                    case 0 -> {
                        forEach(home, chunk -> {
                            FarChunks.force(helper, level, chunk.x(), chunk.z());
                            level.getChunk(chunk.x(), chunk.z(), ChunkStatus.FULL);
                        });
                        phase[0] = 1;
                    }
                    case 1 -> {
                        if (!teleportOnceTicking(helper, level, mock, home, deadline)) {
                            return;
                        }
                        phase[0] = 2;
                    }
                    case 2 -> {
                        boolean sent = mock.chunksSent().containsAll(pair);
                        deadline.await(helper, level, sent, () -> "the player was not sent " + pair);
                        if (!sent) {
                            return;
                        }
                        // Vanilla sends the player no light of these chunks from here on.
                        LightBroadcastSuppression.suppress(home);
                        LightBroadcastSuppression.suppress(lampChunk);
                        int x = lampChunk.getMinBlockX();
                        int z = lampChunk.getMiddleBlockZ();
                        lamp[0] = new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) + 2, z);
                        phase[0] = 3;
                    }
                    case 3 -> {
                        // The chunk lies dark and its light has stopped changing.
                        if (!steady(level, home, home, lamp[0], steady, waited, marker)) {
                            return;
                        }
                        dark[0] = steady[0];
                        level.setBlock(lamp[0], Blocks.MAGMA_BLOCK.defaultBlockState(), 3);
                        phase[0] = 4;
                    }
                    case 4 -> {
                        if (changedSections(level, home, lamp[0], dark[0]).isEmpty()
                                || !steady(level, home, home, lamp[0], steady, waited, marker)) {
                            return;
                        }
                        changed[0] = changedSections(level, home, lamp[0], dark[0]);
                        sentBefore[0] = mock.timesSent(home);
                        phase[0] = 5;
                        keepHeld(level, lamp[0]);
                        // The player is sent the lit chunk.
                        ServerPlayer player = mock.player();
                        player.connection.chunkSender.dropChunk(player, home);
                        player.connection.chunkSender.markChunkPendingToSend(level.getChunk(home.x(), home.z()));
                    }
                    case 5 -> {
                        // The lamp goes out once the lit chunk is sent: the player holds the lit light for good.
                        if (mock.timesSent(home) <= sentBefore[0]) {
                            return;
                        }
                        level.setBlock(lamp[0], Blocks.AIR.defaultBlockState(), 3);
                        keepHeld(level, lamp[0]);
                        phase[0] = 6;
                    }
                    case 6 -> {
                        // Held again from the send on, so no comparison of the net sees the chunk lit or half dark.
                        keepHeld(level, lamp[0]);
                        if (!changedSections(level, home, lamp[0], dark[0]).isEmpty()
                                || !steady(level, home, home, lamp[0], steady, waited, marker)) {
                            return;
                        }
                        // The sends so far are not the ones the case is about. The last hold, as a draw's, ends with
                        // the chunk dark.
                        LightSyncSends.unwatch(id);
                        LightSyncSends.watch(id);
                        keepHeld(level, lamp[0]);
                        phase[0] = 7;
                    }
                    case 7 -> {
                        boolean over = holdsEnded(pair);
                        deadline.await(helper, level, over, () -> "the hold of " + pair + " did not end");
                        if (!over) {
                            return;
                        }
                        waited[0] = 0;
                        phase[0] = 8;
                    }
                    default -> {
                        if (waited[0]++ < NET_WAIT_TICKS) {
                            return;
                        }
                        BitSet unsent = (BitSet) changed[0].clone();
                        unsent.andNot(LightSyncSends.sectionsCarried(id, home));
                        if (!unsent.isEmpty()) {
                            throw failure(
                                    helper,
                                    "the player was sent the chunk lit, the light is dark again, and the net did not send the sections %s, so the client keeps the lamp's light",
                                    unsent);
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

    /** A hold of the lamp's chunks, as a draw makes one. */
    private static void keepHeld(ServerLevel level, BlockPos lamp) {
        LightSync.holdSendUntilLit(level, lamp, lamp);
    }

    /**
     * A chunk of a hold's 3x3 that is loaded but cannot be sent when the hold is requested (it does not tick yet) is watched too: it
     * is sent later, unheld, and a light change after that must reach the player. The player's chunk ticks and the chunk next to it,
     * the lamp's, ticks through it; the chunk beyond is loaded and neither ticks nor can be sent until the case forces it.
     */
    @GameTest(environment = CampaignEnvironments.C, dimension = SAMPLE, maxTicks = MAX_TICKS)
    public void aNeighbourThatCannotBeSentWhenTheHoldIsRequestedIsWatched(GameTestHelper helper) {
        neighbourSentLater(helper, "net-neighbour", 80, 0);
    }

    /**
     * As above, but the chunk is sent {@value #LATE_SEND_TICKS} ticks after the hold was requested, past any window counted from the
     * request (#954): the ticks of a server and the light engine's thread run at different speeds under load, so how many ticks a
     * neighbour waits to be sent is not bounded.
     */
    @GameTest(environment = CampaignEnvironments.C, dimension = SAMPLE, maxTicks = MAX_TICKS + LATE_SEND_TICKS)
    public void aNeighbourSentLongAfterTheHoldWasRequestedIsWatched(GameTestHelper helper) {
        neighbourSentLater(helper, "net-late", 120, LATE_SEND_TICKS);
    }

    /** The case, at {@code homeOffset} chunks from the spawn: two cases that run together must not share a chunk. */
    private static void neighbourSentLater(GameTestHelper helper, String name, int homeOffset, int sendDelayTicks) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        int simulationBefore = server.getPlayerList().getSimulationDistance();
        int viewBefore = server.getPlayerList().getViewDistance();
        server.getPlayerList().setSimulationDistance(1);
        server.getPlayerList().setViewDistance(3);
        MockPlayer mock = MockPlayers.join(helper, name);
        UUID id = mock.player().getUUID();
        ClientInformation info = mock.player().clientInformation();
        mock.player()
                .updateOptions(new ClientInformation(
                        info.language(),
                        3,
                        info.chatVisibility(),
                        info.chatColors(),
                        info.modelCustomisation(),
                        info.mainHand(),
                        info.textFilteringEnabled(),
                        info.allowsListing(),
                        info.particleStatus()));
        mock.player().setGameMode(GameType.SPECTATOR);
        ChunkPos home = new ChunkPos((PLAYER_X >> 4) + homeOffset, PLAYER_Z >> 4);
        ChunkPos lampChunk = new ChunkPos(home.x() + 1, home.z());
        ChunkPos beyond = new ChunkPos(home.x() + 2, home.z());
        LightSyncSends.watch(id);
        FarChunks.Deadline deadline = FarChunks.deadline(120);
        int[] phase = {0};
        int[] waited = {0};
        int[] requested = {0};
        BlockPos[] lamp = {null};
        int[][] lit = {null};
        int[][] steady = {null};
        AtomicReference<@Nullable CompletableFuture<?>> marker = new AtomicReference<>();
        boolean[] ended = {false};
        Runnable finish = () -> {
            ended[0] = true;
            server.getPlayerList().setSimulationDistance(simulationBefore);
            server.getPlayerList().setViewDistance(viewBefore);
            LightBroadcastSuppression.restore(beyond);
            LightBroadcastSuppression.restore(lampChunk);
            LightSyncSends.unwatch(id);
            mock.leave();
            releaseAround(level, home);
        };
        TestTicks.each(helper, () -> {
            if (ended[0]) {
                return;
            }
            try {
                if (phase[0] > 0 && phase[0] < 5) {
                    loadAround(level, home);
                }
                switch (phase[0]) {
                    case 0 -> {
                        loadAround(level, home);
                        FarChunks.force(helper, level, home.x(), home.z());
                        phase[0] = 1;
                    }
                    case 1 -> {
                        if (!teleportOnceTicking(helper, level, mock, home, deadline)) {
                            return;
                        }
                        phase[0] = 2;
                    }
                    case 2 -> {
                        boolean sent = mock.chunksSent().contains(lampChunk);
                        deadline.await(helper, level, sent, () -> "the player was not sent " + lampChunk);
                        if (!sent) {
                            return;
                        }
                        if (level.getChunkSource().chunkMap.getChunkToSend(beyond.pack()) != null
                                || level.getChunkSource().isPositionTicking(beyond.pack())) {
                            throw failure(
                                    helper, "the chunk %s can be sent or ticks, so the case proves nothing", beyond);
                        }
                        LightBroadcastSuppression.suppress(lampChunk);
                        LightBroadcastSuppression.suppress(beyond);
                        // The last column of the lamp's chunk: the light spills across the border into the
                        // chunk beyond.
                        int x = beyond.getMinBlockX() - 1;
                        int z = beyond.getMiddleBlockZ();
                        lamp[0] = new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) + 2, z);
                        level.setBlock(lamp[0], Blocks.SEA_LANTERN.defaultBlockState(), 3);
                        LightSync.holdSendUntilLit(level, lamp[0], lamp[0]);
                        requested[0] = server.getTickCount();
                        phase[0] = 3;
                    }
                    case 3 -> {
                        boolean over = holdsEnded(List.of(lampChunk));
                        deadline.await(helper, level, over, () -> "the hold of " + lampChunk + " did not end");
                        if (!over || !steady(level, beyond, lampChunk, lamp[0], steady, waited, marker)) {
                            return;
                        }
                        lit[0] = steady[0];
                        if (server.getTickCount() - requested[0] < sendDelayTicks) {
                            return;
                        }
                        // The chunk beyond ticks from now, and is sent to the player, which tracks it.
                        FarChunks.force(helper, level, beyond.x(), beyond.z());
                        phase[0] = 4;
                    }
                    case 4 -> {
                        boolean sent = mock.chunksSent().contains(beyond);
                        deadline.await(helper, level, sent, () -> "the player was not sent " + beyond);
                        if (!sent) {
                            return;
                        }
                        LightSyncSends.unwatch(id);
                        LightSyncSends.watch(id);
                        level.setBlock(lamp[0], Blocks.AIR.defaultBlockState(), 3);
                        waited[0] = 0;
                        phase[0] = 5;
                    }
                    case 5 -> {
                        // The lamp's removal is published: every task queued for it has run and the light has
                        // stopped changing.
                        if (!steady(level, beyond, lampChunk, lamp[0], steady, waited, marker)) {
                            return;
                        }
                        waited[0] = 0;
                        phase[0] = 6;
                    }
                    case 6 -> {
                        if (waited[0]++ < NET_WAIT_TICKS) {
                            return;
                        }
                        BitSet changed = changedSections(level, beyond, lamp[0], lit[0]);
                        if (changed.isEmpty()) {
                            throw failure(
                                    helper,
                                    "removing the lamp changed no light in %s, so the case proves nothing",
                                    beyond);
                        }
                        BitSet unsent = (BitSet) changed.clone();
                        unsent.andNot(LightSyncSends.sectionsCarried(id, beyond));
                        if (!unsent.isEmpty()) {
                            throw failure(
                                    helper,
                                    "the chunk %s could not be sent when the hold was requested, its light changed after it was sent, and the net did not send the sections %s",
                                    beyond,
                                    unsent);
                        }
                        finish.run();
                        helper.succeed();
                    }
                    default -> throw new IllegalStateException("phase " + phase[0]);
                }
            } catch (RuntimeException e) {
                finish.run();
                throw e;
            }
        });
    }

    /** Moves the player to the chunk once it ticks; false until then. */
    private static boolean teleportOnceTicking(
            GameTestHelper helper, ServerLevel level, MockPlayer mock, ChunkPos home, FarChunks.Deadline deadline) {
        boolean ticking = level.isPositionEntityTicking(home.getWorldPosition());
        deadline.await(helper, level, ticking, () -> "the chunk " + home + " did not tick");
        if (ticking) {
            mock.teleportTo(
                    level,
                    TestLayers.vec(TestLayers.SURFACE, home.getMiddleBlockX() + 0.5, 100, home.getMiddleBlockZ()),
                    0f,
                    0f);
        }
        return ticking;
    }

    private static void releaseAround(ServerLevel level, ChunkPos home) {
        for (int x = home.x() - 2; x <= home.x() + 4; x++) {
            for (int z = home.z() - 2; z <= home.z() + 2; z++) {
                level.getChunkSource().removeTicketWithRadius(TicketType.PLAYER_LOADING, new ChunkPos(x, z), 0);
            }
        }
    }

    /** Loads the chunks round the player's chunk to FULL, held by a ticket that loads and does not simulate. */
    private static void loadAround(ServerLevel level, ChunkPos home) {
        for (int x = home.x() - 2; x <= home.x() + 4; x++) {
            for (int z = home.z() - 2; z <= home.z() + 2; z++) {
                level.getChunkSource().addTicketWithRadius(TicketType.PLAYER_LOADING, new ChunkPos(x, z), 0);
                level.getChunk(x, z, ChunkStatus.FULL);
            }
        }
    }

    /**
     * True once the light tasks queued for {@code marked} have run and the stored light of {@code chunk} has been the same for
     * {@value #STEADY_TICKS} ticks in a row; {@code last} holds it. Light spreads from one chunk to the next in the batch that runs
     * the first's tasks, so a marker on the lamp's chunk covers the neighbour's light.
     */
    private static boolean steady(
            ServerLevel level,
            ChunkPos chunk,
            ChunkPos marked,
            BlockPos lamp,
            int[][] last,
            int[] same,
            AtomicReference<@Nullable CompletableFuture<?>> marker) {
        // Every light task queued for the chunk so far has run: a marker the light engine completes behind them.
        CompletableFuture<?> waiting = marker.get();
        if (waiting == null) {
            waiting = level.getChunkSource().getLightEngine().waitForPendingTasks(marked.x(), marked.z());
            marker.set(waiting);
        }
        if (!waiting.isDone()) {
            return false;
        }
        int[] now = storedLight(level, chunk, lamp);
        if (last[0] != null && Arrays.equals(last[0], now)) {
            same[0]++;
        } else {
            same[0] = 0;
        }
        last[0] = now;
        if (same[0] >= STEADY_TICKS) {
            same[0] = 0;
            marker.set(null);
            return true;
        }
        return false;
    }

    private static void run(GameTestHelper helper, boolean change) {
        ServerLevel level = helper.getLevel();
        MockPlayer mock = MockPlayers.join(helper, change ? "net-change" : "net-same");
        UUID id = mock.player().getUUID();
        mock.player().setGameMode(GameType.SPECTATOR);
        int offsetX = change ? 0 : 20;
        ChunkPos home = new ChunkPos((PLAYER_X >> 4) + offsetX, PLAYER_Z >> 4);
        ChunkPos lampChunk = new ChunkPos(home.x() + 1, home.z());
        List<ChunkPos> pair = List.of(home, lampChunk);
        LightSyncSends.watch(id);
        FarChunks.Deadline deadline = FarChunks.deadline(120);
        int[] phase = {0};
        int[] waited = {0};
        int[][] steady = {null};
        AtomicReference<@Nullable CompletableFuture<?>> marker = new AtomicReference<>();
        BlockPos[] lamp = {null};
        int[][] before = {null};
        boolean[] ended = {false};
        Runnable finish = () -> {
            ended[0] = true;
            LightBroadcastSuppression.restore(home);
            LightBroadcastSuppression.restore(lampChunk);
            LightSyncSends.unwatch(id);
            mock.leave();
        };
        TestTicks.each(helper, () -> {
            if (ended[0]) {
                return;
            }
            try {
                switch (phase[0]) {
                    case 0 -> {
                        // Every chunk of the case is loaded and forced, so it ticks as far as its neighbours allow.
                        forEach(home, chunk -> {
                            FarChunks.force(helper, level, chunk.x(), chunk.z());
                            level.getChunk(chunk.x(), chunk.z(), ChunkStatus.FULL);
                        });
                        phase[0] = 1;
                    }
                    case 1 -> {
                        if (!teleportOnceTicking(helper, level, mock, home, deadline)) {
                            return;
                        }
                        phase[0] = 2;
                    }
                    case 2 -> {
                        boolean sent = mock.chunksSent().containsAll(pair);
                        deadline.await(helper, level, sent, () -> "the player was not sent " + pair);
                        if (!sent) {
                            return;
                        }
                        // Vanilla sends the player no light of these chunks from here on.
                        LightBroadcastSuppression.suppress(home);
                        LightBroadcastSuppression.suppress(lampChunk);
                        // A lamp beside the border with the player's chunk, placed as a draw places blocks.
                        int x = lampChunk.getMinBlockX() + 1;
                        int z = lampChunk.getMiddleBlockZ();
                        lamp[0] = new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) + 2, z);
                        level.setBlock(lamp[0], Blocks.SEA_LANTERN.defaultBlockState(), 3);
                        LightSync.holdSendUntilLit(level, lamp[0], lamp[0]);
                        phase[0] = 3;
                    }
                    case 3 -> {
                        boolean over =
                                holdsEnded(pair) && !level.getLightEngine().hasLightWork();
                        deadline.await(helper, level, over, () -> "the hold of " + pair + " did not end");
                        if (!over || waited[0]++ < SETTLE_TICKS) {
                            return;
                        }
                        checkStoredLight(helper, level, home, lampChunk, lamp[0], "after the hold");
                        before[0] = storedLight(level, home, lamp[0]);
                        // The hold is over. The sends so far are not the ones the case is about.
                        LightSyncSends.unwatch(id);
                        LightSyncSends.watch(id);
                        if (change) {
                            level.setBlock(lamp[0], Blocks.AIR.defaultBlockState(), 3);
                        }
                        waited[0] = 0;
                        phase[0] = 4;
                    }
                    case 4 -> {
                        // The light engine runs in wall time, so a tick count after the change may end before
                        // it publishes.
                        if (!steady(level, home, lampChunk, lamp[0], steady, waited, marker)) {
                            return;
                        }
                        waited[0] = 0;
                        phase[0] = 5;
                    }
                    default -> {
                        if (waited[0]++ < NET_WAIT_TICKS) {
                            return;
                        }
                        if (change) {
                            checkSentAfterChange(helper, level, home, lampChunk, lamp[0], before[0], id);
                        } else {
                            BitSet sent = LightSyncSends.sectionsCarried(id, home);
                            sent.or(LightSyncSends.sectionsCarried(id, lampChunk));
                            if (!sent.isEmpty()) {
                                throw failure(
                                        helper,
                                        "the light did not change, and the net sent the player the sections %s",
                                        sent);
                            }
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

    private static void forEach(ChunkPos home, Consumer<ChunkPos> body) {
        for (int x = home.x() - REACH_X; x <= home.x() + REACH_X; x++) {
            for (int z = home.z() - REACH_Z; z <= home.z() + REACH_Z; z++) {
                body.accept(new ChunkPos(x, z));
            }
        }
    }

    private static boolean holdsEnded(List<ChunkPos> chunks) {
        return LightSync.heldChunks().stream()
                .noneMatch(line -> chunks.stream().anyMatch(chunk -> line.contains(chunk.toString())));
    }

    /** The rows the case compares, round the lamp. */
    private static BoundingBox box(ChunkPos home, ChunkPos lampChunk, BlockPos lamp) {
        return new BoundingBox(
                home.getMinBlockX(),
                lamp.getY() - ROWS,
                home.getMinBlockZ(),
                lampChunk.getMaxBlockX(),
                lamp.getY() + ROWS,
                home.getMaxBlockZ());
    }

    /** The server stores the light of the blocks in both chunks of the pair. */
    private static void checkStoredLight(
            GameTestHelper helper, ServerLevel level, ChunkPos home, ChunkPos lampChunk, BlockPos lamp, String when) {
        BlockLightOracle.Field expected = BlockLightOracle.expected(level, box(home, lampChunk, lamp));
        List<BlockLightOracle.Mismatch> stored = BlockLightOracle.mismatches(level, expected);
        if (!stored.isEmpty()) {
            throw failure(
                    helper,
                    "%s: the server stores block light the blocks do not give in %d cells; the first: %s",
                    when,
                    stored.size(),
                    stored.subList(0, Math.min(6, stored.size())));
        }
    }

    /** The block light the engine stores for every cell of the chunk, in the rows of the case. */
    private static int[] storedLight(ServerLevel level, ChunkPos chunk, BlockPos lamp) {
        int[] light = new int[16 * 16 * (2 * ROWS + 1)];
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int at = 0;
        for (int x = chunk.getMinBlockX(); x <= chunk.getMaxBlockX(); x++) {
            for (int z = chunk.getMinBlockZ(); z <= chunk.getMaxBlockZ(); z++) {
                for (int y = lamp.getY() - ROWS; y <= lamp.getY() + ROWS; y++) {
                    light[at++] = level.getBrightness(LightLayer.BLOCK, pos.set(x, y, z));
                }
            }
        }
        return light;
    }

    /** The light sections, counted from the lowest, in which the stored light of the chunk differs from {@code before}. */
    private static BitSet changedSections(ServerLevel level, ChunkPos chunk, BlockPos lamp, int[] before) {
        int[] after = storedLight(level, chunk, lamp);
        int minLightSection = level.getLightEngine().getMinLightSection();
        BitSet sections = new BitSet();
        for (int i = 0; i < after.length; i++) {
            if (after[i] != before[i]) {
                sections.set(((lamp.getY() - ROWS + i % (2 * ROWS + 1)) >> 4) - minLightSection);
            }
        }
        return sections;
    }

    /** The net sent the player every section of the player's chunk whose stored light changed, and the stored light is the oracle's. */
    private static void checkSentAfterChange(
            GameTestHelper helper,
            ServerLevel level,
            ChunkPos home,
            ChunkPos lampChunk,
            BlockPos lamp,
            int[] before,
            UUID player) {
        checkStoredLight(helper, level, home, lampChunk, lamp, "after the change");
        BitSet changed = changedSections(level, home, lamp, before);
        if (changed.isEmpty()) {
            throw failure(helper, "removing the lamp changed no light in %s, so the case proves nothing", home);
        }
        BitSet unsent = (BitSet) changed.clone();
        unsent.andNot(LightSyncSends.sectionsCarried(player, home));
        if (!unsent.isEmpty()) {
            throw failure(
                    helper,
                    "the light of %s changed in the sections %s (counted from the lowest light section) after its hold ended, no broadcast reached the player, and the net did not send them, so the client keeps the old light",
                    home,
                    unsent);
        }
    }
}
