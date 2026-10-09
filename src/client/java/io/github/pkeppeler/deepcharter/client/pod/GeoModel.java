package io.github.pkeppeler.deepcharter.client.pod;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import net.minecraft.world.phys.Vec3;

/**
 * A pod model read from a Bedrock entity geometry, the {@code .geo.json} that Blockbench exports (ADR 0030's ModelPart path,
 * #334). Box UV only. Every bone name is a {@link BoneRole} word, and there is one {@code drill_mount} with a
 * {@code drill_head} under it; a {@code drill_ring} is under the mount and not under the head. Anything else, or anything
 * malformed, throws with the file and the place named.
 *
 * <p>Coordinates are the file's: pixels, y up, the floor at 0, the front toward -z.
 */
public record GeoModel(String source, int textureWidth, int textureHeight, List<Bone> bones) {
	private static final Set<String> TOP_KEYS = Set.of("format_version", "minecraft:geometry");
	private static final Set<String> DESCRIPTION_KEYS = Set.of("identifier", "texture_width", "texture_height", "visible_bounds_width",
			"visible_bounds_height", "visible_bounds_offset");
	private static final Set<String> GEOMETRY_KEYS = Set.of("description", "bones");
	private static final Set<String> BONE_KEYS = Set.of("name", "parent", "pivot", "rotation", "cubes", "mirror", "inflate", "locators");
	private static final Set<String> CUBE_KEYS = Set.of("origin", "size", "uv", "inflate", "mirror", "pivot", "rotation");
	/** A PNG starts with an 8-byte signature, then the IHDR chunk: length, type, width, height. */
	private static final byte[] PNG_SIGNATURE = {(byte) 137, 'P', 'N', 'G', '\r', '\n', 26, '\n'};
	private static final byte[] PNG_IHDR = {'I', 'H', 'D', 'R'};
	private static final int PNG_IHDR_TYPE = 12;
	private static final int PNG_HEADER_BYTES = 24;

	/** A bone: its pivot and rest rotation (degrees, applied z, then y, then x) are in model space, as in the file. */
	public record Bone(String name, BoneRole role, Optional<String> parent, Vec3 pivot, Vec3 rotation, List<Cube> cubes) {
	}

	/** A cube with box UV at (u, v). A turned cube turns about its own pivot, after its bone. */
	public record Cube(Vec3 origin, Vec3 size, int u, int v, float inflate, boolean mirror, Optional<Turn> turn) {
	}

	/** A cube's own rotation (degrees) about its own pivot. */
	public record Turn(Vec3 pivot, Vec3 rotation) {
	}

	public GeoModel {
		bones = List.copyOf(bones);
	}

	/** Reads one geometry from {@code json}; {@code source} names the file in every error. */
	public static GeoModel parse(String source, Reader json) {
		JsonElement root;
		try {
			root = JsonParser.parseReader(json);
		} catch (JsonParseException e) {
			throw new IllegalArgumentException(source + ": not valid JSON: " + e.getMessage(), e);
		}
		JsonObject top = object(source, "the file", root);
		keys(source, "the file", top, TOP_KEYS);
		string(source, "format_version", top, "format_version");
		JsonArray geometries = array(source, "minecraft:geometry", top, "minecraft:geometry");
		if (geometries.size() != 1) {
			throw new IllegalArgumentException(source + ": holds " + geometries.size() + " geometries; a pod model holds exactly one");
		}
		JsonObject geometry = object(source, "minecraft:geometry[0]", geometries.get(0));
		keys(source, "the geometry", geometry, GEOMETRY_KEYS);
		JsonObject description = object(source, "description", geometry.get("description"));
		keys(source, "description", description, DESCRIPTION_KEYS);
		int width = positiveInt(source, "texture_width", description);
		int height = positiveInt(source, "texture_height", description);
		List<Bone> bones = new ArrayList<>();
		for (JsonElement element : array(source, "bones", geometry, "bones")) {
			bones.add(bone(source, element, width, height));
		}
		GeoModel model = new GeoModel(source, width, height, bones);
		model.checkRig();
		return model;
	}

