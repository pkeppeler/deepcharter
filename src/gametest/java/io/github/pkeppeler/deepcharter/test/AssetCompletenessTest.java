package io.github.pkeppeler.deepcharter.test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
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

import io.github.pkeppeler.deepcharter.DeepCharter;

/**
 * Server GameTests for #226: every block, item and entity the mod registers has the asset files a resource pack would replace. The
 * checks start from the registries, so a new registration with no assets fails here instead of showing as a purple-black cube.
 *
 * <p>A block needs a blockstate; an item needs an item definition; each model they name must exist, so must its parent, and so must
 * every texture the models list. Vanilla ids (the {@code minecraft} namespace) are not ours to check.
 */
public class AssetCompletenessTest {
	/**
	 * Entities drawn from a vanilla block, so they have no texture of their own until #258 makes them resource-pack models. Every other
	 * registered entity needs {@code textures/entity/<id>.png}.
	 */
	private static final Set<String> ENTITIES_DRAWN_FROM_VANILLA_BLOCKS = Set.of("pod", "prospector");

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
			checkDefinition(problems, "block " + id, "blockstates/" + id.getPath() + ".json");
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
			if (!ENTITIES_DRAWN_FROM_VANILLA_BLOCKS.contains(id.getPath())) {
				requireFile(problems, "entity " + id, "textures/entity/" + id.getPath() + ".png");
			}
		}
		for (String path : ENTITIES_DRAWN_FROM_VANILLA_BLOCKS) {
			if (!BuiltInRegistries.ENTITY_TYPE.containsKey(Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, path))) {
				problems.add("ENTITIES_DRAWN_FROM_VANILLA_BLOCKS lists " + path + ", which is not a registered entity: remove it");
			}
			if (AssetCompletenessTest.class.getResource(resource("textures/entity/" + path + ".png")) != null) {
				problems.add("entity " + path + " now has " + resource("textures/entity/" + path + ".png")
						+ ": it is drawn from its own assets, so remove it from ENTITIES_DRAWN_FROM_VANILLA_BLOCKS");
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

	/** The definition file must exist; every model it names must exist, with its parents and textures. */
	private static void checkDefinition(List<String> problems, String owner, String definition) {
		JsonElement json = requireJson(problems, owner, definition);
		if (json == null) {
			return;
		}
		List<String> models = new ArrayList<>();
		collectModelRefs(json, models);
		if (models.isEmpty()) {
			problems.add(owner + ": " + definition + " names no model");
		}
		Set<String> seen = new TreeSet<>();
		for (String ref : models) {
			checkModel(problems, owner, Identifier.parse(ref), seen);
		}
	}

	/** Every string under a key called {@code model}, at any depth: a blockstate's variants and parts, an item definition's tree. */
	private static void collectModelRefs(JsonElement json, List<String> out) {
		if (json.isJsonObject()) {
			for (var entry : json.getAsJsonObject().entrySet()) {
				if (entry.getKey().equals("model") && entry.getValue().isJsonPrimitive()) {
					out.add(entry.getValue().getAsString());
				} else {
					collectModelRefs(entry.getValue(), out);
				}
			}
		} else if (json.isJsonArray()) {
			for (JsonElement element : json.getAsJsonArray()) {
				collectModelRefs(element, out);
			}
		}
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
				if (ref.startsWith("#")) {
					continue;
				}
				Identifier texture = Identifier.parse(ref);
				if (texture.getNamespace().equals(DeepCharter.MOD_ID)) {
					requireFile(problems, owner + " (model " + model + ")", "textures/" + texture.getPath() + ".png");
				}
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

	private static void requireFile(List<String> problems, String owner, String path) {
		try (InputStream stream = AssetCompletenessTest.class.getResourceAsStream(resource(path))) {
			if (stream == null) {
				problems.add(owner + ": missing " + resource(path));
			} else if (stream.readAllBytes().length == 0) {
				problems.add(owner + ": " + resource(path) + " is empty");
			}
		} catch (IOException e) {
			problems.add(owner + ": " + resource(path) + " cannot be read: " + e.getMessage());
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
