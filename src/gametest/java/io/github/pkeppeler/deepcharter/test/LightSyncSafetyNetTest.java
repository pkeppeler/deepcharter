package io.github.pkeppeler.deepcharter.test;

import com.google.errorprone.annotations.FormatMethod;
import com.google.errorprone.annotations.FormatString;
import io.github.pkeppeler.deepcharter.layer.LightSync;
import io.github.pkeppeler.deepcharter.test.support.BlockLightOracle;
import io.github.pkeppeler.deepcharter.test.support.FarChunks;
import io.github.pkeppeler.deepcharter.test.support.LightBroadcastSuppression;
import io.github.pkeppeler.deepcharter.test.support.LightSyncSends;
import io.github.pkeppeler.deepcharter.test.support.MockPlayer;
import io.github.pkeppeler.deepcharter.test.support.MockPlayers;
import io.github.pkeppeler.deepcharter.test.support.TestLayers;
import io.github.pkeppeler.deepcharter.test.support.TestTicks;
import java.util.BitSet;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
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
    /** Ticks the net compares for after a hold ended, with a margin: restates its window. */
    private static final int NET_WAIT_TICKS = 100 + 40;
    /** Ticks the stored light must not change before the case takes it as settled. */
    private static final int STEADY_TICKS = 6;

    /** Ticks between two comparisons of the net: restates its interval. */
    private static final int NET_INTERVAL = 10;

    private static final int MAX_TICKS = FarChunks.AWAIT_BUDGET_TICKS + 1_200;

    @GameTest(dimension = SAMPLE, maxTicks = MAX_TICKS)
    public void aLightChangeAfterAHoldNoBroadcastReachesAPlayerIsSent(GameTestHelper helper) {
        run(helper, true);
    }

    @GameTest(dimension = SAMPLE, maxTicks = MAX_TICKS)
    public void noLightPacketIsSentWhenTheLightDidNotChangeAfterAHold(GameTestHelper helper) {
        run(helper, false);
    }

    /**
     * The lamp is a magma block, whose light of 3 spreads two blocks: it is published and withdrawn in a few ticks, where a sea
     * lantern's takes seconds, and the case needs the light to come and go inside one interval of the net.
     *
     * <p>The light the player holds is neither the light before a change nor the light after it, and the published light returns to the
     * first between two of the net's samples. The chunk lies dark; a lamp is placed; once its light is published the chunk is sent
     * again and the lamp is removed in the same tick, so the player holds the lit chunk and the published light is dark again a few
     * ticks later, inside one interval of the net. A net that compares with its own last sample sees dark, then dark. A first round,
     * before the hold, measures how long the lamp's light takes to appear; the round that counts starts so that the lit light falls
     * just after one of the net's samples, which come every {@value #NET_INTERVAL} ticks from the end of the hold.
     */
    @GameTest(dimension = SAMPLE, maxTicks = MAX_TICKS)
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
        int[] holdEnd = {0};
        int[] placedAt = {0};
        int[] litDelay = {0};
        int[] sentBefore = {0};
        BlockPos[] lamp = {null};
        int[][] dark = {null};
        int[][] steady = {null};
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
                int tick = level.getServer().getTickCount();
                switch (phase[0]) {
                    case 0 -> {
                        forEach(home, chunk -> {
                            FarChunks.force(helper, level, chunk.x(), chunk.z());
                            level.getChunk(chunk.x(), chunk.z(), ChunkStatus.FULL);
                        });
                        phase[0] = 1;
                    }
                    case 1 -> {
                        boolean ticking = level.isPositionEntityTicking(home.getWorldPosition());
                        deadline.await(helper, level, ticking, () -> "the chunk " + home + " did not tick");
                        if (!ticking) {
                            return;
                        }
                        mock.teleportTo(
                                level,
                                TestLayers.vec(
                                        TestLayers.SURFACE, home.getMiddleBlockX() + 0.5, 100, home.getMiddleBlockZ()),
                                0f,
                                0f);
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
                        if (!steady(level, home, lamp[0], steady, waited)) {
                            return;
                        }
                        dark[0] = steady[0];
                        placedAt[0] = tick;
                        level.setBlock(lamp[0], Blocks.MAGMA_BLOCK.defaultBlockState(), 3);
                        phase[0] = 4;
                    }
                    case 4 -> {
                        if (changedSections(level, home, lamp[0], dark[0]).isEmpty()) {
                            return;
                        }
                        litDelay[0] = tick - placedAt[0];
                        level.setBlock(lamp[0], Blocks.AIR.defaultBlockState(), 3);
                        phase[0] = 5;
                    }
                    case 5 -> {
                        if (!steady(level, home, lamp[0], steady, waited)) {
                            return;
                        }
                        // The hold of a draw: the chunks get a watch of the net, and the chunk lies dark afterwards.
                        level.setBlock(lamp[0], Blocks.MAGMA_BLOCK.defaultBlockState(), 3);
                        LightSync.holdSendUntilLit(level, lamp[0], lamp[0]);
                        level.setBlock(lamp[0], Blocks.AIR.defaultBlockState(), 3);
                        phase[0] = 6;
                    }
                    case 6 -> {
                        boolean over = holdsEnded(pair);
                        deadline.await(helper, level, over, () -> "the hold of " + pair + " did not end");
                        if (!over) {
                            return;
                        }
                        if (holdEnd[0] == 0) {
                            // The hold ended in the end-of-tick step of the tick before this one.
                            holdEnd[0] = tick - 1;
                        }
                        if (!steady(level, home, lamp[0], steady, waited)) {
                            return;
                        }
                        checkStoredLight(helper, level, home, lampChunk, lamp[0], "dark");
                        dark[0] = steady[0];
                        phase[0] = 7;
                    }
                    case 7 -> {
                        // The lit light is published litDelay ticks after the lamp is placed: two ticks after a sample.
                        if ((tick + litDelay[0] - holdEnd[0] - 2) % NET_INTERVAL != 0) {
                            return;
                        }
                        placedAt[0] = tick;
                        sentBefore[0] = mock.timesSent(home);
                        level.setBlock(lamp[0], Blocks.MAGMA_BLOCK.defaultBlockState(), 3);
                        phase[0] = 8;
                    }
                    case 8 -> {
                        BitSet lit = changedSections(level, home, lamp[0], dark[0]);
                        if (lit.isEmpty()) {
                            return;
                        }
                        changed[0] = lit;
                        // The player is sent the lit chunk, and the lamp goes out in the same tick: the published light
                        // is dark
                        // again before another comparison.
                        net.minecraft.server.level.ServerPlayer player = mock.player();
                        player.connection.chunkSender.dropChunk(player, home);
                        player.connection.chunkSender.markChunkPendingToSend(level.getChunk(home.x(), home.z()));
                        level.setBlock(lamp[0], Blocks.AIR.defaultBlockState(), 3);
                        phase[0] = 9;
                    }
                    case 9 -> {
                        boolean back =
                                changedSections(level, home, lamp[0], dark[0]).isEmpty();
                        boolean sent = mock.timesSent(home) > sentBefore[0];
                        if (!back || !sent) {
                            return;
                        }
                        // The sends so far are not the ones the case is about.
                        LightSyncSends.unwatch(id);
                        LightSyncSends.watch(id);
                        waited[0] = 0;
                        phase[0] = 10;
                    }
                    default -> {
                        if (waited[0]++ < NET_INTERVAL + 5) {
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

    /**
     * A chunk of a hold's 3x3 that is loaded but cannot be sent when the hold is requested (it does not tick yet) is watched too: it
     * is sent later, unheld, and a light change after that must reach the player. The player's chunk ticks and the chunk next to it,
     * the lamp's, ticks through it; the chunk beyond is loaded and neither ticks nor can be sent until the case forces it.
     */
    @GameTest(dimension = SAMPLE, maxTicks = MAX_TICKS)
    public void aNeighbourThatCannotBeSentWhenTheHoldIsRequestedIsWatched(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        int simulationBefore = server.getPlayerList().getSimulationDistance();
        int viewBefore = server.getPlayerList().getViewDistance();
        server.getPlayerList().setSimulationDistance(1);
        server.getPlayerList().setViewDistance(3);
        MockPlayer mock = MockPlayers.join(helper, "net-neighbour");
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
        ChunkPos home = new ChunkPos((PLAYER_X >> 4) + 80, PLAYER_Z >> 4);
        ChunkPos lampChunk = new ChunkPos(home.x() + 1, home.z());
        ChunkPos beyond = new ChunkPos(home.x() + 2, home.z());
        LightSyncSends.watch(id);
        FarChunks.Deadline deadline = FarChunks.deadline(120);
        int[] phase = {0};
        int[] waited = {0};
        BlockPos[] lamp = {null};
        int[][] lit = {null};
        int[][] steady = {null};
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
                        boolean ticking = level.isPositionEntityTicking(home.getWorldPosition());
                        deadline.await(helper, level, ticking, () -> "the chunk " + home + " did not tick");
                        if (!ticking) {
                            return;
                        }
                        mock.teleportTo(
                                level,
                                TestLayers.vec(
                                        TestLayers.SURFACE, home.getMiddleBlockX() + 0.5, 100, home.getMiddleBlockZ()),
                                0f,
                                0f);
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
                        // The last column of the lamp's chunk: the light spills across the border into the chunk
                        // beyond.
                        int x = beyond.getMinBlockX() - 1;
                        int z = beyond.getMiddleBlockZ();
                        lamp[0] = new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) + 2, z);
                        level.setBlock(lamp[0], Blocks.SEA_LANTERN.defaultBlockState(), 3);
                        LightSync.holdSendUntilLit(level, lamp[0], lamp[0]);
                        phase[0] = 3;
                    }
                    case 3 -> {
                        boolean over = holdsEnded(List.of(lampChunk));
                        deadline.await(helper, level, over, () -> "the hold of " + lampChunk + " did not end");
                        if (!over || !steady(level, beyond, lamp[0], steady, waited)) {
                            return;
                        }
                        lit[0] = steady[0];
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

    /** True once the stored light of the chunk has been the same for {@value #STEADY_TICKS} ticks in a row; {@code last} holds it. */
    private static boolean steady(ServerLevel level, ChunkPos chunk, BlockPos lamp, int[][] last, int[] same) {
        int[] now = storedLight(level, chunk, lamp);
        if (last[0] != null && java.util.Arrays.equals(last[0], now)) {
            same[0]++;
        } else {
            same[0] = 0;
        }
        last[0] = now;
        if (same[0] >= STEADY_TICKS) {
            same[0] = 0;
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
                        boolean ticking = level.isPositionEntityTicking(home.getWorldPosition());
                        deadline.await(helper, level, ticking, () -> "the chunk " + home + " did not tick");
                        if (!ticking) {
                            return;
                        }
                        mock.teleportTo(
                                level,
                                TestLayers.vec(
                                        TestLayers.SURFACE, home.getMiddleBlockX() + 0.5, 100, home.getMiddleBlockZ()),
                                0f,
                                0f);
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

    @FormatMethod
    private static RuntimeException failure(
            GameTestHelper helper, @FormatString String format, @Nullable Object... args) {
        return helper.assertionException(Component.literal(String.format(format, args)));
    }
}
