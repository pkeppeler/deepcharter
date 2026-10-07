package io.github.pkeppeler.deepcharter.test;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import io.github.pkeppeler.deepcharter.test.support.TwoPlayerServer;

/** Proves the {@link TwoPlayerServer} harness: the real client sees itself and the mock player. */
public class TwoPlayerClientTest implements FabricClientGameTest {
	@Override
	public void runTest(ClientGameTestContext context) {
		try (TwoPlayerServer two = TwoPlayerServer.start(context)) {
			int seen = context.computeOnClient(client -> client.level.players().size());
			if (seen != 2) {
				throw new AssertionError("Expected the real client to see 2 players, saw " + seen);
			}
			boolean mockVisible = context.computeOnClient(
					client -> client.level.getPlayerByUUID(two.mock().player().getUUID()) != null);
			if (!mockVisible) {
				throw new AssertionError("The real client does not see the mock player");
			}
		}
	}
}
