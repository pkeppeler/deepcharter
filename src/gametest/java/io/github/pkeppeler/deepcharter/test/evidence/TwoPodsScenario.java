package io.github.pkeppeler.deepcharter.test.evidence;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

/** Evidence scenario "m1-two-pods": two pods drilling through layer 1 and crossing the breach. */
public class TwoPodsScenario extends EvidenceScenario {
	// Filled by #33.

	@Override
	protected String name() {
		return "m1-two-pods";
	}

	@Override
	protected void run(ClientGameTestContext context) {
		throw new UnsupportedOperationException("Scenario m1-two-pods is a stub: #33 fills it");
	}
}
