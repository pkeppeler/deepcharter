package io.github.pkeppeler.deepcharter.test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.colony.ColonyKit;

/**
 * Server GameTests for #335: the colony concepts' structure files load, name only blocks, properties and entities that exist,
 * and fit the pad the evidence scenario clears; and every state of every kit block has a model in its blockstate file.
 *
 * <p>Minecraft reads an unknown block in a structure as air and drops an unknown property without a word, so a renamed kit block
 * would only show as a hole in a still. These tests read the files themselves and fail instead.
 */
public class ColonyConceptsTest {
	/** The evidence scenario clears this far round the colony's centre; every piece must fit inside. */
	private static final int CLEARED_RADIUS = 56;
	private static final int CONCEPTS = 3;

	@GameTest
	public void everyConceptLoadsAndNamesOnlyRealBlocksAndEntities(GameTestHelper helper) throws IOException {
		MinecraftServer server = helper.getLevel().getServer();
		Map<Identifier, Resource> layouts = server.getResourceManager().listResources("colony_concept", id -> id.getPath().endsWith(".json"));
		if (layouts.size() != CONCEPTS) {
			throw fail(helper, List.of("found " + layouts.size() + " concept layouts, not " + CONCEPTS + ": " + layouts.keySet()));
		}
		List<String> problems = new ArrayList<>();
		for (Map.Entry<Identifier, Resource> entry : layouts.entrySet()) {
			JsonObject layout = json(entry.getValue());
			String concept = entry.getKey().getPath();
			if (layout.getAsJsonArray("views").isEmpty() || layout.getAsJsonArray("pieces").isEmpty()) {
				problems.add(concept + ": no views or no pieces");
			}
			for (JsonElement element : layout.getAsJsonArray("pieces")) {
				checkPiece(server, concept, element.getAsJsonObject(), problems);
			}
		}
		finish(helper, problems);
	}

	@GameTest
	public void everyKitBlockStateHasAVariant(GameTestHelper helper) throws IOException {
		List<String> problems = new ArrayList<>();
		for (Block block : ColonyKit.all()) {
			Identifier id = BuiltInRegistries.BLOCK.getKey(block);
			String path = "/assets/" + DeepCharter.MOD_ID + "/blockstates/" + id.getPath() + ".json";
			try (InputStream stream = ColonyConceptsTest.class.getResourceAsStream(path)) {
				if (stream == null) {
					problems.add(id + ": no " + path);
					continue;
				}
				JsonObject variants = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonObject("variants");
				List<Map<String, String>> keys = new ArrayList<>();
				for (String key : variants.keySet()) {
					keys.add(parseVariant(key));
				}
				for (BlockState state : block.getStateDefinition().getPossibleStates()) {
					Map<String, String> wanted = new HashMap<>();
					for (Property<?> property : state.getProperties()) {
						wanted.put(property.getName(), valueName(state, property));
					}
					if (!keys.contains(wanted)) {
						problems.add(id + ": no variant for " + wanted);
					}
				}
			}
		}
		if (ColonyKit.all().size() < 30) {
			problems.add("the kit has " + ColonyKit.all().size() + " blocks: the walk is not looking at ColonyKit");
		}
		finish(helper, problems);
	}

