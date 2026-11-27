package io.github.pkeppeler.deepcharter.test;

import io.github.pkeppeler.deepcharter.colony.Colony;
import io.github.pkeppeler.deepcharter.colony.ColonySite;
import io.github.pkeppeler.deepcharter.test.support.ColonyChunks;
import io.github.pkeppeler.deepcharter.test.support.FarChunks;
import io.github.pkeppeler.deepcharter.test.support.MockPlayers;
import io.github.pkeppeler.deepcharter.test.support.ProbeInfos;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestInfo;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.AABB;

/** Server GameTests for {@link FarChunks}. */
public class FarChunksTest {
    private static final String NESTED_MESSAGE =
            "FarChunks.awaitEntityTicking must be called from the test method, not from a tick callback";

    /** A wait fails past its limit only after {@link FarChunks#MIN_WAITED_TICKS} ticks, or past the ceiling: each clause is needed (#1002). */
    @GameTest
    public void aWaitFailsOnlyPastItsLimitAndTheTickFloorOrTheCeiling(GameTestHelper helper) {
        long budget = 10_000_000_000L;
        int floor = FarChunks.MIN_WAITED_TICKS;
        boolean[] results = {
            FarChunks.Deadline.failed(budget + 1, budget, floor - 1),
            FarChunks.Deadline.failed(budget + 1, budget, floor),
            FarChunks.Deadline.failed(budget - 1, budget, floor * 10),
            FarChunks.Deadline.failed(FarChunks.CEILING_FACTOR * budget + 1, budget, 1),
            FarChunks.Deadline.failed(FarChunks.CEILING_FACTOR * budget, budget, 1)
        };
        boolean[] expected = {false, true, false, true, false};
        for (int i = 0; i < results.length; i++) {
            if (results[i] != expected[i]) {
                throw helper.assertionException("case %d failed the wait: %s, expected %s", i, results[i], expected[i]);
            }
        }
        helper.succeed();
    }

    @GameTest
    public void aChunkIsForcedWhileTheTestRuns(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ChunkPos chunk = new ChunkPos(4000, 4000);
        FarChunks.force(helper, level, chunk.x(), chunk.z());
        if (!level.getForceLoadedChunks().contains(chunk.pack())) {
            throw helper.assertionException("the chunk should be forced while the test runs");
        }
        helper.succeed();
    }

    /**
     * Forcing a chunk only takes a ticket: {@code ServerLevel.setChunkForced} would load it on the server thread before returning, and
     * that stalls every test in the batch while it generates (#1016). The chunk is not visible on the tick that forced it, and
     * {@code whenBlocksLoaded} then waits for it.
     */
    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS)
    public void forcingAChunkDoesNotLoadItOnTheServerThread(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ChunkPos chunk = new ChunkPos(4020, 4020);
        FarChunks.force(helper, level, chunk.x(), chunk.z());
        if (level.getChunkSource().getChunkNow(chunk.x(), chunk.z()) != null) {
            throw helper.assertionException("forcing the chunk loaded it on the server thread");
        }
        FarChunks.whenBlocksLoaded(helper, List.of(new FarChunks.At(level, chunk.x(), chunk.z())), helper::succeed);
    }

    /**
     * A chunk a test forced is released when that test finishes, or the suite's heap grows with it (#569). The test ends a probe of itself,
     * which shares its test block and so its bounds, as the runner ends it, and needs no other test (#1163).
     */
    @GameTest
    public void aChunkIsNotForcedAfterTheTestThatForcedItFinished(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ChunkPos chunk = new ChunkPos(4005, 4005);
        FarChunks.force(helper, level, chunk.x(), chunk.z());
        if (!level.getForceLoadedChunks().contains(chunk.pack())) {
            throw helper.assertionException("the chunk should be forced while the test runs");
        }
        GameTestInfo owner = MockPlayers.testInfo(helper);
        GameTestInfo probe = ProbeInfos.of(owner, level);
        probe.setTestBlockPos(owner.getTestBlockPos());
        // finish() also releases the owner's ItemSpawns and ColumnCost bookkeeping on the shared bounds, harmless here.
        ProbeInfos.finish(probe);
        if (level.getForceLoadedChunks().contains(chunk.pack())) {
            throw helper.assertionException("chunk %s is still forced after its test finished", chunk);
        }
        helper.succeed();
    }

    /**
     * The runner unforces every forced chunk of the level when a batch finishes (#669: it dropped the colony pad, and the test that
     * needed the pad next found its chunk unloaded). That is done here for the pad's chunks, as it does it.
     */
    @GameTest
    public void thePadStaysForcedWhenTheRunnerUnforcesEveryChunkAtTheEndOfABatch(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ColonySite.Placed colony = Colony.placed(level.getServer()).orElseThrow();
        // force-ok: this test exercises the pad pin itself, in the shard that holds it
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
