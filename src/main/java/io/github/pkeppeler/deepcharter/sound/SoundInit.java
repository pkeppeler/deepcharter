package io.github.pkeppeler.deepcharter.sound;

/**
 * Server entry point for the sound feature, called from DeepCharter. Issues: #57, #71.
 * Each part of the feature registers itself from here, one line per part.
 */
public final class SoundInit {
	private SoundInit() {
	}

	public static void init() {
		SoundRegistry.register();
	}
}
