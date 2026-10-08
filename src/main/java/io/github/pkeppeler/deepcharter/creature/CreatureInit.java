package io.github.pkeppeler.deepcharter.creature;

/**
 * Server entry point for the creature feature, called from DeepCharter. Issues: #83.
 * Each part of the feature registers itself from here, one line per part.
 */
public final class CreatureInit {
	private CreatureInit() {
	}

	public static void init() {
		CreatureRegistry.register();
		LamplessFigures.init();
	}
}
