package io.github.pkeppeler.deepcharter.client.creature;

/**
 * Client entry point for the creature feature, called from DeepCharterClient. Issues: #83.
 * Each part of the feature registers itself from here, one line per part.
 */
public final class CreatureClientInit {
	private CreatureClientInit() {
	}

	public static void init() {
		CreatureClientRegistry.register();
	}
}
