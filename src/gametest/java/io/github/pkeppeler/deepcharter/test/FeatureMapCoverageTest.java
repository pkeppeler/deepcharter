package io.github.pkeppeler.deepcharter.test;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.colony.ColonyKit;
import io.github.pkeppeler.deepcharter.repair.Consumable;
import io.github.pkeppeler.deepcharter.terminal.TerminalType;
import io.github.pkeppeler.deepcharter.terminal.TerminalTypes;
import io.github.pkeppeler.deepcharter.upgrade.ComponentTrack;

/**
 * The feature map (#510) covers the game. Every registered {@code deepcharter:} item, block, entity type, terminal,
 * upgrade track and consumable must be named in the {@code covers} list of a node of {@code docs/feature-map/map.json}, and every such
 * entry must name something that is registered, so a removed feature leaves the map too. Entries look like {@code item:scrap_iron},
 * {@code block:company_rock}, {@code entity:pod}, {@code terminal:fuel_pump}, {@code consumable:dynamite} and {@code track:drill}.
 *
 * <p>Exclusions, kept short and explicit: a block item is judged as its block; a track's part item ({@code part_<track>}) is judged as the
 * track; a consumable's item as the consumable; and the colony kit, 24 building blocks that are fields of {@link ColonyKit}, is one entry,
 * {@code group:colony-kit}, so a new plate or pipe needs no node of its own.
 */
public class FeatureMapCoverageTest {
	private static final String MAP = "docs/feature-map/map.json";
	private static final String KIT_GROUP = "group:colony-kit";
	/** The entry kinds this test checks against the registries; the others (chapter, zone, order, group) are the map's own. */
	private static final Set<String> CHECKED_KINDS = Set.of("item", "block", "entity", "terminal", "consumable", "track");

	@GameTest
	public void everyRegisteredFeatureIsOnTheFeatureMap(GameTestHelper helper) throws IOException {
		Set<String> required = required();
		Set<String> covered = covered(helper);
		Set<String> missing = new TreeSet<>(required);
		missing.removeAll(covered);
		Set<String> stale = new TreeSet<>();
		for (String entry : covered) {
			boolean checked = CHECKED_KINDS.contains(entry.substring(0, entry.indexOf(':')));
			if (checked && !required.contains(entry)) {
				stale.add(entry);
			}
		}
		if (!covered.contains(KIT_GROUP)) {
			missing.add(KIT_GROUP);
		}
		if (!missing.isEmpty() || !stale.isEmpty()) {
			throw failure(helper, "%s is out of step with the game. Registered but on no node's covers: %s. Named by a node but not registered: %s. "
					+ "Add the feature to a node, or fix the entry, then run python3 -I tools/feature_map.py (skill: .claude/skills/feature-map/SKILL.md)",
					MAP, missing, stale);
		}
		helper.succeed();
	}

	/** What the game registers, as map entries. */
	private static Set<String> required() {
		Set<String> kit = new HashSet<>();
		for (Field field : ColonyKit.class.getFields()) {
			if (Modifier.isStatic(field.getModifiers()) && Block.class.isAssignableFrom(field.getType())) {
				try {
					kit.add(BuiltInRegistries.BLOCK.getKey((Block) field.get(null)).getPath());
				} catch (IllegalAccessException e) {
					throw new IllegalStateException("cannot read the colony kit field " + field.getName(), e);
				}
			}
		}
		Set<String> terminals = new HashSet<>();
		for (TerminalType type : TerminalTypes.all()) {
			terminals.add(type.id().getPath());
		}
		Set<String> consumables = new HashSet<>();
		Arrays.stream(Consumable.values()).forEach(consumable -> consumables.add(consumable.itemId().getPath()));
		Set<String> partItems = new HashSet<>();
		Set<String> required = new TreeSet<>();
		for (ComponentTrack track : ComponentTrack.values()) {
			partItems.add(track.itemId().getPath());
			required.add("track:" + track.id());
		}
		consumables.forEach(path -> required.add("consumable:" + path));
		Set<String> blocks = new HashSet<>();
		BuiltInRegistries.BLOCK.keySet().stream().filter(FeatureMapCoverageTest::ours).map(Identifier::getPath).forEach(blocks::add);
		for (String path : blocks) {
			if (terminals.contains(path)) {
				required.add("terminal:" + path);
			} else if (!kit.contains(path)) {
				required.add("block:" + path);
			}
		}
		BuiltInRegistries.ITEM.keySet().stream().filter(FeatureMapCoverageTest::ours).map(Identifier::getPath)
				.filter(path -> !blocks.contains(path) && !partItems.contains(path) && !consumables.contains(path))
				.forEach(path -> required.add("item:" + path));
		BuiltInRegistries.ENTITY_TYPE.keySet().stream().filter(FeatureMapCoverageTest::ours).map(Identifier::getPath).forEach(path -> required.add("entity:" + path));
		return required;
	}

	/** Every {@code covers} entry of the map. The map is a repo file, not a resource: game tests run in build/run/gameTest, so walk up to the repo. */
	private static Set<String> covered(GameTestHelper helper) throws IOException {
		Path dir = Path.of("").toAbsolutePath();
		while (dir != null && !Files.isRegularFile(dir.resolve(MAP))) {
			dir = dir.getParent();
		}
		if (dir == null) {
			throw failure(helper, "cannot find %s above %s", MAP, Path.of("").toAbsolutePath());
		}
		Set<String> covered = new TreeSet<>();
		for (JsonElement node : JsonParser.parseString(Files.readString(dir.resolve(MAP))).getAsJsonObject().getAsJsonArray("nodes")) {
			JsonObject object = node.getAsJsonObject();
			if (object.has("covers")) {
				object.getAsJsonArray("covers").forEach(entry -> covered.add(entry.getAsString()));
			}
		}
		return covered;
	}

	private static RuntimeException failure(GameTestHelper helper, String format, Object... args) {
		return helper.assertionException(Component.literal(String.format(format, args)));
	}

	private static boolean ours(Identifier id) {
		return id.getNamespace().equals(DeepCharter.MOD_ID);
	}
}
