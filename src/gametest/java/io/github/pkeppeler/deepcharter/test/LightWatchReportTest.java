package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.Failures.failure;

import io.github.pkeppeler.deepcharter.layer.LightSync;
import io.github.pkeppeler.deepcharter.test.support.CampaignEnvironments;
import io.github.pkeppeler.deepcharter.test.support.FarChunks;
import io.github.pkeppeler.deepcharter.test.support.LightEngineGate;
import io.github.pkeppeler.deepcharter.test.support.LogCapture;
import io.github.pkeppeler.deepcharter.test.support.MockPlayer;
import io.github.pkeppeler.deepcharter.test.support.MockPlayers;
import io.github.pkeppeler.deepcharter.test.support.TestLayers;
import io.github.pkeppeler.deepcharter.test.support.TestTicks;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;

/**
 * The safety net's report that a watched chunk has waited too long to be sendable (#1297). A chunk is sendable
 * only once it ticks, so a loaded chunk on the rim of the ticking area waits for that, not for the light: it is not
 * reported, and its watch ends with the hold instead of waiting as long as it stays loaded; it starts again when the
 * chunk is sent. A ticking chunk whose send the light holds back is reported.
 *
 * <p>One forced chunk ticks with its ring; the ring beyond that is loaded and does not tick. The hold is drawn on a
 * chunk of the first ring, so its 3x3 reaches into the second.
 */
public class LightWatchReportTest {
    private static final String SAMPLE = "deepcharter:campaign_sample";
    /** Far from the cells of the other light tests (LightSyncRingHoldTest ends at x -2207). */
    private static final ChunkPos UNTICKED_SITE = new ChunkPos((-40_000 >> 4) + 350, 40_000 >> 4);

    private static final ChunkPos GATED_SITE = new ChunkPos(UNTICKED_SITE.x() + 20, UNTICKED_SITE.z());

    private static final ChunkPos DORMANT_SITE = new ChunkPos(UNTICKED_SITE.x() + 40, UNTICKED_SITE.z());
    /** Ticks past the report's limit the test still listens, so a report on the limit's tick is not missed. */
    private static final int GRACE_TICKS = 20;

    private static final int MAX_TICKS = FarChunks.AWAIT_BUDGET_TICKS + 1_200;

    /** A loaded chunk that does not tick, next to a hold, is never reported as waiting to be sendable. */
    @GameTest(environment = CampaignEnvironments.C, dimension = SAMPLE, maxTicks = MAX_TICKS)
    public void aLoadedChunkThatDoesNotTickIsNotReportedAsWaitingToBeSent(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ChunkPos drawn = new ChunkPos(UNTICKED_SITE.x() + 1, UNTICKED_SITE.z());
        ChunkPos rim = new ChunkPos(UNTICKED_SITE.x() + 2, UNTICKED_SITE.z());
        FarChunks.Deadline deadline = FarChunks.deadline(FarChunks.WAIT_SECONDS);
        LogCapture capture = LogCapture.start(rim.toString());
        boolean[] held = {false};
        int[] heldFor = {0};
        TestTicks.each(helper, () -> {
            if (!held[0]) {
                FarChunks.force(helper, level, UNTICKED_SITE.x(), UNTICKED_SITE.z());
                boolean ready = level.getChunkSource().chunkMap.getChunkToSend(drawn.pack()) != null
                        && level.getChunkSource().getChunkNow(rim.x(), rim.z()) != null;
                deadline.await(helper, level, ready, () -> drawn + " was not sendable or " + rim + " not loaded");
                if (!ready) {
                    return;
                }
                if (ticking(level, rim)) {
                    throw failure(helper, "%s ticks, so the case proves nothing", rim);
                }
                LightSync.holdSendUntilLit(level, drawn);
                held[0] = true;
                return;
            }
            if (!LightSync.timedOut(heldFor[0]++ - GRACE_TICKS)) {
                return;
            }
            List<String> reported = capture.warnings().stream()
                    .filter(message -> message.contains("to be sendable"))
                    .toList();
            if (!reported.isEmpty()) {
                throw failure(helper, "a chunk that does not tick was reported as waiting for its light: %s", reported);
            }
            helper.succeed();
        });
    }

