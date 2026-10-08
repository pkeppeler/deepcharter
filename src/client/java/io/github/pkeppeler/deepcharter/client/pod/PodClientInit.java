package io.github.pkeppeler.deepcharter.client.pod;

/**
 * Client entry point for pods, called from DeepCharterClient.
 * Each part of the pod feature registers itself from here, one line per part.
 */
public final class PodClientInit {
	private PodClientInit() {
	}

	public static void init() {
		PodClientRegistry.register();
		PodStatusHud.init();
		LowFuelBeep.init();
		PilotFire.init();
	}
}
