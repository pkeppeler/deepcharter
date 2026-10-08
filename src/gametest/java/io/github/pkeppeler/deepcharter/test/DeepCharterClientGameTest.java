package io.github.pkeppeler.deepcharter.test;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.loader.api.FabricLoader;

import net.minecraft.server.level.ServerLevel;

import io.github.pkeppeler.deepcharter.layer.LayerChain;

public class DeepCharterClientGameTest implements FabricClientGameTest {
	@Override
	public void runTest(ClientGameTestContext context) {
		ClientTestLog.start(this);
		if (!FabricLoader.getInstance().isModLoaded("deepcharter")) {
			throw new AssertionError("deepcharter is not loaded on the client");
		}
		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			context.waitTicks(20);
			boolean loaded = context.computeOnClient(client -> client.level != null && client.player != null);
			if (!loaded) {
				throw new AssertionError("Singleplayer world did not load");
			}
			// A real world, without the test-only world preset: proves the shipped mod loads the layers.
			String problems = singleplayer.getServer().computeOnServer(server -> {
				StringBuilder out = new StringBuilder();
				int[] heights = {192, 256};
				for (int layer = 1; layer <= 2; layer++) {
					ServerLevel level = server.getLevel(LayerChain.dimension(layer));
					if (level == null) {
						out.append("layer_").append(layer).append(" is not loaded; ");
					} else if (level.getMinY() != 0 || level.getHeight() != heights[layer - 1]) {
						out.append("layer_").append(layer).append(" has minY ").append(level.getMinY())
								.append(" height ").append(level.getHeight()).append("; ");
					}
				}
				return out.toString();
			});
			if (!problems.isEmpty()) {
				throw new AssertionError("Shipped layer dimensions did not load in a real world: " + problems);
			}
		}
	}
}
