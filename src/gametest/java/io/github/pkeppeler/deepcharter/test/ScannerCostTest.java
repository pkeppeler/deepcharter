package io.github.pkeppeler.deepcharter.test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SingleRecipeInput;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.hangar.HangarParts;
import io.github.pkeppeler.deepcharter.scanner.ScannerRegistry;
import io.github.pkeppeler.deepcharter.surface.SurfaceBlocks;
import io.github.pkeppeler.deepcharter.terminal.TerminalType;
import io.github.pkeppeler.deepcharter.terminal.TerminalTypes;
import io.github.pkeppeler.deepcharter.test.support.EarlyRunModel;
import io.github.pkeppeler.deepcharter.test.support.EarlyRunModel.Run;
import io.github.pkeppeler.deepcharter.test.support.EarlyRunModel.Zone;
import io.github.pkeppeler.deepcharter.pod.PodStats;
import io.github.pkeppeler.deepcharter.upgrade.ComponentTrack;
import io.github.pkeppeler.deepcharter.upgrade.UpgradeTuning;

/**
 * Server GameTests for #483, the cost of the crafted scanner against the bootstrap's timeline (mechanics.md, "The crafted scanner"). What the
 * recipe asks comes from the shipped recipe file, what a crate holds from the real loot table with fixed seeds (as {@link SalvageRenewalTest}
 * does), and the minutes from the walk to the yard, the break time of a crate and the furnace's cooking time, with the assumptions marked (A).
 * The kit is what a charter makes before its first run: the parts of the fuel pump, the ore processor and the upgrade terminal, and the Mole's
 * four parts at the hangar. No world is built.
 */
public class ScannerCostTest {
	private static final Logger LOGGER = LoggerFactory.getLogger(ScannerCostTest.class);

	/** How many different runs of crates the measurement starts at: crate n of a run is seed n, so each start is another player's luck. */
	private static final int STARTS = 1500;
	/** The longest run of crates one start may need before the measurement gives up. */
	private static final int MOST_CRATES = 200;

	/** (A) A player sprints, 5.612 blocks a second. */
	private static final double WALK_BLOCKS_PER_SECOND = 5.612;
	/** (A) A crate of a patch is reached in two seconds: a patch spreads its crates over 4 blocks round a point. */
	private static final double REACH_SECONDS = 2.0;
	/** (A) A crate breaks by hand in 1.5 times its hardness in seconds: it needs no tool, so the hand's speed of 1 applies, 30 ticks of damage for each point of hardness. */
	private static final double HAND_SECONDS_PER_HARDNESS = 1.5;
	/** (A) Putting the ingots in a grid and taking the part out. */
	private static final double CRAFT_SECONDS = 5.0;
	/** (A) One furnace, run one ingot at a time, with fuel to hand. */
	private static final int FURNACES = 1;

	/** The scanner adds at most this share to the crates of the kit, and at most this many minutes to its time. */
	private static final double MOST_EXTRA_CRATE_SHARE = 0.25;
	private static final double MOST_EXTRA_MINUTES = 2.0;
	/** A salvage patch holds up to 8 crates (placed_feature/salvage_litter.json): the scanner alone is about one patch, and 90 starts in 100 need no more than this. */
	private static final int PATCH_CRATES = 8;

	/** What a recipe asks, by ingredient. */
	private record Needs(int iron, int copper, int redstone) {
		Needs plus(Needs other) {
			return new Needs(iron + other.iron, copper + other.copper, redstone + other.redstone);
		}

		int ingots() {
			return iron + copper;
		}
	}

	private static JsonObject recipe(String path) {
		try (InputStream stream = ScannerCostTest.class.getResourceAsStream("/data/deepcharter/recipe/" + path + ".json")) {
			if (stream == null) {
				throw new IllegalStateException("no recipe " + path);
			}
			return JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
		} catch (IOException e) {
			throw new UncheckedIOException(path, e);
		}
	}

	/** The iron, copper and redstone the shipped shaped recipe of {@code item} asks for; any other ingredient fails the test. */
	private static Needs needs(String item) {
		JsonObject recipe = recipe(item);
		JsonObject key = recipe.getAsJsonObject("key");
		int iron = 0;
		int copper = 0;
		int redstone = 0;
		for (var row : recipe.getAsJsonArray("pattern")) {
			for (char cell : row.getAsString().toCharArray()) {
				if (cell == ' ') {
					continue;
				}
				switch (key.get(String.valueOf(cell)).getAsString()) {
					case "minecraft:iron_ingot" -> iron++;
					case "minecraft:copper_ingot" -> copper++;
					case "minecraft:redstone" -> redstone++;
					default -> throw new IllegalStateException(item + " asks for " + key.get(String.valueOf(cell)) + ", not iron, copper or redstone");
				}
			}
		}
		return new Needs(iron, copper, redstone);
	}

	private static String path(Item item) {
		return BuiltInRegistries.ITEM.getKey(item).getPath();
	}

	/** The parts a charter makes before its first run. */
	private static List<Item> kitParts() {
		List<Item> parts = new ArrayList<>();
		for (TerminalType type : List.of(TerminalTypes.FUEL_PUMP, TerminalTypes.ORE_PROCESSOR, TerminalTypes.UPGRADE_TERMINAL)) {
			parts.addAll(type.parts());
		}
		parts.addAll(HangarParts.ALL);
		return parts;
	}

	/** The metal of the kit. */
	private static Needs kit() {
		Needs total = new Needs(0, 0, 0);
		for (Item part : kitParts()) {
			total = total.plus(needs(path(part)));
		}
		return total;
	}

