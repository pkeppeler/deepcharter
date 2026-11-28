package io.github.pkeppeler.deepcharter.test;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

/**
 * #1371: a dead person teleported to another dimension keeps their sequence, a death after RETURN TO WORK is a new one,
 * and a pack whose envelope has broken values still ends in the button ({@link ContinuityPackCases}).
 */
public class ContinuityPackClientTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        ClientTestLog.start(this);
        ContinuityPackCases.runCases(context, true, ContinuityPackCases.BROKEN_VALUES);
    }
}
