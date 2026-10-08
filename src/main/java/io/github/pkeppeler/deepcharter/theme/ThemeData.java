package io.github.pkeppeler.deepcharter.theme;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

/**
 * One area of the UI theme (ADR 0032): a flat map from key to a colour or a number, merged from every pack's copy of the area's file.
 *
 * <p>A value is a JSON string for a colour, {@code "#RRGGBB"} (opaque) or {@code "#AARRGGBB"}, or a JSON number. The layers go lowest
 * priority first, the mod's own file at the bottom, and a later layer replaces only the keys it names, so a pack restyles one colour
 * by naming one key. Every value is checked when it is parsed, so a bad file fails the reload with the pack and key named instead of
 * drawing a wrong colour later. A read of a key the area lacks, or of a value as the wrong kind, throws and names the area and key.
 *
 * <p>Pure Java, no Minecraft classes: the loader that reads packs is in {@code client/theme}.
 */
public final class ThemeData {
	private static final Pattern COLOR = Pattern.compile("#(?:[0-9A-Fa-f]{6}|[0-9A-Fa-f]{8})");

	/** One file of an area, with the name of the pack it came from for error messages. */
	public record Layer(String source, String json) {
	}

	private final String area;
	private final Map<String, JsonPrimitive> values;
	private final Set<String> read = new HashSet<>();

	private ThemeData(String area, Map<String, JsonPrimitive> values) {
		this.area = area;
		this.values = values;
	}

	/** Merges {@code layers}, lowest priority first. Throws {@link IllegalArgumentException} naming the pack and key of a bad value. */
	public static ThemeData parse(String area, List<Layer> layers) {
		Map<String, JsonPrimitive> merged = new LinkedHashMap<>();
		for (Layer layer : layers) {
			JsonObject object;
			try {
				JsonElement root = JsonParser.parseString(layer.json());
				if (!root.isJsonObject()) {
					throw new IllegalArgumentException("theme area '%s' in %s must be a JSON object".formatted(area, layer.source()));
				}
				object = root.getAsJsonObject();
			} catch (JsonParseException e) {
				throw new IllegalArgumentException("theme area '%s' in %s is not valid JSON: %s".formatted(area, layer.source(), e.getMessage()), e);
			}
			for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
				merged.put(entry.getKey(), checked(area, layer.source(), entry.getKey(), entry.getValue()));
			}
		}
		return new ThemeData(area, merged);
	}

	private static JsonPrimitive checked(String area, String source, String key, JsonElement value) {
		if (value.isJsonPrimitive()) {
			JsonPrimitive primitive = value.getAsJsonPrimitive();
			if (primitive.isNumber()) {
				return primitive;
			}
			if (primitive.isString()) {
				if (!COLOR.matcher(primitive.getAsString()).matches()) {
					throw new IllegalArgumentException("theme area '%s', key '%s' in %s: '%s' is not a colour, write #RRGGBB or #AARRGGBB"
							.formatted(area, key, source, primitive.getAsString()));
				}
				return primitive;
			}
		}
		throw new IllegalArgumentException("theme area '%s', key '%s' in %s: a value is a colour string or a number, got %s"
				.formatted(area, key, source, value));
	}

	/** The colour as ARGB. {@code #RRGGBB} is opaque. */
	public int color(String key) {
		JsonPrimitive value = value(key);
		if (!value.isString()) {
			throw wrongKind(key, "a colour string");
		}
		String hex = value.getAsString().substring(1);
		long argb = Long.parseLong(hex, 16);
		return (int) (hex.length() == 6 ? 0xFF000000L | argb : argb);
	}

	public int integer(String key) {
		JsonPrimitive value = value(key);
		if (!value.isNumber()) {
			throw wrongKind(key, "a number");
		}
		double number = value.getAsDouble();
		if (number != Math.rint(number) || Math.abs(number) > Integer.MAX_VALUE) {
			throw wrongKind(key, "a whole number, got " + value.getAsString());
		}
		return (int) number;
	}

	/** {@link #integer(String)}, and at least {@code min}. */
	public int integer(String key, int min) {
		int number = integer(key);
		if (number < min) {
			throw new IllegalArgumentException("theme area '%s', key '%s' must be at least %d, got %d".formatted(area, key, min, number));
		}
		return number;
	}

	public double decimal(String key) {
		JsonPrimitive value = value(key);
		if (!value.isNumber()) {
			throw wrongKind(key, "a number");
		}
		return value.getAsDouble();
	}

	/** The keys no read has asked for yet, sorted: after a loader has built everything, what is left is a typo or a stale key. */
	public Set<String> unread() {
		Set<String> unread = new TreeSet<>(values.keySet());
		unread.removeAll(read);
		return unread;
	}

	private JsonPrimitive value(String key) {
		JsonPrimitive value = values.get(key);
		if (value == null) {
			throw new IllegalStateException("theme area '%s' has no key '%s'".formatted(area, key));
		}
		read.add(key);
		return value;
	}

	private IllegalStateException wrongKind(String key, String wanted) {
		return new IllegalStateException("theme area '%s', key '%s' must be %s".formatted(area, key, wanted));
	}
}
