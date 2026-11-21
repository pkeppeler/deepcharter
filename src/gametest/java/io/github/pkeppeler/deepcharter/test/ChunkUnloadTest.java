package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.Failures.failure;

import io.github.pkeppeler.deepcharter.test.mixin.ChunkMapAccess;
import java.util.concurrent.atomic.AtomicBoolean;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

/** Server GameTests for {@code ChunkMapUnloadMixin} (#807). */
public class ChunkUnloadTest {
    /** The mixin must run a queued unload tail when told there is no time. */
    @GameTest
    public void aQueuedUnloadFinishesWhenTheTickHasNoTimeLeft(GameTestHelper helper) {
        ChunkMapAccess chunkMap = (ChunkMapAccess) helper.getLevel().getChunkSource().chunkMap;
        AtomicBoolean ran = new AtomicBoolean();
        chunkMap.deepcharter$unloadQueue().add(() -> ran.set(true));
        chunkMap.deepcharter$processUnloads(() -> false);
        if (!ran.get()) {
            throw failure(helper, "a queued unload tail did not run in a tick with no time left");
        }
        helper.succeed();
    }
}
