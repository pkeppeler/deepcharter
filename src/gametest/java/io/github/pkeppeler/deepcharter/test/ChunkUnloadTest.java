package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.Failures.failure;

import io.github.pkeppeler.deepcharter.test.mixin.ChunkMapAccess;
import java.util.concurrent.atomic.AtomicBoolean;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/** Server GameTests for {@code ChunkMapUnloadMixin} (#807) and {@code ChunkMapSaveMixin} (#816). */
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

    /** A half-generated chunk is not written: its data is only what the world seed makes again, and the writes outran the IO thread (#816). */
    @GameTest
    public void aHalfGeneratedChunkIsNotSaved(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ChunkMapAccess chunkMap = (ChunkMapAccess) level.getChunkSource().chunkMap;
        // Far from every column a test uses, so a write here (the failure) reaches no other test.
        ProtoChunk half = new ProtoChunk(
                new ChunkPos(1_000_000, 1_000_000), UpgradeData.EMPTY, level, level.palettedContainerFactory(), null);
        half.setPersistedStatus(ChunkStatus.FEATURES);
        half.markUnsaved();
        if (chunkMap.deepcharter$save(half)) {
            throw failure(helper, "a half-generated chunk was written");
        }
        if (half.isUnsaved()) {
            throw failure(helper, "a half-generated chunk was left marked unsaved, so the next unload tries again");
        }
        helper.succeed();
    }

    /** A full chunk still saves: the mixin must not skip every chunk. */
    @GameTest
    public void aFullChunkIsSaved(GameTestHelper helper) {
        ChunkMapAccess chunkMap = (ChunkMapAccess) helper.getLevel().getChunkSource().chunkMap;
        // sync-load: the chunk of the test's own structure, which is loaded
        LevelChunk full = helper.getLevel().getChunkAt(helper.absolutePos(BlockPos.ZERO));
        full.markUnsaved();
        if (!chunkMap.deepcharter$save(full)) {
            throw failure(helper, "a full chunk marked unsaved was not written");
        }
        helper.succeed();
    }
}
