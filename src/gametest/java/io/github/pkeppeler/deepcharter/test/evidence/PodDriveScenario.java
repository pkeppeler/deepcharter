package io.github.pkeppeler.deepcharter.test.evidence;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

/** Evidence scenario "pod-drive": a bot-driven recording of a pod driving and flying. */
public class PodDriveScenario extends EvidenceScenario {
	// Filled by #29.

	@Override
	protected String name() {
		return "pod-drive";
	}

	@Override
	protected void run(ClientGameTestContext context) {
		throw new UnsupportedOperationException("Scenario pod-drive is a stub: #29 fills it");
	}
}
