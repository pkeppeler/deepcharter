package io.github.pkeppeler.deepcharter.test;

import io.github.pkeppeler.deepcharter.test.mixin.ChunkMapAccess;
import java.util.concurrent.atomic.AtomicBoolean;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

/** Server GameTests for {@code ChunkMapUnloadMixin} (#807). */
public class ChunkUnloadTest {
    /**
     * Vanilla runs a queued unload tail only while the tick has time left, so a server that ticks over budget keeps the chunks of a
     * finished batch. The mixin must run it when told there is no time.
     */
    @GameTest
    public void aQueuedUnloadFinishesWhenTheTickHasNoTimeLeft(GameTestHelper helper) {
        ChunkMapAccess chunkMap = (ChunkMapAccess) helper.getLevel().getChunkSource().chunkMap;
        AtomicBoolean ran = new AtomicBoolean();
        chunkMap.deepcharter$unloadQueue().add(() -> ran.set(true));
        chunkMap.deepcharter$processUnloads(() -> false);
        if (!ran.get()) {
            throw helper.assertionException("a queued unload tail did not run in a tick with no time left");
        }
        helper.succeed();
    }
}
