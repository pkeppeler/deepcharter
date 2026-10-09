package io.github.pkeppeler.deepcharter.surface;

public final class SurfaceRegistry {
	// Registers the surface blocks. The surface rules and structure removal are data.

	private SurfaceRegistry() {
	}

	public static void register() {
		SurfaceBlocks.register();
	}
}
