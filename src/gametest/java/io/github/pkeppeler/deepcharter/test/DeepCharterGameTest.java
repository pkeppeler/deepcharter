package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.Failures.failure;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.gametest.framework.GameTestHelper;

public class DeepCharterGameTest {
    @GameTest
    public void modIsLoaded(GameTestHelper helper) {
        FabricLoader loader = FabricLoader.getInstance();
        if (!loader.isModLoaded("deepcharter")) {
            throw failure(helper, "deepcharter is not loaded");
        }
        if (!loader.isModLoaded("deepcharter-test")) {
            throw failure(helper, "deepcharter-test is not loaded");
        }
        helper.succeed();
    }
}
