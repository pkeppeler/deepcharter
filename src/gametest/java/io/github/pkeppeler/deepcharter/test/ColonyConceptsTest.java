package io.github.pkeppeler.deepcharter.test;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
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
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.phys.AABB;


/**
 * Server GameTests for #335 and #353: the colony concepts' structure files are of the game's data version, load, name only blocks and
 * properties that exist, place every block and every display they hold, and fit the pad the evidence scenario clears.
 * (AssetCompletenessTest covers the kit blocks' blockstates and models.)
 *
 * <p>Minecraft reads an unknown block in a structure as air and drops an unknown property without a word, so a renamed kit block
 * would only show as a hole in a still. These tests read the files themselves and fail instead.
 */
public class ColonyConceptsTest {
	/** The evidence scenario clears this far round the colony's centre; every piece must fit inside. */
	private static final int CLEARED_RADIUS = 56;
	/** Where a piece is placed to be counted: far from the test structures and from the colony. */
	private static final int PLACE_AT = 40_000;

	/**
	 * Every layout's pieces place whole, and the layouts and the structure files agree both ways: each piece a layout names is a
	 * file, and each file is a piece of some layout. Every layout has a piece of its own (its square), so a layout file that went
	 * missing leaves a file no layout names.
	 */
	@GameTest
	public void everyConceptPiecePlacesWhole(GameTestHelper helper) throws IOException {
		MinecraftServer server = helper.getLevel().getServer();
		Map<Identifier, Resource> layouts = server.getResourceManager().listResources("colony_concept", id -> id.getPath().endsWith(".json"));
		if (layouts.isEmpty()) {
			throw fail(helper, List.of("found no concept layouts under data/deepcharter/colony_concept/: run tools/colony/build.py"));
		}
		List<String> problems = new ArrayList<>();
		Set<Identifier> named = new TreeSet<>();
		for (Map.Entry<Identifier, Resource> entry : layouts.entrySet()) {
			JsonObject layout = json(entry.getValue());
			String concept = entry.getKey().getPath();
			if (layout.getAsJsonArray("views").isEmpty() || layout.getAsJsonArray("pieces").isEmpty()) {
				problems.add(concept + ": no views or no pieces");
			}
			for (JsonElement element : layout.getAsJsonArray("pieces")) {
				named.add(Identifier.parse(element.getAsJsonObject().get("structure").getAsString()));
				checkPiece(server, concept, element.getAsJsonObject(), problems);
			}
		}
		Set<Identifier> files = new TreeSet<>();
		for (Identifier file : server.getResourceManager().listResources("structure/colony_concept", id -> id.getPath().endsWith(".nbt")).keySet()) {
			String path = file.getPath();
			files.add(Identifier.fromNamespaceAndPath(file.getNamespace(), path.substring("structure/".length(), path.length() - ".nbt".length())));
		}
		Set<Identifier> unnamed = new TreeSet<>(files);
		unnamed.removeAll(named);
		if (!unnamed.isEmpty()) {
			problems.add("no layout names the structure file(s) " + unnamed + ": a layout file is missing; run tools/colony/build.py");
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
		int version = root.getIntOr("DataVersion", -1);
		if (version != SharedConstants.getCurrentVersion().dataVersion().version()) {
			problems.add(concept + ": " + id + " is of data version " + version + ", the game " + SharedConstants.getCurrentVersion().dataVersion().version()
					+ ": set piece.DATA_VERSION in tools/colony/piece.py and check the structure format (26.3 changed the block-state keys)");
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
		placeWhole(server, concept + ": " + id, template.get(), blocks.size(), entities.size(), problems);
	}

	/**
	 * Places the template far from the test area and counts what landed: every block of the file, and every display with a block
	 * to draw. A display whose block state does not parse loads as a display of air, and a block that does not, as air; either
	 * shows as a hole. Then takes it all away again.
	 */
	private static void placeWhole(MinecraftServer server, String where, StructureTemplate template, int blocks, int displays, List<String> problems) {
		ServerLevel level = server.overworld();
		BlockPos at = new BlockPos(PLACE_AT, level.getMinY() + 100, PLACE_AT);
		Vec3i size = template.getSize();
		BoundingBox box = BoundingBox.fromCorners(at, at.offset(size).offset(-1, -1, -1));
		for (int cx = box.minX() >> 4; cx <= box.maxX() >> 4; cx++) {
			for (int cz = box.minZ() >> 4; cz <= box.maxZ() >> 4; cz++) {
				level.getChunk(cx, cz);
			}
		}
		AABB space = AABB.of(box).inflate(1);
		template.placeInWorld(level, at, at, new StructurePlaceSettings(), level.getRandom(), Block.UPDATE_CLIENTS);
		int placed = (int) BlockPos.betweenClosedStream(box).filter(pos -> !level.getBlockState(pos).isAir()).count();
		List<Display.BlockDisplay> shown = level.getEntitiesOfClass(Display.BlockDisplay.class, space);
		long drawn = shown.stream().filter(display -> !display.getBlockState().isAir()).count();
		if (placed != blocks || shown.size() != displays || drawn != displays) {
			problems.add(where + " placed " + placed + " of " + blocks + " blocks and " + drawn + " drawn displays (" + shown.size()
					+ " in all) of " + displays);
		}
		shown.forEach(Entity::discard);
		BlockPos.betweenClosedStream(box).forEach(pos -> level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS));
	}

	/** The named block exists, and each property it names is the block's own and holds a value the block has. */
	private static void checkState(String where, CompoundTag state, List<String> problems) {
		Identifier name = Identifier.parse(state.getStringOr("id", ""));
		Optional<Block> block = BuiltInRegistries.BLOCK.getOptional(name);
		if (block.isEmpty()) {
			problems.add(where + " names the unknown block " + name);
			return;
		}
		CompoundTag properties = state.getCompoundOrEmpty("properties");
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
