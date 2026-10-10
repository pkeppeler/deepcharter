package io.github.pkeppeler.deepcharter.pod;

/**
 * Server entry point for pods, called from DeepCharter.
 * Each part of the pod feature registers itself from here, one line per part.
 */
public final class PodInit {
	private PodInit() {
	}

	public static void init() {
		PodRegistry.register();
		PodCommands.init();
		PodMovement.init();
		HardLanding.init();
		PodDrill.init();
		PodCargo.init();
		PodFuel.init();
		PodComponents.init();
		PodLights.init();
		PodLining.init();
		PodLiner.init();
		PodSounder.init();
		PodBrace.init();
		PodTowing.init();
	}
}
