package io.github.pkeppeler.deepcharter.test;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.loader.api.FabricLoader;

public class DeepCharterClientGameTest implements FabricClientGameTest {
	@Override
	public void runTest(ClientGameTestContext context) {
		if (!FabricLoader.getInstance().isModLoaded("deepcharter")) {
			throw new AssertionError("deepcharter is not loaded on the client");
		}
		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			context.waitTicks(20);
			boolean loaded = context.computeOnClient(client -> client.level != null && client.player != null);
			if (!loaded) {
				throw new AssertionError("Singleplayer world did not load");
			}
		}
	}
}
