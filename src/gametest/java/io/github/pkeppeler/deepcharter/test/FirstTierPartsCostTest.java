package io.github.pkeppeler.deepcharter.test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.RecipeType;

import io.github.pkeppeler.deepcharter.pod.PodStats;
import io.github.pkeppeler.deepcharter.test.BootstrapCost.Needs;
import io.github.pkeppeler.deepcharter.test.BootstrapCost.Spread;
import io.github.pkeppeler.deepcharter.test.support.EarlyRunModel;
import io.github.pkeppeler.deepcharter.test.support.EarlyRunModel.Run;
import io.github.pkeppeler.deepcharter.test.support.EarlyRunModel.Zone;
import io.github.pkeppeler.deepcharter.upgrade.ComponentTrack;

/**
 * Server GameTests for #536 (ADR 0053, mechanics.md "Crafted first-tier parts"): what the six crafted units cost a new pilot, and that the
 * opening they make is no cheaper and no slower than the one the $200 purchases made. What a recipe asks comes from the shipped file,
 * what a crate holds from the real loot table, and a run's money and seconds from {@link EarlyRunModel} (the expected ore of a bore, so it
 * never flakes). {@link BootstrapCost} holds the method; no world is built.
 *
 * <p>The yardstick is time. A purchase cost money, which a run of a stock Mole earns at {@code net} dollars in {@code seconds}; a crafted unit costs
 * crates, a furnace and a bench, which take minutes. A unit is "as dear as" its old price when its minutes are the minutes of the runs that earned
 * that price. The money it saves is spent on the tiers above (a tier 2 part is still $500).
 */
public class FirstTierPartsCostTest {
	private static final Logger LOGGER = LoggerFactory.getLogger(FirstTierPartsCostTest.class);

	/**
	 * What a new pilot builds, in this order (mechanics.md): the parts that lift a layer 1 run first, by what each adds to the stock run (the tank $173
	 * against $115, the drill $162, the bay nothing alone, but it holds what the other two bring), then the hull for the depth, the engine for the
	 * weight, and the radiator for the heat of layer 3.
	 */
	private static final List<ComponentTrack> ORDER = List.of(ComponentTrack.FUEL_TANK, ComponentTrack.DRILL, ComponentTrack.CARGO_BAY,
			ComponentTrack.HULL, ComponentTrack.ENGINE, ComponentTrack.RADIATOR);

	/** What each part cost at the terminal before #536, in dollars: tier 1 of the original ladder, scaled by a quarter (UpgradeTuning). */
	private static final Map<ComponentTrack, Long> OLD_PRICE = Map.of(
			ComponentTrack.FUEL_TANK, 200L, ComponentTrack.DRILL, 200L, ComponentTrack.CARGO_BAY, 200L,
			ComponentTrack.HULL, 200L, ComponentTrack.ENGINE, 200L, ComponentTrack.RADIATOR, 500L);

	/** The ingredients a salvage crate gives or a bench makes from them: iron and copper ingots (melted scrap), redstone, and a block of nine ingots. */
	private static final Set<String> FROM_SALVAGE = Set.of("minecraft:iron_ingot", "minecraft:copper_ingot", "minecraft:redstone",
			"minecraft:iron_block", "minecraft:copper_block");

	/**
	 * (A) What a run takes besides the bore and the climb that {@link EarlyRunModel#runSeconds} counts: the walk from the processor to the pump and to the pod,
	 * boarding, the fuel purchase and the sale. About 100 blocks of sprint at 5.6 blocks a second and a few screens; a run of any pod pays it alike.
	 */
	private static final double RUN_OVERHEAD_SECONDS = 45;

	/** The kit and all six parts take fewer than this many crates in 90 starts of 100 (the kit alone takes 37, and the scanner about 7 more). */
	private static final int MOST_CRATES_OF_ALL_UNITS = 80;
	/** The six units are made as long as the crates round the colony last: a survey of 12 chunks holds about 160 (ADR 0047), and a charter takes under three quarters. */
	private static final int YARD_CRATES = 160;
	/** The whole set takes a charter this share of the minutes the old purchases took, at most and at least (the opening is "no noticeably cheaper or slower"). */
	private static final double SET_SHARE_MIN = 0.75;
	private static final double SET_SHARE_MAX = 1.25;
	/** One part takes this share of the minutes its old price took, at most and at least. */
	private static final double PART_SHARE_MIN = 0.5;
	private static final double PART_SHARE_MAX = 1.5;

