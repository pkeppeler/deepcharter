package io.github.pkeppeler.deepcharter.test.evidence;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import io.github.pkeppeler.deepcharter.pod.Chassis;

/**
 * Evidence scenario "signal-decay" (#502): a Mole with the stock console is lowered down a shaft in layer 1 to the floor and raised again. The scanner fills with noise,
 * the gauges flicker, jump and go dark, the plate becomes a line that says something is on the line, and at the floor only the hull lamp (the pod is at a quarter hull) is lit.
 */
public class SignalDecayScenario extends EvidenceScenario {
	@Override
	protected String name() {
		return "signal-decay";
	}

	@Override
	protected void run(ClientGameTestContext context) {
		SignalDecayDescent.run(this, context, Chassis.MOLE, 0, "signal-decay");
	}
}