	/**
	 * Throws unless {@code png} (named {@code texture} in the error) is a PNG of exactly this model's texture size. A missing texture,
	 * or one of another size, would draw the model in vanilla's magenta or in the wrong places, with no error.
	 */
	public void checkTexture(String texture, InputStream png) throws IOException {
		byte[] head = png.readNBytes(PNG_HEADER_BYTES);
		if (head.length < PNG_HEADER_BYTES || !Arrays.equals(head, 0, PNG_SIGNATURE.length, PNG_SIGNATURE, 0, PNG_SIGNATURE.length)
				|| !Arrays.equals(head, PNG_IHDR_TYPE, PNG_IHDR_TYPE + PNG_IHDR.length, PNG_IHDR, 0, PNG_IHDR.length)) {
			throw new IllegalArgumentException(texture + " is not a PNG; the pod model " + source + " needs it");
		}
		ByteBuffer header = ByteBuffer.wrap(head);
		int width = header.getInt(PNG_IHDR_TYPE + 4);
		int height = header.getInt(PNG_IHDR_TYPE + 8);
		if (width != textureWidth || height != textureHeight) {
			throw new IllegalArgumentException(texture + " is " + width + " x " + height + ", but the pod model " + source + " lays its UV out for "
					+ textureWidth + " x " + textureHeight);
		}
	}

	/**
	 * How far the cubes of the spinning drill bones ({@code drill_head} and {@code drill_ring}, not their children) reach from the
	 * bone's axis across x or y, in pixels: half the width of the cutter.
	 */
	public double drillReach() {
		double reach = 0;
		for (Bone spinning : bones.stream().filter(bone -> bone.role() == BoneRole.DRILL_HEAD || bone.role() == BoneRole.DRILL_RING).toList()) {
			for (Cube cube : spinning.cubes()) {
				Vec3 low = cube.origin().subtract(spinning.pivot());
				Vec3 high = low.add(cube.size());
				reach = Math.max(reach, Math.max(Math.max(Math.abs(low.x), Math.abs(high.x)), Math.max(Math.abs(low.y), Math.abs(high.y))));
			}
		}
		return reach;
	}

	/** The bones that name {@code parent} as their parent, in file order. */
	public List<Bone> children(String parent) {
		return bones.stream().filter(bone -> bone.parent().map(parent::equals).orElse(false)).toList();
	}

	/** Unique names, parents that exist, no loop, one drill mount with a drill head under it, and drill rings under the mount but not the head. */
	private void checkRig() {
		if (bones.isEmpty()) {
			throw new IllegalArgumentException(source + ": has no bones");
		}
		Map<String, Bone> byName = new HashMap<>();
		for (Bone bone : bones) {
			if (byName.put(bone.name(), bone) != null) {
				throw new IllegalArgumentException(source + ": two bones are named '" + bone.name() + "'");
			}
		}
		for (Bone bone : bones) {
			Set<String> seen = new HashSet<>();
			Bone current = bone;
			while (current.parent().isPresent()) {
				if (!seen.add(current.name())) {
					throw new IllegalArgumentException(source + ": bone '" + bone.name() + "' is its own ancestor");
				}
				String parent = current.parent().get();
				current = byName.get(parent);
				if (current == null) {
					throw new IllegalArgumentException(source + ": bone '" + bone.name() + "' names the parent '" + parent + "', which is not a bone");
				}
			}
		}
		List<Bone> mounts = bones.stream().filter(bone -> bone.role() == BoneRole.DRILL_MOUNT).toList();
		if (mounts.size() != 1) {
			throw new IllegalArgumentException(source + ": has " + mounts.size() + " drill_mount bones; a pod model has exactly one");
		}
		if (!hasDescendant(mounts.getFirst().name(), BoneRole.DRILL_HEAD)) {
			throw new IllegalArgumentException(source + ": no drill_head bone under drill_mount");
		}
		for (Bone ring : bones.stream().filter(bone -> bone.role() == BoneRole.DRILL_RING).toList()) {
			// A ring that does not ride the mount would not aim with the drill; one under the head would spin with it and stand still.
			if (!hasAncestor(byName, ring, BoneRole.DRILL_MOUNT) || hasAncestor(byName, ring, BoneRole.DRILL_HEAD)) {
				throw new IllegalArgumentException(source + ": drill_ring bone '" + ring.name() + "' must be under drill_mount and not under drill_head");
			}
		}
	}