	private static RuntimeException failure(GameTestHelper helper, String format, Object... args) {
		return helper.assertionException(Component.literal(String.format(format, args)));
	}

	private static String unitPath(ComponentTrack track) {
		return track.id() + "_unit";
	}

	private static Needs unitNeeds(ComponentTrack track) {
		return BootstrapCost.needs(unitPath(track));
	}

	/**
	 * A new pilot can reach the first dig: no unit's recipe asks for a pod part, an ore, the hangar's parts or any part of the kit. Each asks for
	 * what a crate gives (scrap melted to ingots, redstone) and for a block of nine ingots only where the bench makes it from them, and each fits the 3 by 3 grid.
	 * The stock Mole needs none of the units, so the order of the opening is the kit, the first run, and then the units as the crates allow.
	 */
	@GameTest
	public void everyUnitAsksOnlyForWhatTheYardGivesAndTheStockMoleNeedsNoUnit(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		for (ComponentTrack track : ORDER) {
			for (String cell : BootstrapCost.cells(BootstrapCost.recipe(unitPath(track)))) {
				if (!cell.isEmpty() && !FROM_SALVAGE.contains(cell)) {
					throw failure(helper, "the %s unit asks for %s, which the yard does not give", track, cell);
				}
			}
			var pattern = BootstrapCost.recipe(unitPath(track)).getAsJsonArray("pattern");
			if (pattern.size() > 3 || pattern.asList().stream().anyMatch(row -> row.getAsString().length() > 3)) {
				throw failure(helper, "the %s unit's pattern does not fit the 3 by 3 grid", track);
			}
		}
		for (String block : List.of("minecraft:iron_block", "minecraft:copper_block")) {
			boolean used = ORDER.stream().anyMatch(track -> BootstrapCost.cells(BootstrapCost.recipe(unitPath(track))).contains(block));
			if (used) {
				Item ingot = BuiltInRegistries.ITEM.getValue(Identifier.parse(block.replace("_block", "_ingot")));
				List<ItemStack> nine = new ArrayList<>();
				for (int i = 0; i < 9; i++) {
					nine.add(new ItemStack(ingot));
				}
				CraftingInput input = CraftingInput.of(3, 3, nine);
				ItemStack made = level.recipeAccess().getRecipeFor(RecipeType.CRAFTING, input, level).map(holder -> holder.value().assemble(input))
						.orElseThrow(() -> failure(helper, "nine ingots make no %s", block));
				if (!BuiltInRegistries.ITEM.getKey(made.getItem()).toString().equals(block)) {
					throw failure(helper, "nine ingots should make %s, made %s", block, made);
				}
			}
		}
		// The kit is what the stock Mole needs, and it must not ask for a unit: a unit's recipe and the kit's share no part.
		for (Item part : BootstrapCost.kitParts()) {
			for (String cell : BootstrapCost.cells(BootstrapCost.recipe(BootstrapCost.path(part)))) {
				if (cell.contains("_unit")) {
					throw failure(helper, "the kit part %s asks for %s: the first dig would wait on a unit", part, cell);
				}
			}
		}
		helper.succeed();
	}

