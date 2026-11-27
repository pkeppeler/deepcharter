package io.github.pkeppeler.deepcharter.test;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;

/** The overworld, campaign-level and colony cases of #427 ({@link StaleClientLightCases}). */
public class StaleClientLightClientTest extends StaleClientLightCases implements FabricClientGameTest {
    @Override
    void cases(ClientGameTestContext context, TestServerContext server) {
        overworldCases(context, server);
    }
}
