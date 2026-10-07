package io.github.pkeppeler.deepcharter.client.layer;

/**
 * Client entry point for layers and breaches, called from DeepCharterClient.
 * Each part of the layer feature registers itself from here, one line per part.
 */
public final class LayerClientInit {
	private LayerClientInit() {
	}

	public static void init() {
		LayerClientRegistry.register();
	}
}
