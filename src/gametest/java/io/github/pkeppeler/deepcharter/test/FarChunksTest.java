package io.github.pkeppeler.deepcharter.test;

import io.github.pkeppeler.deepcharter.test.support.FarChunks;
import io.github.pkeppeler.deepcharter.test.support.TestTicks;
import java.util.concurrent.atomic.AtomicReference;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

/** Server GameTests for {@link FarChunks}. */
public class FarChunksTest {
    private static final String NESTED_MESSAGE =
            "FarChunks.awaitEntityTicking must be called from the test method, not from a tick callback";

    /** A chunk a finished test forced, to be found unforced a few ticks later. */
    private record Forced(ServerLevel level, ChunkPos chunk) {}

    private static final AtomicReference<Forced> FORCED_BY_A_FINISHED_TEST = new AtomicReference<>();
    /** Ticks to wait after the forcing test succeeded: the runner ends a test at the end of the tick that succeeds it. */
    private static final int TICKS_AFTER_FINISH = 3;

    @GameTest
    public void aChunkIsForcedWhileTheTestRuns(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ChunkPos chunk = new ChunkPos(4000, 4000);
        FarChunks.force(helper, level, chunk.x(), chunk.z());
        if (!level.getForceLoadedChunks().contains(chunk.pack())) {
            throw helper.assertionException("the chunk should be forced while the test runs");
        }
        FORCED_BY_A_FINISHED_TEST.set(new Forced(level, chunk));
        helper.succeed();
    }

    /** #569: a chunk left forced pins up to 17 x 17 chunks of proto chunks, and the live heap grew with the suite. */
    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS)
    public void aChunkIsNotForcedAfterTheTestThatForcedItFinished(GameTestHelper helper) {
        FarChunks.Deadline deadline = FarChunks.deadline();
        int[] sinceSeen = {0};
        TestTicks.each(helper, () -> {
            Forced forced = FORCED_BY_A_FINISHED_TEST.get();
            deadline.await(helper, helper.getLevel(), forced != null, () -> "no test forced a chunk");
            if (forced == null || ++sinceSeen[0] < TICKS_AFTER_FINISH) {
                return;
            }
            if (forced.level().getForceLoadedChunks().contains(forced.chunk().pack())) {
                throw helper.assertionException("chunk %s is still forced after its test finished", forced.chunk());
            }
            helper.succeed();
        });
    }

    @GameTest
    public void awaitingFromATickCallbackFailsFast(GameTestHelper helper) {
        helper.runAfterDelay(2, () -> {
            try {
                FarChunks.awaitEntityTicking(helper, helper.getLevel(), BlockPos.ZERO, () -> {});
            } catch (IllegalStateException expected) {
                if (!NESTED_MESSAGE.equals(expected.getMessage())) {
                    throw helper.assertionException("wrong message: " + expected.getMessage());
                }
                helper.succeed();
                return;
            }
            throw helper.assertionException("a nested call should throw IllegalStateException");
        });
    }
}
