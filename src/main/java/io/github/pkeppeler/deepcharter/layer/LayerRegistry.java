package io.github.pkeppeler.deepcharter.layer;

/** Registrations of the layer chain. The dimensions are data, so only the blocks register in code. */
public final class LayerRegistry {
	private LayerRegistry() {
	}

	public static void register() {
		LayerBlocks.register();
	}
}
