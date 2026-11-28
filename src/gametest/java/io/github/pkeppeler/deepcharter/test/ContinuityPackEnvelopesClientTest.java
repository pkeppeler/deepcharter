package io.github.pkeppeler.deepcharter.test;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

/** #1371: a pack whose laugh envelope has a broken length still ends in the button ({@link ContinuityPackCases}). */
public class ContinuityPackEnvelopesClientTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        ClientTestLog.start(this);
        ContinuityPackCases.runCases(context, false, ContinuityPackCases.BROKEN_LENGTHS);
    }
}
