package io.github.pkeppeler.deepcharter.layer;

import io.github.pkeppeler.deepcharter.layer.gen.ZoneBiomeSource;

/** Registrations of the layer chain. The dimensions are data, so only the blocks and the zone biome source register in code. */
public final class LayerRegistry {
	private LayerRegistry() {
	}

	public static void register() {
		LayerBlocks.register();
		ZoneBiomeSource.register();
	}
}