    /**
     * Once the hold is over, nothing in its 3x3 is watched, the loaded chunk that does not tick included. When that
     * chunk comes to tick and is sent to a player, it is watched again.
     */
    @GameTest(environment = CampaignEnvironments.C, dimension = SAMPLE, maxTicks = MAX_TICKS * 2)
    public void aWatchOnALoadedChunkThatDoesNotTickEndsWithTheHoldAndStartsAgainWhenItIsSent(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ChunkPos drawn = new ChunkPos(DORMANT_SITE.x() + 1, DORMANT_SITE.z());
        ChunkPos rim = new ChunkPos(DORMANT_SITE.x() + 2, DORMANT_SITE.z());
        FarChunks.Deadline deadline = FarChunks.deadline(FarChunks.WAIT_SECONDS);
        MockPlayer[] mock = {null};
        int[] phase = {0};
        int[] heldFor = {0};
        TestTicks.each(helper, () -> {
            switch (phase[0]) {
                case 0 -> {
                    FarChunks.force(helper, level, DORMANT_SITE.x(), DORMANT_SITE.z());
                    boolean ready = level.getChunkSource().chunkMap.getChunkToSend(drawn.pack()) != null
                            && level.getChunkSource().getChunkNow(rim.x(), rim.z()) != null;
                    deadline.await(helper, level, ready, () -> drawn + " was not sendable or " + rim + " not loaded");
                    if (!ready) {
                        return;
                    }
                    if (ticking(level, rim)) {
                        throw failure(helper, "%s ticks, so the case proves nothing", rim);
                    }
                    LightSync.holdSendUntilLit(level, drawn);
                    phase[0] = 1;
                }
                case 1 -> {
                    List<ChunkPos> watched = ChunkPos.rangeClosed(drawn, 1)
                            .filter(near -> LightSync.watching(level, near))
                            .toList();
                    if (watched.isEmpty()) {
                        mock[0] = MockPlayers.join(helper, "dormant-watch-viewer");
                        mock[0].player().setGameMode(GameType.SPECTATOR);
                        mock[0].teleportTo(
                                level,
                                TestLayers.vec(
                                        TestLayers.SURFACE,
                                        DORMANT_SITE.getMiddleBlockX() + 0.5,
                                        100,
                                        DORMANT_SITE.getMiddleBlockZ()),
                                0f,
                                0f);
                        FarChunks.force(helper, level, rim.x(), rim.z());
                        phase[0] = 2;
                    } else if (LightSync.timedOut(heldFor[0]++ - GRACE_TICKS)) {
                        throw failure(helper, "%s are still watched long after the hold of %s", watched, drawn);
                    }
                }
                default -> {
                    boolean sent = mock[0] != null && mock[0].chunksSent().contains(rim);
                    deadline.await(helper, level, sent, () -> rim + " was not sent to the player once it ticked");
                    if (!sent) {
                        return;
                    }
                    if (!LightSync.watching(level, rim)) {
                        throw failure(helper, "%s was sent and is not watched again", rim);
                    }
                    helper.succeed();
                }
            }
        });
    }

    private static boolean ticking(ServerLevel level, ChunkPos chunk) {
        ChunkHolder holder = level.getChunkSource().chunkMap.getUpdatingChunkIfPresent(chunk.pack());
        return holder != null && holder.getTickingChunk() != null;
    }

    /** A ticking chunk that the light keeps from being sent is still reported. */
    @GameTest(environment = CampaignEnvironments.C, dimension = SAMPLE, maxTicks = MAX_TICKS)
    public void aTickingChunkTheLightHoldsBackIsReported(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ChunkPos drawn = GATED_SITE;
        ChunkPos neighbour = new ChunkPos(drawn.x() + 1, drawn.z());
        FarChunks.Deadline deadline = FarChunks.deadline(FarChunks.WAIT_SECONDS);
        LogCapture capture = LogCapture.start(neighbour.toString());
        boolean[] held = {false};
        int[] heldFor = {0};
        TestTicks.each(helper, () -> {
            try {
                if (!held[0]) {
                    FarChunks.force(helper, level, drawn.x(), drawn.z());
                    boolean ready = ChunkPos.rangeClosed(drawn, 1)
                            .allMatch(near -> level.getChunkSource().chunkMap.getChunkToSend(near.pack()) != null);
                    deadline.await(helper, level, ready, () -> "the chunks round " + drawn + " were not sendable");
                    if (!ready) {
                        return;
                    }
                    LightEngineGate.close(drawn);
                    LightSync.holdSendUntilLit(level, drawn);
                    held[0] = true;
                    return;
                }
                boolean reported = capture.warnings().stream().anyMatch(message -> message.contains("to be sendable"));
                if (reported) {
                    LightEngineGate.release(drawn);
                    helper.succeed();
                    return;
                }
                if (LightSync.timedOut(heldFor[0]++ - GRACE_TICKS)) {
                    throw failure(
                            helper,
                            "%s ticks and the light has held it back past the limit, but it was not reported",
                            neighbour);
                }
            } catch (RuntimeException e) {
                LightEngineGate.release(drawn);
                throw e;
            }
        });
    }
}
