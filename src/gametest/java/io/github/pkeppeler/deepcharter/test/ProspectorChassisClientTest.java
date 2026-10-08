package io.github.pkeppeler.deepcharter.test;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

/** Client GameTest for #82. */
public class ProspectorChassisClientTest implements FabricClientGameTest {
	// Filled by #82. The class is already registered in the test fabric.mod.json.

	@Override
	public void runTest(ClientGameTestContext context) {
		ClientTestLog.start(this);
		// Empty so that the stub passes: #82 writes the test.
	}
}
