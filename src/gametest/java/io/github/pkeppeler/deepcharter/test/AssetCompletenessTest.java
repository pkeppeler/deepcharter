package io.github.pkeppeler.deepcharter.test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import io.github.pkeppeler.deepcharter.DeepCharter;

/**
 * Server GameTests for #226 and #242: every block, item and entity the mod registers has the asset files a resource pack would
 * replace. The checks start from the registries, so a new registration with no assets fails here instead of showing as a
 * purple-black cube.
 *
 * <p>A block needs a blockstate with a variant for each of its states; an item needs an item definition; each model they name must
 * exist, so must its parent, and so must every texture the models list, glow layers included. A connected casing
 * ({@code "fabric:type": "deepcharter:connected"}, ADR 0037) needs its five tiles. A texture taller than it is wide is an animation:
 * it needs a {@code .png.mcmeta} with an {@code animation}, and a whole number of square frames. Vanilla ids (the {@code minecraft}
 * namespace) are not ours to check.
 */
public class AssetCompletenessTest {
	/**
	 * Pods render GeckoLib models from their look files, with their textures under textures/entity/pod/ (see SkinAssetsTest), so they
	 * have no {@code textures/entity/<id>.png}. Every other registered entity needs one.
	 */
	private static final Set<String> ENTITIES_DRAWN_FROM_LOOK_FILES = Set.of("pod", "prospector");
	private static final String CONNECTED = DeepCharter.MOD_ID + ":connected";
	private static final List<String> CONNECTED_TILES = List.of("alone", "horizontal", "vertical", "corner", "centre");
	private static final byte[] PNG_SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'};

	@GameTest
	public void theRegistriesAreNotEmpty(GameTestHelper helper) {
		if (ours(BuiltInRegistries.BLOCK).size() < 15 || ours(BuiltInRegistries.ITEM).size() < 50 || ours(BuiltInRegistries.ENTITY_TYPE).size() < 3) {
			throw fail(helper, List.of("the walk found %d blocks, %d items and %d entities of %s: it is not looking at the registries"
					.formatted(ours(BuiltInRegistries.BLOCK).size(), ours(BuiltInRegistries.ITEM).size(),
							ours(BuiltInRegistries.ENTITY_TYPE).size(), DeepCharter.MOD_ID)));
		}
		helper.succeed();
	}

	@GameTest
	public void everyBlockHasABlockstateAndItsModelsAndTextures(GameTestHelper helper) {
		List<String> problems = new ArrayList<>();
		for (Identifier id : ours(BuiltInRegistries.BLOCK)) {
			String definition = "blockstates/" + id.getPath() + ".json";
			JsonElement json = checkDefinition(problems, "block " + id, definition);
			if (json != null) {
				checkEveryStateHasAVariant(problems, "block " + id, definition, BuiltInRegistries.BLOCK.getValue(id), json);
			}
		}
		finish(helper, problems);
	}

	@GameTest
	public void everyItemHasAnItemDefinitionAndItsModelsAndTextures(GameTestHelper helper) {
		List<String> problems = new ArrayList<>();
		for (Identifier id : ours(BuiltInRegistries.ITEM)) {
			checkDefinition(problems, "item " + id, "items/" + id.getPath() + ".json");
		}
		finish(helper, problems);
	}

