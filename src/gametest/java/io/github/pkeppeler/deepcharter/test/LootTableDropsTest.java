package io.github.pkeppeler.deepcharter.test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.Vec3;

import io.github.pkeppeler.deepcharter.DeepCharter;

/**
 * The drops of every loot table the mod ships, from the real {@code LootTable} with fixed seeds (#469). The tables are in the 26.3 format;
 * the pre-26.3 {@code conditions} and {@code functions} keys load without error and are ignored, so only a roll shows that a condition or a
 * count applies. No shipped table reads the tool, so silk touch and fortune change nothing, and a test pins that too.
 */
public class LootTableDropsTest {
	/** The one item that each single-item block drops: independent literals, not read from the registry. */
	private static final Map<String, String> SINGLE_DROP = Map.ofEntries(
			Map.entry("basalt_outcrop", "basalt_outcrop"), Map.entry("bronzium_ore", "bronzium"),
			Map.entry("cicatrium_ore", "cicatrium"), Map.entry("einsteinium_ore", "einsteinium"),
			Map.entry("fungal_stalk", "fungal_stalk"), Map.entry("goldium_ore", "goldium"),
			Map.entry("ironium_ore", "ironium"), Map.entry("ochre_regolith", "ochre_regolith"),
			Map.entry("platinium_ore", "platinium"), Map.entry("regolith", "regolith"),
			Map.entry("regolith_packed", "regolith_packed"), Map.entry("regolith_rock", "regolith_rock"),
			Map.entry("silverium_ore", "silverium"), Map.entry("slag_brick", "slag_brick"),
			Map.entry("stalk_planks", "stalk_planks"));

	/** The salvage crate's pools: item to its fewest and most per roll. */
	private record Range(String item, int min, int max) {
	}

	private static final List<Range> CRATE = List.of(new Range("minecraft:oak_planks", 1, 3), new Range("minecraft:stick", 0, 2),
			new Range("deepcharter:salvage_cloth", 0, 1), new Range("deepcharter:scrap_iron", 0, 2),
			new Range("deepcharter:scrap_copper", 0, 2), new Range("minecraft:redstone", 0, 1));

	/** Seeds 1 to this: seed 0 means "no seed" to a loot context, which then rolls from the world's random sequence. */
	private static final int SEEDS = 200;
	/** A blast so far from the block that its survival chance (1 over the radius) is about one in a million. */
	private static final float FAR_BLAST = 1_000_000f;

	@GameTest
	public void everyShippedLootTableIsCoveredHere(GameTestHelper helper) {
		Set<String> shipped = new TreeSet<>();
		helper.getLevel().getServer().reloadableRegistries().lookup().lookupOrThrow(Registries.LOOT_TABLE).listElementIds().map(ResourceKey::identifier)
				.filter(id -> id.getNamespace().equals(DeepCharter.MOD_ID)).forEach(id -> shipped.add(id.getPath()));
		Set<String> covered = new TreeSet<>();
		SINGLE_DROP.keySet().forEach(path -> covered.add("blocks/" + path));
		covered.add("blocks/salvage_crate");
		if (!shipped.equals(covered)) {
			throw helper.assertionException("the mod ships loot tables %s and this test covers %s", shipped, covered);
		}
		helper.succeed();
	}

	@GameTest
	public void aSingleDropBlockDropsItsItemOnceOnEverySeed(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		new TreeMap<>(SINGLE_DROP).forEach((path, item) -> {
			for (long seed = 1; seed <= SEEDS; seed++) {
				List<ItemStack> drops = roll(level, path, seed, null);
				if (!names(drops).equals(List.of("deepcharter:" + item)) || drops.getFirst().getCount() != 1) {
					throw helper.assertionException("%s on seed %s should drop 1 of deepcharter:%s, it drops %s", path, seed, item, drops);
				}
			}
		});
		helper.succeed();
	}

	/** {@code survives_explosion} keeps a drop with the chance of 1 over the blast radius: a radius of 1 keeps it always, a far blast loses it. */
	@GameTest
	public void aBlastAtTheBlockKeepsTheDropAndAFarBlastLosesIt(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		new TreeMap<>(SINGLE_DROP).forEach((path, item) -> {
			for (long seed = 1; seed <= SEEDS; seed++) {
				List<String> near = names(roll(level, path, seed, 1f));
				if (!near.equals(List.of("deepcharter:" + item))) {
					throw helper.assertionException("%s on seed %s with blast radius 1 should drop [deepcharter:%s], it drops %s", path, seed, item, near);
				}
				List<String> far = names(roll(level, path, seed, FAR_BLAST));
				if (!far.isEmpty()) {
					throw helper.assertionException("%s on seed %s with blast radius %s should drop nothing, it drops %s", path, seed, FAR_BLAST, far);
				}
			}
		});
		helper.succeed();
	}

