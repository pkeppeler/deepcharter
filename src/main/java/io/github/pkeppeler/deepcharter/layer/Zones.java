package io.github.pkeppeler.deepcharter.layer;

import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;

import io.github.pkeppeler.deepcharter.DeepCharter;

/**
 * The zones of a layer: three equal thirds by height, top to bottom, named in the lore canon (section 11). Each zone
 * is also a biome, {@code deepcharter:<name>}, which the layer's biome source places by the same rule.
 */
public final class Zones {
	public static final int COUNT = 3;

	/** Zone names per layer (index 0 is layer 1), top to bottom. A layer with no entry has no zones yet. */
	private static final List<List<String>> NAMES = List.of(
			List.of("topsoil_claims", "stone_benches", "deep_claim"),
			List.of("upper_levels", "shift_change", "prospectors_run"));

	private Zones() {
	}

	/**
	 * One zone of one layer.
	 *
	 * @param layer the layer, counted from 1
	 * @param index 0 for the top third, 2 for the bottom
	 * @param id    the biome of the zone, {@code deepcharter:<name>}
	 */
	public record Zone(int layer, int index, Identifier id) {
		/** The lang key of the zone's name, which is its biome's. */
		public String translationKey() {
			return "biome." + id.getNamespace() + "." + id.getPath();
		}
	}

	/**
	 * The zone at {@code y} in a layer; empty on the surface. A layer with no zones defined is a bug, and so is a
	 * {@code y} outside the layer.
	 */
	public static Optional<Zone> of(Level level, int y) {
		OptionalInt found = LayerChain.layerOf(level.dimensionTypeRegistration().unwrapKey().orElseThrow().identifier());
		if (found.isEmpty()) {
			return Optional.empty();
		}
		int layer = found.getAsInt();
		int index = index(level.getMinY(), level.getHeight(), y);
		return Optional.of(new Zone(layer, index, biome(layer, index)));
	}

	/** Which third of the {@code height} blocks from {@code minY} holds {@code y}: 0 for the top third, 2 for the bottom. */
	public static int index(int minY, int height, int y) {
		if (y < minY || y >= minY + height) {
			throw new IllegalArgumentException("y=" + y + " is outside the layer's " + minY + ".." + (minY + height - 1));
		}
		return COUNT - 1 - (int) ((long) (y - minY) * COUNT / height);
	}

	/** The biome of a zone; the layer must have zones defined. */
	public static Identifier biome(int layer, int index) {
		if (layer < 1 || layer > NAMES.size()) {
			throw new IllegalStateException("Layer " + layer + " has no zones defined");
		}
		return Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, NAMES.get(layer - 1).get(index));
	}
}
