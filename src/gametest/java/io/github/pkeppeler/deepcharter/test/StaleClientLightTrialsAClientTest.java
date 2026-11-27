package io.github.pkeppeler.deepcharter.test;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;

/** Layer-structure trials, part 1 of 3 ({@link StaleClientLightCases}). */
public class StaleClientLightTrialsAClientTest extends StaleClientLightCases implements FabricClientGameTest {
    @Override
    void cases(ClientGameTestContext context, TestServerContext server) {
        layerTrials(context, server, 0);
    }
}