	private static boolean hasAncestor(Map<String, Bone> byName, Bone bone, BoneRole role) {
		for (Bone current = bone; current.parent().isPresent(); ) {
			current = byName.get(current.parent().get());
			if (current.role() == role) {
				return true;
			}
		}
		return false;
	}

	private boolean hasDescendant(String name, BoneRole role) {
		for (Bone child : children(name)) {
			if (child.role() == role || hasDescendant(child.name(), role)) {
				return true;
			}
		}
		return false;
	}

	private static Bone bone(String source, JsonElement element, int textureWidth, int textureHeight) {
		JsonObject json = object(source, "a bone", element);
		String name = string(source, "a bone's name", json, "name");
		String where = "bone '" + name + "'";
		keys(source, where, json, BONE_KEYS);
		BoneRole role;
		try {
			role = BoneRole.of(name);
		} catch (IllegalArgumentException e) {
			throw new IllegalArgumentException(source + ": " + e.getMessage(), e);
		}
		Optional<String> parent = json.has("parent") ? Optional.of(string(source, where + " parent", json, "parent")) : Optional.empty();
		boolean mirror = json.has("mirror") && bool(source, where + " mirror", json.get("mirror"));
		float inflate = json.has("inflate") ? (float) number(source, where + " inflate", json.get("inflate")) : 0f;
		List<Cube> cubes = new ArrayList<>();
		if (json.has("cubes")) {
			JsonArray array = array(source, where + " cubes", json, "cubes");
			for (int i = 0; i < array.size(); i++) {
				cubes.add(cube(source, where + " cube " + i, array.get(i), mirror, inflate, textureWidth, textureHeight));
			}
		}
		return new Bone(name, role, parent, vec(source, where + " pivot", json, "pivot", false), vec(source, where + " rotation", json, "rotation", false),
				List.copyOf(cubes));
	}

	private static Cube cube(String source, String where, JsonElement element, boolean boneMirror, float boneInflate, int textureWidth,
			int textureHeight) {
		JsonObject json = object(source, where, element);
		keys(source, where, json, CUBE_KEYS);
		Vec3 origin = vec(source, where + " origin", json, "origin", true);
		Vec3 size = vec(source, where + " size", json, "size", true);
		if (size.x < 0 || size.y < 0 || size.z < 0) {
			throw new IllegalArgumentException(source + ": " + where + " has a negative size " + size);
		}
		JsonElement uv = json.get("uv");
		if (uv == null) {
			throw new IllegalArgumentException(source + ": " + where + " has no uv");
		}
		if (!uv.isJsonArray()) {
			throw new IllegalArgumentException(source + ": " + where + " has per-face UV; pod models use box UV, a uv of [u, v]");
		}
		JsonArray pair = uv.getAsJsonArray();
		if (pair.size() != 2) {
			throw new IllegalArgumentException(source + ": " + where + " uv is not [u, v]");
		}
		int u = wholeNumber(source, where + " uv", pair.get(0));
		int v = wholeNumber(source, where + " uv", pair.get(1));
		double spanU = u + 2 * size.z + 2 * size.x;
		double spanV = v + size.z + size.y;
		if (u < 0 || v < 0 || spanU > textureWidth || spanV > textureHeight) {
			throw new IllegalArgumentException(source + ": " + where + " box UV at [" + u + ", " + v + "] reaches [" + spanU + ", " + spanV
					+ "], outside the " + textureWidth + " x " + textureHeight + " texture");
		}
		boolean mirror = json.has("mirror") ? bool(source, where + " mirror", json.get("mirror")) : boneMirror;
		float inflate = json.has("inflate") ? (float) number(source, where + " inflate", json.get("inflate")) : boneInflate;
		Optional<Turn> turn = Optional.empty();
		if (json.has("rotation")) {
			if (!json.has("pivot")) {
				throw new IllegalArgumentException(source + ": " + where + " has a rotation and no pivot");
			}
			turn = Optional.of(new Turn(vec(source, where + " pivot", json, "pivot", true), vec(source, where + " rotation", json, "rotation", true)));
		} else if (json.has("pivot")) {
			throw new IllegalArgumentException(source + ": " + where + " has a pivot and no rotation");
		}
		return new Cube(origin, size, u, v, inflate, mirror, turn);
	}

