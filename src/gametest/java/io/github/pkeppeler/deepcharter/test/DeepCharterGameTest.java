package io.github.pkeppeler.deepcharter.test;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.fabricmc.loader.api.FabricLoader;

import net.minecraft.gametest.framework.GameTestHelper;

public class DeepCharterGameTest {
	@GameTest
	public void modIsLoaded(GameTestHelper helper) {
		FabricLoader loader = FabricLoader.getInstance();
		if (!loader.isModLoaded("deepcharter")) {
			throw helper.assertionException("deepcharter is not loaded");
		}
		if (!loader.isModLoaded("deepcharter-test")) {
			throw helper.assertionException("deepcharter-test is not loaded");
		}
		helper.succeed();
	}
}