	/**
	 * The crafting order from a fresh start: the kit, then each unit in {@link #ORDER}, drawing crate after crate from the real loot table (1,500
	 * starts). Every start gets there, and the whole set takes a share of the yard's crates that leaves the rest of the world its salvage.
	 */
	@GameTest(maxTicks = 200)
	public void aNewPilotCraftsTheKitAndThenEveryUnitFromOneYardsCrates(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		Needs total = BootstrapCost.kit();
		Spread kit = BootstrapCost.crates(level, total);
		LOGGER.info("[first-tier] the kit: {} iron, {} copper, {} redstone; crates {}", total.iron(), total.copper(), total.redstone(), kit);
		Spread last = kit;
		for (ComponentTrack track : ORDER) {
			Needs unit = unitNeeds(track);
			total = total.plus(unit);
			Spread now = BootstrapCost.crates(level, total);
			LOGGER.info("[first-tier] + {} unit ({} iron, {} copper, {} redstone): crates for the kit and the units so far {}; this unit adds {} on average",
					track.id(), unit.iron(), unit.copper(), unit.redstone(), now, String.format("%.1f", now.mean() - last.mean()));
			last = now;
		}
		LOGGER.info("[first-tier] the kit and all six units: {} iron, {} copper, {} redstone; {} of the {} crates of a 12-chunk survey",
				total.iron(), total.copper(), total.redstone(), last, YARD_CRATES);
		if (last.ninetieth() > MOST_CRATES_OF_ALL_UNITS) {
			throw failure(helper, "the kit and all six units take up to %d crates in 90 starts of 100, over the %d allowed", last.ninetieth(), MOST_CRATES_OF_ALL_UNITS);
		}
		if (MOST_CRATES_OF_ALL_UNITS * 4 > YARD_CRATES * 3) {
			throw failure(helper, "the allowed %d crates are over three quarters of the %d a survey holds", MOST_CRATES_OF_ALL_UNITS, YARD_CRATES);
		}
		helper.succeed();
	}

	/** The minutes of {@code crates} crates, the furnace for {@code unit}'s ingots and the bench. The walk to the yard is paid once, by the kit. */
	private static double minutes(ServerLevel level, double crates, Needs unit) {
		return (BootstrapCost.crateSeconds(crates) + unit.ingots() * BootstrapCost.smeltSeconds(level) + BootstrapCost.CRAFT_SECONDS) / 60;
	}