	@GameTest
	public void everyEntityHasItsTexture(GameTestHelper helper) {
		List<String> problems = new ArrayList<>();
		for (Identifier id : ours(BuiltInRegistries.ENTITY_TYPE)) {
			if (!ENTITIES_DRAWN_FROM_LOOK_FILES.contains(id.getPath())) {
				requireFile(problems, "entity " + id, "textures/entity/" + id.getPath() + ".png");
			}
		}
		for (String path : ENTITIES_DRAWN_FROM_LOOK_FILES) {
			if (!BuiltInRegistries.ENTITY_TYPE.containsKey(Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, path))) {
				problems.add("ENTITIES_DRAWN_FROM_LOOK_FILES lists " + path + ", which is not a registered entity: remove it");
			}
			if (AssetCompletenessTest.class.getResource(resource("textures/entity/" + path + ".png")) != null) {
				problems.add("entity " + path + " now has " + resource("textures/entity/" + path + ".png")
						+ ": it is drawn from its own assets, so remove it from ENTITIES_DRAWN_FROM_LOOK_FILES");
			}
		}
		finish(helper, problems);
	}

	private static Set<Identifier> ours(Registry<?> registry) {
		Set<Identifier> ids = new TreeSet<>();
		for (Identifier id : registry.keySet()) {
			if (id.getNamespace().equals(DeepCharter.MOD_ID)) {
				ids.add(id);
			}
		}
		return ids;
	}

	/**
	 * The definition file must exist; every model it names must exist, with its parents and textures, and every connected-casing
	 * model must name its tiles. Returns the parsed file, or null when it is missing or does not parse.
	 */
	private static JsonElement checkDefinition(List<String> problems, String owner, String definition) {
		JsonElement json = requireJson(problems, owner, definition);
		if (json == null) {
			return null;
		}
		List<String> models = new ArrayList<>();
		List<String> textures = new ArrayList<>();
		collectRefs(problems, owner + " (" + definition + ")", json, models, textures);
		if (models.isEmpty() && textures.isEmpty()) {
			problems.add(owner + ": " + definition + " names no model");
		}
		Set<String> seen = new TreeSet<>();
		for (String ref : models) {
			checkModel(problems, owner, Identifier.parse(ref), seen);
		}
		for (String ref : textures) {
			checkTexture(problems, owner + " (connected tile)", Identifier.parse(ref));
		}
		return json;
	}

	/**
	 * Every string under a key called {@code model}, at any depth (a blockstate's variants and parts, an item definition's tree),
	 * into {@code models}; the tiles of each connected-casing model into {@code textures}. Any other custom model type is a problem.
	 */
	private static void collectRefs(List<String> problems, String owner, JsonElement json, List<String> models, List<String> textures) {
		if (json.isJsonObject()) {
			JsonObject object = json.getAsJsonObject();
			if (object.has("fabric:type")) {
				String type = object.get("fabric:type").getAsString();
				if (!type.equals(CONNECTED)) {
					problems.add(owner + ": unknown model type " + type);
					return;
				}
				JsonObject tiles = object.has("tiles") ? object.getAsJsonObject("tiles") : new JsonObject();
				for (String tile : CONNECTED_TILES) {
					if (tiles.has(tile)) {
						textures.add(tiles.get(tile).getAsString());
					} else {
						problems.add(owner + ": the connected model has no " + tile + " tile");
					}
				}
				return;
			}
			for (var entry : object.entrySet()) {
				if (entry.getKey().equals("model") && entry.getValue().isJsonPrimitive()) {
					models.add(entry.getValue().getAsString());
				} else {
					collectRefs(problems, owner, entry.getValue(), models, textures);
				}
			}
		} else if (json.isJsonArray()) {
			for (JsonElement element : json.getAsJsonArray()) {
				collectRefs(problems, owner, element, models, textures);
			}
		}
	}

	/**
	 * A {@code variants} blockstate must match every state of the block, or that state renders as the missing model: an
	 * {@code active} property added to a block with no variants for it, say. A multipart file may show nothing for a state.
	 */
	private static void checkEveryStateHasAVariant(List<String> problems, String owner, String definition, Block block, JsonElement json) {
		if (!json.isJsonObject() || !json.getAsJsonObject().has("variants")) {
			return;
		}
		List<Map<String, String>> keys = new ArrayList<>();
		for (String key : json.getAsJsonObject().getAsJsonObject("variants").keySet()) {
			Map<String, String> values = new LinkedHashMap<>();
			for (String pair : key.isEmpty() ? new String[0] : key.split(",")) {
				String[] parts = pair.split("=", 2);
				values.put(parts[0], parts.length == 2 ? parts[1] : "");
			}
			keys.add(values);
		}
		for (BlockState state : block.getStateDefinition().getPossibleStates()) {
			if (keys.stream().noneMatch(key -> matches(state, key))) {
				problems.add(owner + ": " + definition + " has no variant for " + state);
			}
		}
	}

	private static boolean matches(BlockState state, Map<String, String> key) {
		for (var entry : key.entrySet()) {
			Property<?> property = state.getBlock().getStateDefinition().getProperty(entry.getKey());
			if (property == null || !valueName(state, property).equals(entry.getValue())) {
				return false;
			}
		}
		return true;
	}

	private static <T extends Comparable<T>> String valueName(BlockState state, Property<T> property) {
		return property.getName(state.getValue(property));
	}

	private static void checkModel(List<String> problems, String owner, Identifier model, Set<String> seen) {
		if (!model.getNamespace().equals(DeepCharter.MOD_ID) || !seen.add(model.toString())) {
			return;
		}
		JsonElement json = requireJson(problems, owner, "models/" + model.getPath() + ".json");
		if (json == null || !json.isJsonObject()) {
			return;
		}
		JsonObject object = json.getAsJsonObject();
		if (object.has("parent")) {
			checkModel(problems, owner, Identifier.parse(object.get("parent").getAsString()), seen);
		}
		if (object.has("textures")) {
			for (var entry : object.getAsJsonObject("textures").entrySet()) {
				String ref = entry.getValue().getAsString();
				if (!ref.startsWith("#")) {
					checkTexture(problems, owner + " (model " + model + ")", Identifier.parse(ref));
				}
			}
		}
	}

	/** The texture exists; one taller than wide is animated, so it has a .png.mcmeta with an animation and square frames. */
	private static void checkTexture(List<String> problems, String owner, Identifier texture) {
		if (!texture.getNamespace().equals(DeepCharter.MOD_ID)) {
			return;
		}
		String path = "textures/" + texture.getPath() + ".png";
		byte[] png = requireFile(problems, owner, path);
		if (png == null) {
			return;
		}
		if (png.length < 24 || !Arrays.equals(Arrays.copyOf(png, 8), PNG_SIGNATURE)) {
			problems.add(owner + ": " + resource(path) + " is not a PNG");
			return;
		}
		int width = ByteBuffer.wrap(png, 16, 4).getInt();
		int height = ByteBuffer.wrap(png, 20, 4).getInt();
		boolean hasMeta = AssetCompletenessTest.class.getResource(resource(path + ".mcmeta")) != null;
		if (height != width || hasMeta) {
			JsonElement meta = requireJson(problems, owner + " (" + width + " x " + height + ", so animated)", path + ".mcmeta");
			if (meta != null && (!meta.isJsonObject() || !meta.getAsJsonObject().has("animation"))) {
				problems.add(owner + ": " + resource(path + ".mcmeta") + " has no animation");
			}
			if (height % width != 0) {
				problems.add(owner + ": " + resource(path) + " is " + width + " x " + height + ", not a whole number of square frames");
			}
		}
	}

	private static JsonElement requireJson(List<String> problems, String owner, String path) {
		InputStream stream = AssetCompletenessTest.class.getResourceAsStream(resource(path));
		if (stream == null) {
			problems.add(owner + ": missing " + resource(path));
			return null;
		}
		try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
			return JsonParser.parseReader(reader);
		} catch (IOException | RuntimeException e) {
			problems.add(owner + ": " + resource(path) + " does not parse: " + e.getMessage());
			return null;
		}
	}

	/** The file's bytes, or null (and a problem) when it is missing, empty or unreadable. */
	private static byte[] requireFile(List<String> problems, String owner, String path) {
		try (InputStream stream = AssetCompletenessTest.class.getResourceAsStream(resource(path))) {
			if (stream == null) {
				problems.add(owner + ": missing " + resource(path));
				return null;
			}
			byte[] bytes = stream.readAllBytes();
			if (bytes.length == 0) {
				problems.add(owner + ": " + resource(path) + " is empty");
				return null;
			}
			return bytes;
		} catch (IOException e) {
			problems.add(owner + ": " + resource(path) + " cannot be read: " + e.getMessage());
			return null;
		}
	}

	private static String resource(String path) {
		return "/assets/" + DeepCharter.MOD_ID + "/" + path;
	}

	private static void finish(GameTestHelper helper, List<String> problems) {
		if (!problems.isEmpty()) {
			throw fail(helper, problems);
		}
		helper.succeed();
	}

	private static RuntimeException fail(GameTestHelper helper, List<String> problems) {
		return helper.assertionException(Component.literal(problems.size() + " asset problem(s):\n  " + String.join("\n  ", problems)));
	}
}
