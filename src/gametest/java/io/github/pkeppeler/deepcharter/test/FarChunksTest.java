package io.github.pkeppeler.deepcharter.test;

import io.github.pkeppeler.deepcharter.colony.Colony;
import io.github.pkeppeler.deepcharter.colony.ColonySite;
import io.github.pkeppeler.deepcharter.test.support.ColonyChunks;
import io.github.pkeppeler.deepcharter.test.support.FarChunks;
import io.github.pkeppeler.deepcharter.test.support.TestTicks;
import java.util.concurrent.atomic.AtomicReference;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestAssertException;
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

    /** A chunk a test forced is released when that test finishes, or the suite's heap grows with it (#569). */
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

    /**
     * A first tick that other tests' work stretches to a minute is not the waiter's time (#803): a deadline of 30 s survives it, counts
     * the ticks that follow at a second each, and still fails a wait that never settles.
     */
    @GameTest
    public void aStalledTickDoesNotUseUpTheDeadline(GameTestHelper helper) {
        long second = 1_000_000_000L;
        long[] now = {0};
        ServerLevel level = helper.getLevel();
        FarChunks.Deadline deadline = FarChunks.deadline(30, () -> now[0]);
        // 1 s of the 30 is used by each of these ticks, however long it took.
        now[0] += 90 * second;
        deadline.await(helper, level, false, () -> "ran out in a stalled first tick");
        for (int tick = 0; tick < 20; tick++) {
            now[0] += second;
            deadline.await(helper, level, false, () -> "ran out too soon");
        }
        now[0] += 120 * second;
        deadline.await(helper, level, false, () -> "ran out in a second stalled tick");
        for (int tick = 0; tick < 7; tick++) {
            now[0] += second;
            deadline.await(helper, level, false, () -> "ran out too soon after the second stall");
        }
        // 29 s are used now: the next tick uses the 30th, and the limit is passed at a second more.
        try {
            now[0] += second;
            deadline.await(helper, level, false, () -> "the limit is not passed yet");
            now[0] += second;
            deadline.await(helper, level, false, () -> "never settles");
        } catch (GameTestAssertException expected) {
            if (!expected.getMessage().contains("never settles")) {
                throw helper.assertionException("the wait failed too soon: " + expected.getMessage());
            }
            helper.succeed();
            return;
        }
        throw helper.assertionException("a wait that never settles must fail once the 30 s of ticks are used");
    }

    /**
     * The runner unforces every forced chunk of the level when a batch finishes (#669: it dropped the colony pad, and the test that
     * needed the pad next found its chunk unloaded). That is done here for the pad's chunks, as it does it.
     */
    @GameTest
    public void thePadStaysForcedWhenTheRunnerUnforcesEveryChunkAtTheEndOfABatch(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ColonySite.Placed colony = Colony.placed(level.getServer()).orElseThrow();
        ColonyChunks.force(level, colony);
        for (BlockPos pos : ColonyChunks.of(colony)) {
            // force-ok: simulates GameTestRunner$1.testCompleted's raw batch-end unforce; going through FarChunks would
            // bypass what is under test
            level.setChunkForced(pos.getX() >> 4, pos.getZ() >> 4, false);
            if (!level.getForceLoadedChunks().contains(ChunkPos.pack(pos.getX() >> 4, pos.getZ() >> 4))) {
                throw helper.assertionException("the pad chunk at %s was unforced", pos);
            }
        }
        helper.succeed();
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
