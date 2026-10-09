package io.github.pkeppeler.deepcharter.client.pod;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.function.Predicate;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * What the game knows of a pod model, read from the Bedrock entity geometry, the {@code .geo.json} that Blockbench exports and
 * GeckoLib draws (ADR 0030, #243). The game reads the file a second time, here, to check it and to measure it: how wide its
 * cutters are, how far they reach, how big a box holds the whole model. Box UV only. Every bone name is a {@link BoneRole} word,
 * and there is one {@code drill_mount} with a {@code drill_head} under it; a {@code drill_ring} is under the mount and not under
 * the head. Anything else, or anything malformed, throws with the file and the place named.
 *
 * <p>A model may hold several cutters (ADR 0040): bone sets named {@code drill_head_<cutter>} and {@code drill_ring_<cutter>},
 * of which the pod shows the one its drill tier picks. A model with plain {@code drill_head} and {@code drill_ring} bones has
 * one cutter, which is always shown.
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
	/**
	 * A drill that reaches this far from its axis, in pixels, spins at the full rate. A wider one spins slower, by the square of its
	 * reach: one that reaches twice as far turns at a quarter of the rate, so a giant cutter turns with weight and not like a toy.
	 * Every round-1 drill reaches 7.5 or less; the round-3 cones reach about 12.7, so they turn at 0.4.
	 */
	private static final double FULL_SPIN_REACH = 8;
	private static final int CUBE_CORNERS = 8;
	private static final double PIXELS_PER_BLOCK = 16;
	/** The angles, in degrees, at which {@link #cullingBox} samples the drill mount's swing. */
	private static final int SWING_STEP = 15;

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

	/** How fast this model's drill spins, as a share of the full rate: 1 up to a reach of {@value #FULL_SPIN_REACH} pixels. */
	public double drillSpinScale() {
		return spinScale(drillReach());
	}

	/** {@link #drillSpinScale()} for the one cutter {@code cutter} names (see {@link #cutters}). */
	public double drillSpinScale(String cutter) {
		return spinScale(drillReach(cutter));
	}

	private static double spinScale(double reach) {
		double share = FULL_SPIN_REACH / reach;
		return Math.min(1, share * share);
	}

	/** The names of the cutters this model holds, in file order: the {@code <cutter>} of each {@code drill_head_<cutter>} and {@code drill_ring_<cutter>} bone. Empty for a model with one plain cutter. */
	public List<String> cutters() {
		Set<String> names = new LinkedHashSet<>();
		for (Bone bone : bones) {
			cutterOf(bone).ifPresent(names::add);
		}
		return List.copyOf(names);
	}

	/** The cutter that a {@code drill_head_<cutter>} or {@code drill_ring_<cutter>} bone belongs to; empty for any other bone, and for a plain {@code drill_head} or {@code drill_ring}. */
	public static Optional<String> cutterOf(Bone bone) {
		String prefix = switch (bone.role()) {
			case DRILL_HEAD -> "drill_head_";
			case DRILL_RING -> "drill_ring_";
			default -> null;
		};
		if (prefix == null || !bone.name().startsWith(prefix)) {
			return Optional.empty();
		}
		return Optional.of(bone.name().substring(prefix.length()));
	}

	/** True for a bone that is a cutter's own, or under one: {@code drill_head}, {@code drill_ring} and what rides them, of the cutter {@code cutter} (null: of any). */
	public boolean inCutter(Bone bone, String cutter) {
		Map<String, Bone> byName = new HashMap<>();
		bones.forEach(each -> byName.put(each.name(), each));
		for (Bone at = bone; at != null; at = at.parent().map(byName::get).orElse(null)) {
			if (at.role() == BoneRole.DRILL_HEAD || at.role() == BoneRole.DRILL_RING) {
				return cutter == null || cutterOf(at).map(cutter::equals).orElse(false);
			}
		}
		return false;
	}

	/**
	 * How far the cubes of the spinning drill bones ({@code drill_head} and {@code drill_ring}, not their children) reach from the
	 * bone's axis across x or y, in pixels: half the width of the cutter at its widest. A turned cube counts at its turned corners,
	 * so the flutes of an auger cone or the tilted rollers of a tricone bit count for the width they really have. The bone's own
	 * rest rotation is not applied: a spinning bone is drawn upright, and turns about its own z axis. Only the cubes of the spinning
	 * bones themselves count, not those of bones under them. Every cube of the round-3 cones, including the side cones of the tricone
	 * and the cluster, sits directly in {@code drill_head} or {@code drill_ring}, so all of them count; a round-1 {@code cutter} child
	 * bone (a turned copy of the drill) does not, and its reach is that of its parent's. Of every cutter the model holds.
	 */
	public double drillReach() {
		return reachOf(null);
	}

	/** {@link #drillReach()} of the one cutter {@code cutter} names. */
	public double drillReach(String cutter) {
		return reachOf(cutter);
	}

	private double reachOf(String cutter) {
		double reach = 0;
		for (Bone spinning : bones.stream().filter(bone -> bone.role() == BoneRole.DRILL_HEAD || bone.role() == BoneRole.DRILL_RING)
				.filter(bone -> cutter == null || cutterOf(bone).map(cutter::equals).orElse(false)).toList()) {
			for (Cube cube : spinning.cubes()) {
				for (int corner = 0; corner < CUBE_CORNERS; corner++) {
					Vec3 point = turnedCorner(cube, corner);
					Vec3 across = point.subtract(spinning.pivot());
					reach = Math.max(reach, Math.max(Math.abs(across.x), Math.abs(across.y)));
				}
			}
		}
		return reach;
	}

	/** The extent of every cube corner at rest, turned by its own rotation and its bones', in pixels: {minX, minY, minZ, maxX, maxY, maxZ}. */
	public double[] restBounds() {
		return restBounds(bone -> true);
	}

	/** {@link #restBounds()} over the cubes of the bones {@code only} accepts. */
	public double[] restBounds(Predicate<Bone> only) {
		return bounds(only, OptionalDouble.empty());
	}

	/** {@link #restBounds(Predicate)} with the drill mount turned to {@code pitch} degrees instead of its rest angle (0 level, 90 straight down). */
	public double[] restBounds(Predicate<Bone> only, double pitch) {
		return bounds(only, OptionalDouble.of(pitch));
	}

	private double[] bounds(Predicate<Bone> only, OptionalDouble pitch) {
		Map<String, Bone> byName = new HashMap<>();
		bones.forEach(bone -> byName.put(bone.name(), bone));
		double[] bounds = {Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
		for (Bone bone : bones.stream().filter(only).toList()) {
			for (Cube cube : bone.cubes()) {
				for (int corner = 0; corner < CUBE_CORNERS; corner++) {
					Vec3 p = turnedCorner(cube, corner);
					for (Bone at = bone; at != null; at = at.parent().map(byName::get).orElse(null)) {
						Vec3 rotation = pitch.isPresent() && at.role() == BoneRole.DRILL_MOUNT
								? new Vec3(pitch.getAsDouble(), at.rotation().y, at.rotation().z) : at.rotation();
						p = turn(p, at.pivot(), rotation);
					}
					double[] xyz = {p.x, p.y, p.z};
					for (int i = 0; i < 3; i++) {
						bounds[i] = Math.min(bounds[i], xyz[i]);
						bounds[i + 3] = Math.max(bounds[i + 3], xyz[i]);
					}
				}
			}
		}
		return bounds;
	}

	/**
	 * The box the renderer culls this model by, in blocks, round the pod's feet: the model's bounds in y, and in x and z a square
	 * as wide as the furthest any corner of its bounds is from the pod's middle, so it holds the model at any heading. The bounds
	 * are of the whole model, every cutter it holds, and of the drill mount at its rest angle and at every {@value #SWING_STEP}
	 * degrees down to straight down, so the cutter's tip is held where it leads the hitbox and where it sinks under the floor.
	 */
	public AABB cullingBox() {
		double[] b = restBounds();
		for (int pitch = 0; pitch <= 90; pitch += SWING_STEP) {
			double[] swung = restBounds(bone -> true, pitch);
			for (int i = 0; i < 3; i++) {
				b[i] = Math.min(b[i], swung[i]);
				b[i + 3] = Math.max(b[i + 3], swung[i + 3]);
			}
		}
		double reach = Math.max(Math.max(Math.hypot(b[0], b[2]), Math.hypot(b[0], b[5])), Math.max(Math.hypot(b[3], b[2]), Math.hypot(b[3], b[5])));
		return new AABB(-reach / PIXELS_PER_BLOCK, b[1] / PIXELS_PER_BLOCK, -reach / PIXELS_PER_BLOCK, reach / PIXELS_PER_BLOCK, b[4] / PIXELS_PER_BLOCK,
				reach / PIXELS_PER_BLOCK);
	}

	/** Corner {@code corner} (0 to 7) of {@code cube}, turned by the cube's own rotation if it has one. */
	private static Vec3 turnedCorner(Cube cube, int corner) {
		Vec3 point = cube.origin().add((corner & 1) * cube.size().x, (corner >> 1 & 1) * cube.size().y, (corner >> 2 & 1) * cube.size().z);
		return cube.turn().map(turn -> turn(point, turn.pivot(), turn.rotation())).orElse(point);
	}

	/** Bedrock's rotation in y-up space: x, then y, then z, as the file means it (a positive x angle tips a cutter at -z downward). */
	private static Vec3 turn(Vec3 point, Vec3 pivot, Vec3 degrees) {
		return point.subtract(pivot).xRot((float) Math.toRadians(degrees.x)).yRot((float) Math.toRadians(degrees.y))
				.zRot((float) Math.toRadians(degrees.z)).add(pivot);
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
