package io.github.pkeppeler.deepcharter.surface;

/**
 * Server entry point for the surface feature, called from DeepCharter. Issues: #55.
 * Each part of the feature registers itself from here, one line per part.
 */
public final class SurfaceInit {
	private SurfaceInit() {
	}

	public static void init() {
		SurfaceRegistry.register();
	}
}
