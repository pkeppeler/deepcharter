package io.github.pkeppeler.deepcharter.test;

import io.github.pkeppeler.deepcharter.colony.Colony;
import io.github.pkeppeler.deepcharter.colony.ColonySite;
import io.github.pkeppeler.deepcharter.test.support.ColonyChunks;
import io.github.pkeppeler.deepcharter.test.support.FarChunks;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;

/**
 * The colony pad is pinned in every GameTest world, whichever test classes a server shard holds (#1586). A test that puts a pod on
 * the pad (the tow in {@code HandbookChaptersSixToNineTest}) finds its chunk unloaded otherwise. This class asks nothing of the
 * pad itself, so it fails when only a listener inside another test class does the pinning.
 */
public class ColonyPadPinTest {
    @GameTest
    public void everyChunkOfThePadIsPinnedWithoutAnyTestAskingForIt(GameTestHelper helper) {
        ServerLevel level = helper.getLevel().getServer().overworld();
        ColonySite.Placed colony = Colony.placed(level.getServer()).orElseThrow();
        for (BlockPos pos : ColonyChunks.of(colony)) {
            if (!FarChunks.isPinned(level, pos.getX() >> 4, pos.getZ() >> 4)) {
                throw helper.assertionException("the pad chunk at %s is not pinned", pos);
            }
        }
        helper.succeed();
    }
}
