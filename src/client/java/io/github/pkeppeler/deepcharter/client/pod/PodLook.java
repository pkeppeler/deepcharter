package io.github.pkeppeler.deepcharter.client.pod;

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.pod.Chassis;

/**
 * The look of one chassis, read from the resource file {@code assets/deepcharter/pod/<chassis>.json} (ADR 0040). A resource pack
 * replaces the whole file, with its own model, textures and tier map, and F3+T applies it. The code names bones and nothing else.
 *
 * <pre>{@code
 * {
 *   "model": "deepcharter:pod/mole",                       the GeckoLib model, geckolib/models/pod/mole.geo.json
 *   "texture": "deepcharter:textures/entity/pod/mole.png", its glowmask is the same name with _glowmask: mole_glowmask.png
 *   "glow": "lit",                                         "lit" while the pod has power, "always" or "never"
 *   "hide": [],                                            bones not drawn (optional)
 *   "cutters": {"0": "tricone", "1": "stacked"},           the drill tier from which each cutter shows (the tier map)
 *   "wreck": {"texture": "...", "glow": "never", "hide": ["rotor"]}
 * }
 * }</pre>
 *
 * <p>{@code source} names the file and the pack it came from, for errors. A pack's look that does not load, or does not fit its model and
 * textures, never stops the game: the renderer logs it and draws the mod's own look for that chassis ({@link #readBuiltIn}).
 *
 * <p>The tier map has an entry for tier 0, the stock drill, so every tier has a cutter: a tier shows the cutter of the highest
 * entry at or below it. A model with several cutters (bones {@code drill_head_<cutter>}) needs the map and names only cutters it
 * holds; a model with one plain cutter has no map.
 */
public record PodLook(Identifier model, Variant intact, Variant wreck, NavigableMap<Integer, String> cutters, String source) {
	/** When the glowmask is drawn over the texture. */
	public enum Glow {
		/** While the pod has a pilot and power, as its lamps are. */
		LIT,
		/** Always: a wreck with a lamp still burning. */
		ALWAYS,
		/** Never: the pod has no glowmask in this variant, and the file need not exist. */
		NEVER
	}

	/** One way the pod is drawn, for an intact pod or a wreck: its texture, when it glows, and bones it does not draw. */
	public record Variant(Identifier texture, Glow glow, List<String> hide) {
		public Variant {
			hide = List.copyOf(hide);
		}

		/** The glowmask GeckoLib's glow layer reads: the texture's name and {@code _glowmask}. */
		public Identifier glowmask() {
			String path = texture.getPath();
			return texture.withPath(path.substring(0, path.length() - ".png".length()) + "_glowmask.png");
		}
	}

	private static final Set<String> TOP_KEYS = Set.of("model", "texture", "glow", "hide", "cutters", "wreck");
	private static final Set<String> WRECK_KEYS = Set.of("texture", "glow", "hide");

	public PodLook {
		cutters = Collections.unmodifiableNavigableMap(new TreeMap<>(cutters));
	}

