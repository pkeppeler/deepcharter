package io.github.pkeppeler.deepcharter.test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SingleRecipeInput;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.hangar.HangarParts;
import io.github.pkeppeler.deepcharter.ore.OreType;
import io.github.pkeppeler.deepcharter.surface.SurfaceBlocks;
import io.github.pkeppeler.deepcharter.terminal.TerminalType;
import io.github.pkeppeler.deepcharter.terminal.TerminalTypes;

/**
 * The cost of the bootstrap's recipes in salvage crates and minutes, shared by {@link ScannerCostTest} and {@link FirstTierPartsCostTest}. What a
 * recipe asks comes from the shipped recipe file, what a crate holds from the real loot table with fixed seeds (as {@link SalvageRenewalTest}
 * does), and the minutes from the walk to the yard, the break time of a crate and the furnace's cooking time, with the assumptions marked (A).
 * No world is built.
 */
final class BootstrapCost {
	/** How many different runs of crates the measurement starts at: crate n of a run is seed n, so each start is another player's luck. */
	static final int STARTS = 1500;
	/** The longest run of crates one start may need before the measurement gives up. */
	static final int MOST_CRATES = 200;

	/** (A) A player sprints, 5.612 blocks a second. */
	private static final double WALK_BLOCKS_PER_SECOND = 5.612;
	/** (A) A crate of a patch is reached in two seconds: a patch spreads its crates over 4 blocks round a point. */
	private static final double REACH_SECONDS = 2.0;
	/** (A) A crate breaks by hand in 1.5 times its hardness in seconds: it needs no tool, so the hand's speed of 1 applies, 30 ticks of damage for each point of hardness. */
	private static final double HAND_SECONDS_PER_HARDNESS = 1.5;
	/** (A) Putting the ingots in a grid and taking the part out. */
	static final double CRAFT_SECONDS = 5.0;
	/** (A) One furnace, run one ingot at a time, with fuel to hand. */
	private static final int FURNACES = 1;

	private BootstrapCost() {
	}

	/**
	 * What a recipe asks, by ingredient. An iron or copper block is nine ingots of its metal, made at a crafting table from nine. {@code ore} is the
	 * dollars the ore items in it would have sold for (hand-mined ore, which the crates do not give).
	 */
	record Needs(int iron, int copper, int redstone, int ore) {
		static final Needs NONE = new Needs(0, 0, 0, 0);

		Needs plus(Needs other) {
			return new Needs(iron + other.iron, copper + other.copper, redstone + other.redstone, ore + other.ore);
		}

		/** The ingots a furnace must cook. */
		int ingots() {
			return iron + copper;
		}

		int items() {
			return iron + copper + redstone;
		}
	}

	/** The shipped shaped recipe of {@code item}, as the JSON in the mod's data. */
	static JsonObject recipe(String path) {
		try (InputStream stream = BootstrapCost.class.getResourceAsStream("/data/deepcharter/recipe/" + path + ".json")) {
			if (stream == null) {
				throw new IllegalStateException("no recipe " + path);
			}
			return JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
		} catch (IOException e) {
			throw new UncheckedIOException(path, e);
		}
	}

	/** The ingredient ids of each cell of a shaped recipe, row by row; an empty cell is "". */
	static List<String> cells(JsonObject recipe) {
		JsonObject key = recipe.getAsJsonObject("key");
		List<String> cells = new ArrayList<>();
		for (var row : recipe.getAsJsonArray("pattern")) {
			for (char cell : row.getAsString().toCharArray()) {
				cells.add(cell == ' ' ? "" : key.get(String.valueOf(cell)).getAsString());
			}
		}
		return cells;
	}

	/** The iron, copper, redstone and ore the shipped shaped recipe of {@code item} asks for; any other ingredient fails the test. */
	static Needs needs(String item) {
		int iron = 0;
		int copper = 0;
		int redstone = 0;
		int ore = 0;
		for (String cell : cells(recipe(item))) {
			switch (cell) {
				case "" -> {
				}
				case "minecraft:iron_ingot" -> iron++;
				case "minecraft:iron_block" -> iron += 9;
				case "minecraft:copper_ingot" -> copper++;
				case "minecraft:copper_block" -> copper += 9;
				case "minecraft:redstone" -> redstone++;
				default -> ore += oreValue(item, cell);
			}
		}
		return new Needs(iron, copper, redstone, ore);
	}

	/** The sale value of an ore item named by a recipe cell; any other ingredient fails the test. */
	private static int oreValue(String item, String cell) {
		for (OreType type : OreType.values()) {
			if (type.itemId().toString().equals(cell)) {
				return type.value();
			}
		}
		throw new IllegalStateException(item + " asks for " + cell + ", not iron, copper, redstone or an ore");
	}

