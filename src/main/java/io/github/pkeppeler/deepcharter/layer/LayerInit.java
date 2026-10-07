package io.github.pkeppeler.deepcharter.layer;

/**
 * Server entry point for layers and breaches, called from DeepCharter.
 * Each part of the layer feature registers itself from here, one line per part.
 */
public final class LayerInit {
	private LayerInit() {
	}

	public static void init() {
		LayerRegistry.register();
		LayerCommands.init();
		BreachService.init();
		BreachPayload.init();
		LayerRock.init();
		Zones.init();
		LayerStructures.init();
	}
}
