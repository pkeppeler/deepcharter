package io.github.pkeppeler.deepcharter.client.surface;

/**
 * Client entry point for the surface feature, called from DeepCharterClient.
 * Each part of the feature registers itself from here, one line per part.
 */
public final class SurfaceClientInit {
	private SurfaceClientInit() {
	}

	public static void init() {
		SurfaceClientRegistry.register();
	}
}
