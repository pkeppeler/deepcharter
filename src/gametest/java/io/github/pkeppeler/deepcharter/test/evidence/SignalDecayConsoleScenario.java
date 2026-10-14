package io.github.pkeppeler.deepcharter.test.evidence;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import io.github.pkeppeler.deepcharter.pod.Chassis;
import io.github.pkeppeler.deepcharter.upgrade.ComponentTrack;

/**
 * Evidence scenario "signal-decay-console" (#502): the same descent as "signal-decay" in a Prospector with the top console, tier 3, which a Mole cannot fit. The decay never
 * starts: every gauge, the scanner and the plate read true at the floor.
 */
public class SignalDecayConsoleScenario extends EvidenceScenario {
	@Override
	protected String name() {
		return "signal-decay-console";
	}

	@Override
	protected void run(ClientGameTestContext context) {
		SignalDecayDescent.run(this, context, Chassis.PROSPECTOR, ComponentTrack.CONSOLE.maxTier(), "signal-decay-console");
	}
}
