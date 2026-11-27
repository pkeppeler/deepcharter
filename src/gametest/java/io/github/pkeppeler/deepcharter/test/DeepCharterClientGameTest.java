package io.github.pkeppeler.deepcharter.test;

import io.github.pkeppeler.deepcharter.layer.LayerChain;
import io.github.pkeppeler.deepcharter.test.support.TestLayers;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.level.ServerLevel;

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
            // A real world, without the test-only world preset: proves the campaign level is in it and holds both
            // layers as bands.
            String problems = singleplayer.getServer().computeOnServer(server -> {
                StringBuilder out = new StringBuilder();
                int[] floors = {-256, -512};
                int[] heights = {192, 256};
                for (int layer = 1; layer <= 2; layer++) {
                    ServerLevel level = TestLayers.level(server, layer);
                    LayerChain.Band levelBand = TestLayers.band(level, layer);
                    if (levelBand.minY() != floors[layer - 1] || levelBand.height() != heights[layer - 1]) {
                        out.append("layer ")
                                .append(layer)
                                .append(" has minY ")
                                .append(levelBand.minY())
                                .append(" height ")
                                .append(levelBand.height())
                                .append("; ");
                    }
                }
                return out.toString();
            });
            if (!problems.isEmpty()) {
                throw new AssertionError("The campaign level did not hold the layers in a real world: " + problems);
            }
        }
    }
}
