package io.github.pkeppeler.deepcharter.test.evidence;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

/** Evidence scenario "pod-shell": a screenshot of the pod and its rider. */
public class PodShellScenario extends EvidenceScenario {
	// Filled by #27.

	@Override
	protected String name() {
		return "pod-shell";
	}

	@Override
	protected void run(ClientGameTestContext context) {
		throw new UnsupportedOperationException("Scenario pod-shell is a stub: #27 fills it");
	}
}
