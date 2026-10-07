package io.github.pkeppeler.deepcharter.layer;

/**
 * Registrations of the layer chain. The dimensions and their dimension types are data
 * ({@code data/deepcharter/dimension*}), so only the blocks register in code.
 */
public final class LayerRegistry {
	private LayerRegistry() {
	}

	public static void register() {
		LayerBlocks.register();
	}
}
