package io.github.pkeppeler.deepcharter.test;

import io.github.pkeppeler.deepcharter.test.support.FarChunks;
import io.github.pkeppeler.deepcharter.test.support.TestTicks;
import java.util.concurrent.atomic.AtomicReference;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.AABB;

/** Server GameTests for {@link FarChunks}. */
public class FarChunksTest {
    private static final String NESTED_MESSAGE =
            "FarChunks.awaitEntityTicking must be called from the test method, not from a tick callback";

    /** A chunk a finished test forced, to be found unforced a few ticks later. */
    private record Forced(ServerLevel level, ChunkPos chunk) {}

    private static final AtomicReference<Forced> FORCED_BY_A_FINISHED_TEST = new AtomicReference<>();

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
        TestTicks.each(helper, () -> {
            Forced forced = FORCED_BY_A_FINISHED_TEST.get();
            deadline.await(helper, helper.getLevel(), forced != null, () -> "no test forced a chunk");
            if (forced == null) {
                return;
            }
            if (forced.level().getForceLoadedChunks().contains(forced.chunk().pack())) {
                throw helper.assertionException("chunk %s is still forced after its test finished", forced.chunk());
            }
            helper.succeed();
        });
    }

    /** Two tests that force one chunk: the first to finish does not release it, the second does. */
    @GameTest
    public void aChunkTwoTestsForceStaysForcedUntilTheLastOfThemFinishes(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ChunkPos chunk = new ChunkPos(4010, 4010);
        AABB first = new AABB(0, 0, 0, 1, 1, 1);
        AABB second = new AABB(5, 0, 0, 6, 1, 1);
        FarChunks.force(first, level, chunk.x(), chunk.z());
        FarChunks.force(second, level, chunk.x(), chunk.z());
        FarChunks.release(first);
        if (!level.getForceLoadedChunks().contains(chunk.pack())) {
            FarChunks.release(second);
            throw helper.assertionException("the chunk was released while the second test still held it");
        }
        FarChunks.release(second);
        if (level.getForceLoadedChunks().contains(chunk.pack())) {
            throw helper.assertionException("the chunk should be released when the last test finished");
        }
        helper.succeed();
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