	/** The file that holds the look of {@code chassis}. */
	public static Identifier file(Chassis chassis) {
		return Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "pod/" + chassis.id() + ".json");
	}

	/** Reads the look of {@code chassis} that wins: a pack's if a pack has one, else the mod's. A missing file, or one that does not parse, throws. */
	public static PodLook read(ResourceManager resources, Chassis chassis) {
		Identifier file = file(chassis);
		Resource resource = resources.getResource(file).orElseThrow(() -> new IllegalStateException("No pod look at " + file + " for the chassis " + chassis.id()));
		return read(file, resource);
	}

	/** The mod's own look of {@code chassis}, below every pack's: the one the game falls back to when a pack's look is broken. */
	public static PodLook readBuiltIn(ResourceManager resources, Chassis chassis) {
		Identifier file = file(chassis);
		List<Resource> stack = resources.getResourceStack(file);
		if (stack.isEmpty()) {
			throw new IllegalStateException("No pod look at " + file + " for the chassis " + chassis.id());
		}
		return read(file, stack.getFirst());
	}

	private static PodLook read(Identifier file, Resource resource) {
		try (Reader reader = resource.openAsReader()) {
			return parse(file + " (from " + resource.sourcePackId() + ")", reader);
		} catch (IOException e) {
			throw new UncheckedIOException("Could not read the pod look " + file, e);
		}
	}

	/** Reads a look from {@code json}; {@code source} names the file in every error. */
	public static PodLook parse(String source, Reader json) {
		JsonElement root;
		try {
			root = JsonParser.parseReader(json);
		} catch (JsonParseException e) {
			throw new IllegalArgumentException(source + ": not valid JSON: " + e.getMessage(), e);
		}
		JsonObject top = object(source, "the file", root);
		keys(source, "the file", top, TOP_KEYS);
		Identifier model = identifier(source, "model", top);
		Variant intact = new Variant(texture(source, "texture", top), glow(source, top, Glow.LIT), hide(source, top));
		JsonObject wreckJson = object(source, "wreck", top.get("wreck"));
		keys(source, "wreck", wreckJson, WRECK_KEYS);
		Variant wreck = new Variant(texture(source, "wreck texture", wreckJson), glow(source, wreckJson, Glow.NEVER), hide(source, wreckJson));
		NavigableMap<Integer, String> cutters = new TreeMap<>();
		if (top.has("cutters")) {
			for (Map.Entry<String, JsonElement> entry : object(source, "cutters", top.get("cutters")).entrySet()) {
				int tier;
				try {
					tier = Integer.parseInt(entry.getKey());
				} catch (NumberFormatException e) {
					throw new IllegalArgumentException(source + ": the cutters key '" + entry.getKey() + "' is not a drill tier, a whole number", e);
				}
				if (tier < 0) {
					throw new IllegalArgumentException(source + ": the cutters key " + tier + " is below tier 0");
				}
				JsonElement value = entry.getValue();
				if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString() || value.getAsString().isBlank()) {
					throw new IllegalArgumentException(source + ": the cutter of tier " + tier + " is not a name");
				}
				cutters.put(tier, value.getAsString());
			}
			if (cutters.isEmpty() || cutters.firstKey() != 0) {
				throw new IllegalArgumentException(source + ": the cutters need an entry for tier 0, the stock drill, so that every tier has a cutter; has " + cutters.keySet());
			}
		}
		return new PodLook(model, intact, wreck, cutters, source);
	}

	/** The cutter that a drill of {@code tier} shows: that of the highest entry at or below it. A pod without cutters has none to ask. */
	public String cutterFor(int tier) {
		if (cutters.isEmpty()) {
			throw new IllegalStateException("the look of " + model + " has no cutters to choose from");
		}
		return cutters.floorEntry(Math.max(0, tier)).getValue();
	}

	/** The geometry file GeckoLib reads for {@link #model}, which the game reads as well, to measure it. */
	public Identifier modelFile() {
		return model.withPath("geckolib/models/" + model.getPath() + ".geo.json");
	}

	/**
	 * Throws unless this look fits {@code geo}, the model it names: every cutter the map names is a cutter the model holds, a model
	 * with cutters has the map, and every hidden bone is a bone of the model.
	 */
	public void check(GeoModel geo) {
		List<String> held = geo.cutters();
		if (!held.isEmpty() && cutters.isEmpty()) {
			throw new IllegalArgumentException(source + ": the look of " + model + " has no cutters map, but " + geo.source() + " holds the cutters " + held);
		}
		if (held.isEmpty() && !cutters.isEmpty()) {
			throw new IllegalArgumentException(source + ": the look of " + model + " maps cutters " + cutters.values() + ", but " + geo.source() + " holds one plain cutter");
		}
		for (Map.Entry<Integer, String> entry : cutters.entrySet()) {
			if (!held.contains(entry.getValue())) {
				throw new IllegalArgumentException(source + ": the look of " + model + " maps drill tier " + entry.getKey() + " to the cutter '" + entry.getValue()
						+ "', which " + geo.source() + " does not hold; it holds " + held);
			}
		}
		List<String> names = new ArrayList<>();
		geo.bones().forEach(bone -> names.add(bone.name()));
		for (Variant variant : List.of(intact, wreck)) {
			for (String hidden : variant.hide()) {
				if (!names.contains(hidden)) {
					throw new IllegalArgumentException(source + ": the look of " + model + " hides '" + hidden + "', which is no bone of " + geo.source());
				}
			}
		}
	}

	private static Identifier identifier(String source, String key, JsonObject json) {
		String text = string(source, key, json.get(key));
		Identifier id = Identifier.tryParse(text);
		if (id == null) {
			throw new IllegalArgumentException(source + ": " + key + " '" + text + "' is not a resource id");
		}
		return id;
	}

	private static Identifier texture(String source, String where, JsonObject json) {
		Identifier id = identifier(source, "texture", json);
		if (!id.getPath().startsWith("textures/") || !id.getPath().endsWith(".png")) {
			throw new IllegalArgumentException(source + ": the " + where + " '" + id + "' is not a texture path, textures/....png");
		}
		return id;
	}

	private static Glow glow(String source, JsonObject json, Glow fallback) {
		if (!json.has("glow")) {
			return fallback;
		}
		String text = string(source, "glow", json.get("glow"));
		for (Glow glow : Glow.values()) {
			if (glow.name().equalsIgnoreCase(text)) {
				return glow;
			}
		}
		throw new IllegalArgumentException(source + ": glow '" + text + "' is none of lit, always, never");
	}

	private static List<String> hide(String source, JsonObject json) {
		if (!json.has("hide")) {
			return List.of();
		}
		JsonElement element = json.get("hide");
		if (!element.isJsonArray()) {
			throw new IllegalArgumentException(source + ": hide is not a list of bone names");
		}
		JsonArray array = element.getAsJsonArray();
		List<String> names = new ArrayList<>();
		for (JsonElement name : array) {
			names.add(string(source, "a hidden bone", name));
		}
		return names;
	}

	private static void keys(String source, String where, JsonObject json, Set<String> allowed) {
		for (String key : json.keySet()) {
			if (!allowed.contains(key)) {
				throw new IllegalArgumentException(source + ": " + where + " has the key '" + key + "', which a pod look does not use; known: " + allowed);
			}
		}
	}

	private static JsonObject object(String source, String where, JsonElement element) {
		if (element == null || !element.isJsonObject()) {
			throw new IllegalArgumentException(source + ": " + where + " is missing or not an object");
		}
		return element.getAsJsonObject();
	}

	private static String string(String source, String where, JsonElement element) {
		if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
			throw new IllegalArgumentException(source + ": " + where + " is missing or not a string");
		}
		return element.getAsString();
	}
}
