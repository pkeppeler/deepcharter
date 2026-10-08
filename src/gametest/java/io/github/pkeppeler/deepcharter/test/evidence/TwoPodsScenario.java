package io.github.pkeppeler.deepcharter.test.evidence;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import io.github.pkeppeler.deepcharter.test.TwoPlayerClientTest;
import io.github.pkeppeler.deepcharter.test.support.TwoPlayerServer;

/**
 * Evidence scenario "m1-two-pods": the real client and a mock player each drill a pod down through layer 1 and cross the
 * breach on a dedicated server. The flow, and its assertions, are {@link TwoPlayerClientTest#crossTogether}; this records it.
 */
public class TwoPodsScenario extends EvidenceScenario {
	@Override
	protected String name() {
		return "m1-two-pods";
	}

	@Override
	protected void run(ClientGameTestContext context) {
		try (TwoPlayerServer two = TwoPlayerServer.start(context)) {
			TwoPlayerClientTest.crossTogether(context, two, () -> frame(context));
			screenshot(context, "m1-two-pods-layer-2");
		}
	}
}