	static String path(Item item) {
		return BuiltInRegistries.ITEM.getKey(item).getPath();
	}

	/** The parts a charter makes before its first run. */
	static List<Item> kitParts() {
		List<Item> parts = new ArrayList<>();
		for (TerminalType type : List.of(TerminalTypes.FUEL_PUMP, TerminalTypes.ORE_PROCESSOR, TerminalTypes.UPGRADE_TERMINAL)) {
			parts.addAll(type.parts());
		}
		parts.addAll(HangarParts.ALL);
		return parts;
	}

	/** The metal of the kit. */
	static Needs kit() {
		Needs total = Needs.NONE;
		for (Item part : kitParts()) {
			total = total.plus(needs(path(part)));
		}
		return total;
	}

	/** Scrap iron, scrap copper and redstone of crates 1 to {@code count}, each from the real loot table with its own seed. */
	static int[][] crateDrops(ServerLevel level, int count) {
		int[][] drops = new int[count + 1][3];
		for (int crate = 1; crate <= count; crate++) {
			for (ItemStack drop : SalvageBootstrapTest.drops(level, SurfaceBlocks.SALVAGE_CRATE, crate)) {
				String path = BuiltInRegistries.ITEM.getKey(drop.getItem()).toString();
				if (path.equals(DeepCharter.MOD_ID + ":scrap_iron")) {
					drops[crate][0] += drop.getCount();
				} else if (path.equals(DeepCharter.MOD_ID + ":scrap_copper")) {
					drops[crate][1] += drop.getCount();
				} else if (path.equals("minecraft:redstone")) {
					drops[crate][2] += drop.getCount();
				}
			}
		}
		return drops;
	}

	/** The crates a player breaks, starting after crate {@code start}, until the bag holds {@code needs}. */
	static int cratesFor(int[][] drops, int start, Needs needs) {
		int iron = 0;
		int copper = 0;
		int redstone = 0;
		for (int crate = 1; crate <= MOST_CRATES; crate++) {
			int[] drop = drops[start + crate];
			iron += drop[0];
			copper += drop[1];
			redstone += drop[2];
			if (iron >= needs.iron() && copper >= needs.copper() && redstone >= needs.redstone()) {
				return crate;
			}
		}
		throw new IllegalStateException(MOST_CRATES + " crates did not hold " + needs);
	}

	record Spread(double mean, int median, int ninetieth) {
		static Spread of(int[] crates) {
			int[] sorted = crates.clone();
			Arrays.sort(sorted);
			return new Spread(Arrays.stream(sorted).average().orElse(0), sorted[sorted.length / 2], sorted[sorted.length * 9 / 10]);
		}

		@Override
		public String toString() {
			return String.format("mean %.1f, median %d, 90th %d", mean, median, ninetieth);
		}
	}

	static Spread crates(ServerLevel level, Needs needs) {
		int[][] drops = crateDrops(level, STARTS + MOST_CRATES);
		int[] crates = new int[STARTS];
		for (int start = 0; start < STARTS; start++) {
			crates[start] = cratesFor(drops, start, needs);
		}
		return Spread.of(crates);
	}

	/** Seconds to cook one ingot of scrap in a furnace, from the real smelting recipe. */
	static double smeltSeconds(ServerLevel level) {
		SingleRecipeInput scrap = new SingleRecipeInput(new ItemStack(BuiltInRegistries.ITEM.getValue(Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "scrap_iron"))));
		return level.recipeAccess().getRecipeFor(RecipeType.SMELTING, scrap, level)
				.orElseThrow(() -> new IllegalStateException("scrap iron does not smelt")).value().cookingTime() / 20.0;
	}

	/** Seconds of the walk to the yard and back: once, to its middle. */
	static double tripSeconds() {
		double yardMiddle = (SalvageBootstrapTest.yardInnerRadius() + SalvageBootstrapTest.yardRadius()) / 2.0;
		return 2 * yardMiddle / WALK_BLOCKS_PER_SECOND;
	}

	/** Seconds at the crates: reaching and breaking {@code crates} of them. */
	static double crateSeconds(double crates) {
		return crates * (SurfaceBlocks.SALVAGE_CRATE.defaultDestroyTime() * HAND_SECONDS_PER_HARDNESS + REACH_SECONDS);
	}

	/** Minutes from leaving the colony to holding the parts: one trip to the yard and back, the crates, the furnace and the crafting. */
	static double minutes(ServerLevel level, double crates, Needs needs, int parts) {
		double seconds = tripSeconds() + crateSeconds(crates) + needs.ingots() * smeltSeconds(level) / FURNACES + parts * CRAFT_SECONDS;
		return seconds / 60;
	}
}