	@GameTest
	public void theSalvageCrateDropsEachItemWithinItsCountAndReachesBothEnds(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		Map<String, Set<Integer>> seen = new TreeMap<>();
		for (long seed = 1; seed <= SEEDS; seed++) {
			List<ItemStack> drops = roll(level, "salvage_crate", seed, null);
			for (Range range : CRATE) {
				int count = drops.stream().filter(stack -> id(stack).equals(range.item())).mapToInt(ItemStack::getCount).sum();
				if (count < range.min() || count > range.max()) {
					throw helper.assertionException("salvage_crate seed %s drops %s of %s, expected %s to %s", seed, count, range.item(), range.min(), range.max());
				}
				seen.computeIfAbsent(range.item(), key -> new TreeSet<>()).add(count);
			}
			Set<String> known = new TreeSet<>();
			CRATE.forEach(range -> known.add(range.item()));
			for (ItemStack stack : drops) {
				if (!known.contains(id(stack))) {
					throw helper.assertionException("salvage_crate seed %s drops %s, which is not in its table", seed, id(stack));
				}
			}
		}
		for (Range range : CRATE) {
			Set<Integer> counts = new TreeSet<>();
			for (int count = range.min(); count <= range.max(); count++) {
				counts.add(count);
			}
			if (!seen.get(range.item()).equals(counts)) {
				throw helper.assertionException("salvage_crate drops %s in counts %s over %s seeds, expected every count of %s", range.item(),
						seen.get(range.item()), SEEDS, counts);
			}
		}
		helper.succeed();
	}

	@GameTest
	public void aFarBlastLosesTheWholeSalvageCrate(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		for (long seed = 1; seed <= SEEDS; seed++) {
			List<String> far = names(roll(level, "salvage_crate", seed, FAR_BLAST));
			if (!far.isEmpty()) {
				throw helper.assertionException("salvage_crate seed %s with blast radius %s should drop nothing, it drops %s", seed, FAR_BLAST, far);
			}
		}
		helper.succeed();
	}

	/** No shipped table reads the tool: a silk touch pick and a fortune pick drop what an empty hand does. */
	@GameTest
	public void theToolDoesNotChangeAnyDrop(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		List<String> paths = new ArrayList<>(SINGLE_DROP.keySet());
		paths.add("salvage_crate");
		for (String path : paths) {
			for (long seed = 1; seed <= 20; seed++) {
				List<String> bare = names(roll(level, path, seed, null));
				List<String> picked = names(roll(level, path, seed, null, new ItemStack(Items.DIAMOND_PICKAXE)));
				if (!bare.equals(picked)) {
					throw helper.assertionException("%s on seed %s drops %s by hand and %s with a pickaxe", path, seed, bare, picked);
				}
			}
		}
		helper.succeed();
	}

	private static List<ItemStack> roll(ServerLevel level, String path, long seed, Float blastRadius) {
		return roll(level, path, seed, blastRadius, ItemStack.EMPTY);
	}

	private static List<ItemStack> roll(ServerLevel level, String path, long seed, Float blastRadius, ItemStack tool) {
		var block = BuiltInRegistries.BLOCK.getValue(Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, path));
		ResourceKey<LootTable> key = block.getLootTable().orElseThrow();
		LootTable table = level.getServer().reloadableRegistries().getLootTable(key);
		LootParams.Builder builder = new LootParams.Builder(level).withParameter(LootContextParams.ORIGIN, Vec3.ZERO)
				.withParameter(LootContextParams.BLOCK_STATE, block.defaultBlockState()).withParameter(LootContextParams.TOOL, tool);
		if (blastRadius != null) {
			builder.withParameter(LootContextParams.EXPLOSION_RADIUS, blastRadius);
		}
		return table.getRandomItems(builder.create(LootContextParamSets.BLOCK), seed).stream().filter(stack -> !stack.isEmpty()).toList();
	}

	private static List<String> names(List<ItemStack> stacks) {
		return stacks.stream().map(LootTableDropsTest::id).sorted().toList();
	}

	private static String id(ItemStack stack) {
		return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
	}
}
