package io.github.pkeppeler.deepcharter.transmission;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;

import net.minecraft.resources.Identifier;

import io.github.pkeppeler.deepcharter.DeepCharter;

/**
 * Every transmission of the game, in the order the data file lists them. Read once from
 * {@code data/deepcharter/transmissions.json} on first use, by the server and the client alike (both read the same jar). A file
 * that does not parse, or that lists an id twice, throws: a broken story is found on the first use and not when a player reaches it.
 */
public final class TransmissionCatalog {
	private static final String RESOURCE = "/data/" + DeepCharter.MOD_ID + "/transmissions.json";
	private static final Codec<List<Transmission>> FILE_CODEC = Transmission.CODEC.listOf().fieldOf("transmissions").codec();

	private static final class Holder {
		private static final Map<Identifier, Transmission> ALL = load();
	}

	private TransmissionCatalog() {
	}

	/** All transmissions, in file order. */
	public static List<Transmission> all() {
		return List.copyOf(Holder.ALL.values());
	}

	public static Optional<Transmission> find(Identifier id) {
		return Optional.ofNullable(Holder.ALL.get(id));
	}

	/** The transmission, or an exception naming the id: a saved or fired id that the data does not know is a bug. */
	public static Transmission require(Identifier id) {
		return find(id).orElseThrow(() -> new IllegalArgumentException("No transmission " + id + " in " + RESOURCE));
	}

	/** The transmissions that fire when a charter member descends into {@code toLayer}. */
	public static List<Transmission> forBreach(int toLayer) {
		return all().stream().filter(transmission -> transmission.trigger().equals(new Transmission.Trigger.Breach(toLayer))).toList();
	}

	/** The transmissions that fire when a charter member stands in zone {@code zone} of {@code layer}. */
	public static List<Transmission> forZone(int layer, int zone) {
		return all().stream().filter(transmission -> transmission.trigger().equals(new Transmission.Trigger.Zone(layer, zone))).toList();
	}

	private static Map<Identifier, Transmission> load() {
		try (InputStream stream = TransmissionCatalog.class.getResourceAsStream(RESOURCE)) {
			if (stream == null) {
				throw new IllegalStateException("Missing resource " + RESOURCE);
			}
			try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
				JsonElement json = JsonParser.parseReader(reader);
				List<Transmission> parsed = FILE_CODEC.parse(JsonOps.INSTANCE, json)
						.getOrThrow(message -> new IllegalStateException(RESOURCE + ": " + message));
				Map<Identifier, Transmission> byId = new LinkedHashMap<>();
				for (Transmission transmission : parsed) {
					if (byId.put(transmission.id(), transmission) != null) {
						throw new IllegalStateException(RESOURCE + " lists " + transmission.id() + " twice");
					}
				}
				return Collections.unmodifiableMap(byId);
			}
		} catch (IOException e) {
			throw new UncheckedIOException("Cannot read " + RESOURCE, e);
		}
	}
}