	private static void keys(String source, String where, JsonObject json, Set<String> allowed) {
		for (String key : json.keySet()) {
			if (!allowed.contains(key)) {
				throw new IllegalArgumentException(source + ": " + where + " has the key '" + key + "', which a pod model does not use; known: "
						+ allowed);
			}
		}
	}

	private static JsonObject object(String source, String where, JsonElement element) {
		if (element == null || !element.isJsonObject()) {
			throw new IllegalArgumentException(source + ": " + where + " is not an object");
		}
		return element.getAsJsonObject();
	}

	private static JsonArray array(String source, String where, JsonObject json, String key) {
		JsonElement element = json.get(key);
		if (element == null || !element.isJsonArray()) {
			throw new IllegalArgumentException(source + ": " + where + " is not an array");
		}
		return element.getAsJsonArray();
	}

	private static String string(String source, String where, JsonObject json, String key) {
		JsonElement element = json.get(key);
		if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
			throw new IllegalArgumentException(source + ": " + where + " is not a string");
		}
		return element.getAsString();
	}

	private static boolean bool(String source, String where, JsonElement element) {
		if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isBoolean()) {
			throw new IllegalArgumentException(source + ": " + where + " is not true or false");
		}
		return element.getAsBoolean();
	}

	private static double number(String source, String where, JsonElement element) {
		if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
			throw new IllegalArgumentException(source + ": " + where + " is not a number");
		}
		double value = element.getAsDouble();
		if (!Double.isFinite(value)) {
			throw new IllegalArgumentException(source + ": " + where + " is not finite");
		}
		return value;
	}

	private static int wholeNumber(String source, String where, JsonElement element) {
		double value = number(source, where, element);
		if (value != Math.rint(value)) {
			throw new IllegalArgumentException(source + ": " + where + " is not a whole number: " + value);
		}
		return (int) value;
	}

	private static int positiveInt(String source, String key, JsonObject json) {
		int value = wholeNumber(source, key, json.get(key));
		if (value <= 0) {
			throw new IllegalArgumentException(source + ": " + key + " must be above 0, got " + value);
		}
		return value;
	}

	/** Three numbers at {@code key}: required, or zero when absent. */
	private static Vec3 vec(String source, String where, JsonObject json, String key, boolean required) {
		JsonElement element = json.get(key);
		if (element == null) {
			if (required) {
				throw new IllegalArgumentException(source + ": " + where + " is missing");
			}
			return Vec3.ZERO;
		}
		if (!element.isJsonArray() || element.getAsJsonArray().size() != 3) {
			throw new IllegalArgumentException(source + ": " + where + " is not three numbers");
		}
		JsonArray values = element.getAsJsonArray();
		return new Vec3(number(source, where, values.get(0)), number(source, where, values.get(1)), number(source, where, values.get(2)));
	}
}