	/**
	 * The opening, before and after. Before: a stock Mole runs layer 1 and its charter buys the six parts at their old prices as the money comes, and each
	 * run earns what the parts bought so far allow. After: the charter makes the six units from crates and fits them, and spends no money. Both are measured
	 * in minutes of play, the runs on one side and the crates, the furnace and the bench on the other (EarlyRunModel's (A) assumptions and BootstrapCost's).
	 */
	@GameTest(maxTicks = 200)
	public void craftingTheSixUnitsTakesAboutTheMinutesThatEarningTheirOldPricesTook(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		Zone zone = Zone.load("topsoil_claims");
		PodStats stockStats = PodStats.base();
		Run stockRun = EarlyRunModel.run(zone, stockStats, 0);
		double stockSeconds = EarlyRunModel.runSeconds(zone, stockStats, 0, stockRun) + RUN_OVERHEAD_SECONDS;

		for (int[] only : new int[][] {{0, 1, 0}, {1, 0, 0}, {0, 0, 1}}) {
			Run lifted = EarlyRunModel.run(zone, EarlyRunModel.mole(only[0], only[1], only[2]), 0);
			LOGGER.info("[first-tier] a Mole with only a tier 1 {}: {} slabs, ${} net against the stock run's ${}", only[1] == 1 ? "tank" : only[0] == 1 ? "drill" : "bay",
					lifted.slabs(), Math.round(lifted.net()), Math.round(stockRun.net()));
		}

		// Before: runs, then purchases, until all six are bought.
		int[] tiers = new int[3]; // tank, drill, bay
		int bought = 0;
		long money = 0;
		double beforeSeconds = 0;
		int beforeRuns = 0;
		long spent = 0;
		while (bought < ORDER.size()) {
			PodStats stats = EarlyRunModel.mole(tiers[1], tiers[0], tiers[2]);
			Run run = EarlyRunModel.run(zone, stats, 0);
			beforeSeconds += EarlyRunModel.runSeconds(zone, stats, 0, run) + RUN_OVERHEAD_SECONDS;
			money += Math.round(run.net());
			beforeRuns++;
			while (bought < ORDER.size() && money >= OLD_PRICE.get(ORDER.get(bought))) {
				ComponentTrack track = ORDER.get(bought);
				money -= OLD_PRICE.get(track);
				spent += OLD_PRICE.get(track);
				switch (track) {
					case FUEL_TANK -> tiers[0] = 1;
					case DRILL -> tiers[1] = 1;
					case CARGO_BAY -> tiers[2] = 1;
					default -> {
					}
				}
				bought++;
			}
		}

		// After: the crates and the furnace and the bench for each unit, in order.
		Needs bag = BootstrapCost.kit();
		Spread before = BootstrapCost.crates(level, bag);
		double afterMinutes = 0;
		double inTheBag = 0;
		double oldMinutesOfAll = 0;
		List<String> outside = new ArrayList<>();
		for (ComponentTrack track : ORDER) {
			Needs unit = unitNeeds(track);
			bag = bag.plus(unit);
			Spread now = BootstrapCost.crates(level, bag);
			// Alone, the crates that unit's own metal takes. In the bag the leftovers of the crates that came before it count too, so the set is cheaper than its parts.
			double minutes = minutes(level, BootstrapCost.crates(level, unit).mean(), unit);
			inTheBag += minutes(level, now.mean() - before.mean(), unit);
			double oldMinutes = OLD_PRICE.get(track) / stockRun.net() * stockSeconds / 60;
			oldMinutesOfAll += oldMinutes;
			afterMinutes += minutes;
			LOGGER.info("[first-tier] {} unit: {} min of crates, furnace and bench; its old ${} took {} min of stock runs (share {})",
					track.id(), String.format("%.1f", minutes), OLD_PRICE.get(track), String.format("%.1f", oldMinutes), String.format("%.2f", minutes / oldMinutes));
			if (minutes / oldMinutes < PART_SHARE_MIN || minutes / oldMinutes > PART_SHARE_MAX) {
				outside.add(String.format("the %s unit takes %.1f minutes, %.2f of the %.1f its old $%d took", track.id(), minutes, minutes / oldMinutes, oldMinutes,
						OLD_PRICE.get(track)));
			}
			before = now;
		}
		double beforeMinutes = beforeSeconds / 60;
		double kitMinutes = BootstrapCost.minutes(level, BootstrapCost.crates(level, BootstrapCost.kit()).mean(), BootstrapCost.kit(), BootstrapCost.kitParts().size());
		PodStats tierOne = EarlyRunModel.mole(1, 1, 1);
		Run tierOneRun = EarlyRunModel.run(zone, tierOne, 0);
		LOGGER.info("[first-tier] the kit takes {} min; the first full layer 1 run of a stock Mole: {} s, ${} net, {} slabs, {} L", String.format("%.1f", kitMinutes),
				String.format("%.0f", stockSeconds), Math.round(stockRun.net()), stockRun.slabs(), String.format("%.1f", stockRun.litres()));
		LOGGER.info("[first-tier] a Mole with tier 1 drill, tank and bay: {} s, ${} net, {} slabs", String.format("%.0f", EarlyRunModel.runSeconds(zone, tierOne, 0, tierOneRun) + RUN_OVERHEAD_SECONDS),
				Math.round(tierOneRun.net()), tierOneRun.slabs());
		LOGGER.info("[first-tier] before: {} layer 1 runs ({} min) and ${} spent to buy all six; after: {} min of crates, furnace and bench ({} with the kit's leftovers) and $0 spent; the six old prices at the stock run's pace: {} min",
				beforeRuns, String.format("%.1f", beforeMinutes), spent, String.format("%.1f", afterMinutes), String.format("%.1f", inTheBag), String.format("%.1f", oldMinutesOfAll));
		double setShare = afterMinutes / beforeMinutes;
		LOGGER.info("[first-tier] the six crafted take {} of the minutes the six bought took", String.format("%.2f", setShare));
		if (!outside.isEmpty()) {
			throw failure(helper, "%s; %.2f to %.2f are allowed for one part", outside, PART_SHARE_MIN, PART_SHARE_MAX);
		}
		if (setShare < SET_SHARE_MIN || setShare > SET_SHARE_MAX) {
			throw failure(helper, "the six units take %.1f minutes against the %.1f minutes of runs that bought them (share %.2f); %.2f to %.2f are allowed",
					afterMinutes, beforeMinutes, setShare, SET_SHARE_MIN, SET_SHARE_MAX);
		}
		helper.succeed();
	}
}
