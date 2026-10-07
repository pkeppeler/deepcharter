package io.github.pkeppeler.deepcharter.client.transmission;

/**
 * Client entry point for the transmission feature, called from DeepCharterClient. Issues: #62.
 * Each part of the feature registers itself from here, one line per part.
 */
public final class TransmissionClientInit {
	private TransmissionClientInit() {
	}

	public static void init() {
		TransmissionClientRegistry.register();
	}
}
