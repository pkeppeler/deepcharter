package io.github.pkeppeler.deepcharter.transmission;

/**
 * Server entry point for the transmission feature, called from DeepCharter. Issues: #62.
 * Each part of the feature registers itself from here, one line per part.
 */
public final class TransmissionInit {
	private TransmissionInit() {
	}

	public static void init() {
		TransmissionRegistry.register();
	}
}
