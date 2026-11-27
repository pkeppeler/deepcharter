package io.github.pkeppeler.deepcharter.test;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;

/** Layer-structure trials, part 3 of 3 ({@link StaleClientLightCases}). */
public class StaleClientLightTrialsCClientTest extends StaleClientLightCases implements FabricClientGameTest {
    @Override
    void cases(ClientGameTestContext context, TestServerContext server) {
        layerTrials(context, server, 2);
    }
}
