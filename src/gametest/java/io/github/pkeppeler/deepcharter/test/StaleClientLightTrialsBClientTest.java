package io.github.pkeppeler.deepcharter.test;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;

/** Layer-structure trials, part 2 of 3 ({@link StaleClientLightCases}). */
public class StaleClientLightTrialsBClientTest extends StaleClientLightCases {
    @Override
    void cases(ClientGameTestContext context, TestServerContext server) {
        layerTrials(context, server, 1);
    }
}
