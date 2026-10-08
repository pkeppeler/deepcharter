package io.github.pkeppeler.deepcharter.layer;

import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;

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
	}

	/** Checks at server start that the names cover every layer of the chain. */
	public static void init() {
		ServerLifecycleEvents.SERVER_STARTED.register(server -> requireNamesFor(LayerChain.count(server.registryAccess())));
	}

	/** A chain with a layer that has no zone names is a bug, found at start-up and not on a tick. */
	public static void requireNamesFor(int layers) {
		if (layers > NAMES.size()) {
			throw new IllegalStateException("The chain has " + layers + " layers but only " + NAMES.size() + " have zone names");
		}
	}

	/** The zone at {@code y}; empty on the surface and outside the layer's height, so any player Y is safe to ask. */
	public static Optional<Zone> of(Level level, int y) {
		OptionalInt found = LayerChain.layerOf(level.dimensionTypeRegistration().unwrapKey().orElseThrow().identifier());
		if (found.isEmpty()) {
			return Optional.empty();
		}
		return of(found.getAsInt(), level.getMinY(), level.getHeight(), y);
	}

	/** The zone at {@code y} of a layer of {@code height} blocks from {@code minY}; empty for an unnamed layer or an outside y. */
	public static Optional<Zone> of(int layer, int minY, int height, int y) {
		if (layer < 1 || layer > NAMES.size() || y < minY || y >= minY + height) {
			return Optional.empty();
		}
		int index = index(minY, height, y);
		return Optional.of(new Zone(layer, index, biome(layer, index)));
	}

	/** Which third of the {@code height} blocks from {@code minY} holds {@code y}: 0 for the top third, 2 for the bottom. */
	public static int index(int minY, int height, int y) {
		if (y < minY || y >= minY + height) {
			throw new IllegalArgumentException("y=" + y + " is outside the layer's " + minY + ".." + (minY + height - 1));
		}
		return COUNT - 1 - (int) ((long) (y - minY) * COUNT / height);
	}

	/**
	 * The heights of one zone, both ends included.
	 *
	 * @param low  the lowest Y of the zone
	 * @param high the highest Y of the zone
	 */
	public record Span(int low, int high) {
		public int size() {
			return high - low + 1;
		}
	}

	/** The Y range of zone {@code index} (0 for the top third) of {@code height} blocks from {@code minY}: the Ys for which {@link #index} answers it. */
	public static Span span(int minY, int height, int index) {
		if (index < 0 || index >= COUNT) {
			throw new IllegalArgumentException("A layer has zones 0 to " + (COUNT - 1) + ", not " + index);
		}
		int low = minY + Math.ceilDiv((COUNT - 1 - index) * height, COUNT);
		int high = index == 0 ? minY + height - 1 : minY + Math.ceilDiv((COUNT - index) * height, COUNT) - 1;
		return new Span(low, high);
	}

	/** The biome of a zone; the layer must have zones defined. */
	private static Identifier biome(int layer, int index) {
		if (layer < 1 || layer > NAMES.size()) {
			throw new IllegalStateException("Layer " + layer + " has no zones defined");
		}
		return Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, NAMES.get(layer - 1).get(index));
	}
}