	/** Scrap iron, scrap copper and redstone of crates 1 to {@code count}, each from the real loot table with its own seed. */
	private static int[][] crateDrops(ServerLevel level, int count) {
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
	private static int cratesFor(int[][] drops, int start, Needs needs) {
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

	private record Spread(double mean, int median, int ninetieth) {
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

	private static Spread crates(ServerLevel level, Needs needs) {
		int[][] drops = crateDrops(level, STARTS + MOST_CRATES);
		int[] crates = new int[STARTS];
		for (int start = 0; start < STARTS; start++) {
			crates[start] = cratesFor(drops, start, needs);
		}
		return Spread.of(crates);
	}

	/** Minutes from leaving the colony to holding the parts: one trip to the yard and back, the crates, the furnace and the crafting. */
	private static double minutes(ServerLevel level, double crates, Needs needs, int parts) {
		double yardMiddle = (SalvageBootstrapTest.yardInnerRadius() + SalvageBootstrapTest.yardRadius()) / 2.0;
		double trip = 2 * yardMiddle / WALK_BLOCKS_PER_SECOND;
		double breakSeconds = SurfaceBlocks.SALVAGE_CRATE.defaultDestroyTime() * HAND_SECONDS_PER_HARDNESS;
		SingleRecipeInput scrap = new SingleRecipeInput(new ItemStack(BuiltInRegistries.ITEM.getValue(Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "scrap_iron"))));
		double smeltSeconds = level.recipeAccess().getRecipeFor(RecipeType.SMELTING, scrap, level)
				.orElseThrow(() -> new IllegalStateException("scrap iron does not smelt")).value().cookingTime() / 20.0;
		double seconds = trip + crates * (breakSeconds + REACH_SECONDS) + needs.ingots() * smeltSeconds / FURNACES + parts * CRAFT_SECONDS;
		return seconds / 60;
	}

	private static RuntimeException failure(GameTestHelper helper, String format, Object... args) {
		return helper.assertionException(Component.literal(String.format(format, args)));
	}

	@GameTest(maxTicks = 200)
	public void theRecipeAsksForTheBootstrapsMetalAndNoMoreThanAFewCrates(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		Needs scanner = needs(path(ScannerRegistry.SCANNER_UNIT));
		Needs kit = kit();
		Spread alone = crates(level, scanner);
		Spread kitCrates = crates(level, kit);
		Spread both = crates(level, kit.plus(scanner));
		double extraShare = (both.mean() - kitCrates.mean()) / kitCrates.mean();
		double scannerMinutes = minutes(level, alone.mean(), scanner, 1);
		double kitMinutes = minutes(level, kitCrates.mean(), kit, kitParts().size());
		double bothMinutes = minutes(level, both.mean(), kit.plus(scanner), kitParts().size() + 1);
		LOGGER.info("[scanner-cost] recipe: {} iron, {} copper, {} redstone", scanner.iron(), scanner.copper(), scanner.redstone());
		LOGGER.info("[scanner-cost] kit before the first run: {} iron, {} copper, {} redstone", kit.iron(), kit.copper(), kit.redstone());
		LOGGER.info("[scanner-cost] crates, the scanner alone: {}; the kit: {}; both: {}; the scanner adds {}%", alone, kitCrates, both, Math.round(extraShare * 100));
		LOGGER.info("[scanner-cost] minutes, the scanner alone: {}; the kit: {}; both: {}; the scanner adds {}",
				String.format("%.1f", scannerMinutes), String.format("%.1f", kitMinutes), String.format("%.1f", bothMinutes), String.format("%.1f", bothMinutes - kitMinutes));
		if (alone.ninetieth() > PATCH_CRATES) {
			throw failure(helper, "the scanner alone takes up to %d crates in 90 starts of 100, more than one patch of %d", alone.ninetieth(), PATCH_CRATES);
		}
		if (extraShare > MOST_EXTRA_CRATE_SHARE) {
			throw failure(helper, "the scanner adds %.0f%% to the kit's crates, at most %.0f%% allowed", extraShare * 100, MOST_EXTRA_CRATE_SHARE * 100);
		}
		if (bothMinutes - kitMinutes > MOST_EXTRA_MINUTES) {
			throw failure(helper, "the scanner adds %.1f minutes to the kit's %.1f, at most %.1f allowed", bothMinutes - kitMinutes, kitMinutes, MOST_EXTRA_MINUTES);
		}
		helper.succeed();
	}

	/**
	 * The scanner used to be a $200 buy, two layer 1 runs of a stock Mole before the first one (EarlyRunModel); now its first tier costs no money, and
	 * tier 2 keeps the standard ladder's price, which a layer 2 run pays in two runs at most (EconomyAffordabilityTest).
	 */
	@GameTest
	public void theCraftedTierCostsNoMoneyAndTheNextTierKeepsItsPrice(GameTestHelper helper) {
		Run firstRun = EarlyRunModel.run(Zone.load("topsoil_claims"), PodStats.base(), 0);
		long oldPrice = 200;
		LOGGER.info("[scanner-cost] the old tier 1 price of ${} was {} layer 1 runs of ${}", oldPrice, firstRun.toAfford(oldPrice), Math.round(firstRun.net()));
		if (UpgradeTuning.DEFAULT.price(ComponentTrack.SCANNER, 1) != 0 || UpgradeTuning.DEFAULT.price(ComponentTrack.SCANNER, 2) != 500) {
			throw failure(helper, "the scanner's tier 1 is crafted (price 0) and tier 2 stays $500, got $%d and $%d",
					UpgradeTuning.DEFAULT.price(ComponentTrack.SCANNER, 1), UpgradeTuning.DEFAULT.price(ComponentTrack.SCANNER, 2));
		}
		helper.succeed();
	}
}