	private static void checkPiece(MinecraftServer server, String concept, JsonObject piece, List<String> problems) throws IOException {
		Identifier id = Identifier.parse(piece.get("structure").getAsString());
		Optional<StructureTemplate> template = server.getStructureTemplateManager().get(id);
		if (template.isEmpty() || template.get().getSize().getX() == 0) {
			problems.add(concept + ": " + id + " does not load");
			return;
		}
		var offset = piece.getAsJsonArray("offset");
		int x0 = offset.get(0).getAsInt();
		int z0 = offset.get(2).getAsInt();
		int x1 = x0 + template.get().getSize().getX() - 1;
		int z1 = z0 + template.get().getSize().getZ() - 1;
		if (Math.min(x0, z0) < -CLEARED_RADIUS || Math.max(x1, z1) > CLEARED_RADIUS) {
			problems.add(concept + ": " + id + " spans X " + x0 + ".." + x1 + ", Z " + z0 + ".." + z1 + ", outside the cleared " + CLEARED_RADIUS);
		}
		Identifier file = Identifier.fromNamespaceAndPath(id.getNamespace(), "structure/" + id.getPath() + ".nbt");
		CompoundTag root;
		try (InputStream stream = server.getResourceManager().getResourceOrThrow(file).open()) {
			root = NbtIo.readCompressed(stream, NbtAccounter.unlimitedHeap());
		}
		ListTag palette = root.getListOrEmpty("palette");
		ListTag blocks = root.getListOrEmpty("blocks");
		ListTag entities = root.getListOrEmpty("entities");
		if (blocks.size() != piece.get("blocks").getAsInt() || entities.size() != piece.get("displays").getAsInt()) {
			problems.add(concept + ": " + id + " holds " + blocks.size() + " blocks and " + entities.size() + " entities, the layout says "
					+ piece.get("blocks") + " and " + piece.get("displays") + ": rebuild with tools/colony/build.py");
		}
		for (Tag tag : palette) {
			checkState(concept + ": " + id + " palette", (CompoundTag) tag, problems);
		}
		for (Tag tag : entities) {
			CompoundTag entity = ((CompoundTag) tag).getCompoundOrEmpty("nbt");
			String type = entity.getStringOr("id", "");
			if (!BuiltInRegistries.ENTITY_TYPE.containsKey(Identifier.parse(type))) {
				problems.add(concept + ": " + id + " has an entity of unknown type '" + type + "'");
			} else if (entity.contains("block_state")) {
				checkState(concept + ": " + id + " display", entity.getCompoundOrEmpty("block_state"), problems);
			}
		}
	}

	/** The named block exists, and each property it names is the block's own and holds a value the block has. */
	private static void checkState(String where, CompoundTag state, List<String> problems) {
		Identifier name = Identifier.parse(state.getStringOr("Name", ""));
		Optional<Block> block = BuiltInRegistries.BLOCK.getOptional(name);
		if (block.isEmpty()) {
			problems.add(where + " names the unknown block " + name);
			return;
		}
		CompoundTag properties = state.getCompoundOrEmpty("Properties");
		for (String key : properties.keySet()) {
			Property<?> property = block.get().getStateDefinition().getProperty(key);
			String value = properties.getStringOr(key, "");
			if (property == null || property.getValue(value).isEmpty()) {
				problems.add(where + ": " + name + " has no " + key + "=" + value);
			}
		}
		if (properties.size() != block.get().getStateDefinition().getProperties().size()) {
			problems.add(where + ": " + name + " gives " + properties.keySet() + ", not every property of the block");
		}
	}

	private static <T extends Comparable<T>> String valueName(BlockState state, Property<T> property) {
		return property.getName(state.getValue(property));
	}

	private static Map<String, String> parseVariant(String key) {
		Map<String, String> out = new HashMap<>();
		if (key.isEmpty()) {
			return out;
		}
		for (String pair : key.split(",")) {
			String[] kv = pair.split("=", 2);
			out.put(kv[0], kv[1]);
		}
		return out;
	}

	private static JsonObject json(Resource resource) throws IOException {
		try (Reader reader = resource.openAsReader()) {
			return JsonParser.parseReader(reader).getAsJsonObject();
		}
	}

	private static void finish(GameTestHelper helper, List<String> problems) {
		if (!problems.isEmpty()) {
			throw fail(helper, problems);
		}
		helper.succeed();
	}

	private static RuntimeException fail(GameTestHelper helper, List<String> problems) {
		return helper.assertionException(Component.literal(problems.size() + " problem(s):\n  " + String.join("\n  ", problems)));
	}
}
