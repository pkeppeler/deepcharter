package io.github.pkeppeler.deepcharter.test;

import io.github.pkeppeler.deepcharter.colony.Colony;
import io.github.pkeppeler.deepcharter.colony.ColonySite;
import io.github.pkeppeler.deepcharter.test.support.ColonyChunks;
import io.github.pkeppeler.deepcharter.test.support.ColonyPadPin;
import io.github.pkeppeler.deepcharter.test.support.FarChunks;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;

/** The pad pin is a test-mod entrypoint, so every server shard has it, and the pad is pinned (#1586). */
public class ColonyPadPinTest {
    @GameTest
    public void everyChunkOfThePadIsPinnedWithoutAnyTestAskingForIt(GameTestHelper helper) {
        boolean registered = FabricLoader.getInstance().getEntrypointContainers("main", ModInitializer.class).stream()
                .anyMatch(entry -> entry.getProvider().getMetadata().getId().equals("deepcharter-test")
                        && entry.getEntrypoint() instanceof ColonyPadPin);
        if (!registered) {
            throw helper.assertionException("ColonyPadPin is not a main entrypoint of deepcharter-test");
        }
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
