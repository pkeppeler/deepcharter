package io.github.pkeppeler.deepcharter.test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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

import io.github.pkeppeler.deepcharter.ore.OreType;
import io.github.pkeppeler.deepcharter.pod.PodStats;
import io.github.pkeppeler.deepcharter.test.BootstrapCost.Needs;
import io.github.pkeppeler.deepcharter.test.BootstrapCost.Spread;
import io.github.pkeppeler.deepcharter.test.support.EarlyRunModel;
import io.github.pkeppeler.deepcharter.test.support.EarlyRunModel.Run;
import io.github.pkeppeler.deepcharter.test.support.EarlyRunModel.Zone;
import io.github.pkeppeler.deepcharter.upgrade.ComponentTrack;

/**
 * Server GameTests for #536 (ADR 0053, mechanics.md "Crafted first-tier parts"): what the crafted units cost a pilot, and that the
 * opening the first six make is no cheaper and no slower than the one the $200 purchases made. The seven hazard answers (lights, hopper, brace, sounder, liner,
 * heat shield, console) are tuned to the minutes their old prices took, and take ore from the pod's cargo, which the crates do not give. What a recipe asks comes from the shipped file,
 * what a crate holds from the real loot table, and a run's money and seconds from {@link EarlyRunModel} (the expected ore of a bore, so it
 * never flakes). {@link BootstrapCost} holds the method; no world is built.
 *
 * <p>The yardstick is time. A purchase cost money, which a run earns at {@code net} dollars in {@code seconds}; a crafted unit costs
 * crates, a furnace and a bench, which take minutes, and ore withdrawn from the cargo, which is what a run would have sold: its dollars are minutes at the run's rate. A unit is "as dear as" its old price when its minutes are the minutes of the runs that earned
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

	/**
	 * The seven hazard answers, by the layer of the hazard each meets first: the lights, the hopper, the brace and the console in layer 1 (the dark, the lava
	 * ladder's first rung, the crust, the signal decay at the foot of the layer), the sounder, liner and heat shield in layer 2 (gas, lava). Each was bought, before #536, with the layer's runs.
	 */
	private static final List<ComponentTrack> HAZARDS = List.of(ComponentTrack.LIGHTS, ComponentTrack.SPOIL_HOPPER, ComponentTrack.BRACE,
			ComponentTrack.SOUNDER, ComponentTrack.LINER, ComponentTrack.HEAT_SHIELD, ComponentTrack.CONSOLE);
	private static final Set<ComponentTrack> LAYER_TWO = Set.of(ComponentTrack.SOUNDER, ComponentTrack.LINER, ComponentTrack.HEAT_SHIELD);
	/** The zone whose ore a hazard answer's recipe asks for: the ore must lie where the pilot is when the hazard first bites (ZONE of the layer, not the deepest). */
	private static final Map<ComponentTrack, String> ORE_ZONE = Map.of(
			ComponentTrack.LIGHTS, "topsoil_claims", ComponentTrack.SPOIL_HOPPER, "topsoil_claims", ComponentTrack.BRACE, "deep_claim",
			ComponentTrack.SOUNDER, "stone_benches", ComponentTrack.LINER, "upper_levels", ComponentTrack.HEAT_SHIELD, "upper_levels", ComponentTrack.CONSOLE, "topsoil_claims");

	/** What each part cost at the terminal before #536 and #483, in dollars (UpgradeTuning). */
	private static final Map<ComponentTrack, Long> OLD_PRICE = Map.ofEntries(
			Map.entry(ComponentTrack.FUEL_TANK, 200L), Map.entry(ComponentTrack.DRILL, 200L), Map.entry(ComponentTrack.CARGO_BAY, 200L),
			Map.entry(ComponentTrack.HULL, 200L), Map.entry(ComponentTrack.ENGINE, 200L), Map.entry(ComponentTrack.RADIATOR, 500L),
			Map.entry(ComponentTrack.LIGHTS, 125L), Map.entry(ComponentTrack.SPOIL_HOPPER, 100L), Map.entry(ComponentTrack.BRACE, 200L),
			Map.entry(ComponentTrack.SOUNDER, 400L), Map.entry(ComponentTrack.LINER, 500L), Map.entry(ComponentTrack.HEAT_SHIELD, 750L), Map.entry(ComponentTrack.CONSOLE, 200L));

	private static final List<ComponentTrack> ALL = Stream.concat(ORDER.stream(), HAZARDS.stream()).toList();

	/** The ingredients a salvage crate gives or a bench makes from them: iron and copper ingots (melted scrap), redstone, and a block of nine ingots. */
	private static final Set<String> FROM_SALVAGE = Set.of("minecraft:iron_ingot", "minecraft:copper_ingot", "minecraft:redstone",
			"minecraft:iron_block", "minecraft:copper_block");
	/** The ores a hazard answer may take: the ones a layer 1 or 2 haul holds in numbers. */
	private static final Set<OreType> ORES = Set.of(OreType.IRONIUM, OreType.BRONZIUM, OreType.SILVERIUM, OreType.GOLDIUM);

	/**
	 * (A) What a run takes besides the bore and the climb that {@link EarlyRunModel#runSeconds} counts: the walk from the processor to the pump and to the pod,
	 * boarding, the fuel purchase and the sale. About 100 blocks of sprint at 5.6 blocks a second and a few screens; a run of any pod pays it alike.
	 */
	private static final double RUN_OVERHEAD_SECONDS = 45;

	/** A hazard unit's ore is in the expected haul of this many runs of its layer, at most (the heat shield's 2 Goldium are about 7: the rarest ore asked for). */
	private static final int MOST_RUNS_FOR_THE_ORE = 8;

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

	/** What a run earns and takes, for the run a part was priced against. */
	private record Pace(double net, double seconds) {
		/** The minutes of runs that earn {@code dollars}. */
		double minutesFor(double dollars) {
			return dollars / net * seconds / 60;
		}
	}

	/** The run of a stock Mole in layer 1, which the first six parts and the layer 1 hazard answers were priced against. */
	private static Pace layerOne() {
		Zone zone = Zone.load("topsoil_claims");
		Run run = EarlyRunModel.run(zone, PodStats.base(), 0);
		return new Pace(run.net(), EarlyRunModel.runSeconds(zone, PodStats.base(), 0, run) + RUN_OVERHEAD_SECONDS);
	}

	/** The run of a stock Mole in the zone {@code name} of layer 1. */
	private static Run layerOneRun(String name) {
		return EarlyRunModel.run(Zone.load(name), PodStats.base(), 0);
	}

	/** The run of a Mole with tier 2 parts in layer 2, whose ore is the liner's and heat shield's. */
	private static Run layerTwoRun() {
		return EarlyRunModel.run(Zone.load("upper_levels"), EarlyRunModel.mole(2, 2, 2), EarlyRunModel.layerOneBlocks());
	}

	/** The run of a Mole with tier 2 parts in layer 2, which the layer 2 hazard answers were priced against (EconomyAffordabilityTest). */
	private static Pace layerTwo() {
		Zone zone = Zone.load("upper_levels");
		PodStats stats = EarlyRunModel.mole(2, 2, 2);
		Run run = EarlyRunModel.run(zone, stats, EarlyRunModel.layerOneBlocks());
		return new Pace(run.net(), EarlyRunModel.runSeconds(zone, stats, EarlyRunModel.layerOneBlocks(), run) + RUN_OVERHEAD_SECONDS);
	}

	private static Pace paceOf(ComponentTrack track) {
		return LAYER_TWO.contains(track) ? layerTwo() : layerOne();
	}

	/**
	 * A new pilot can reach the first dig: no unit's recipe asks for a pod part, the hangar's parts or any part of the kit. Each asks for what a crate
	 * gives (scrap melted to ingots, redstone) or, for a hazard answer, an ore, and for a block of nine ingots only where the bench makes it from them. Each
	 * fits the 3 by 3 grid. The stock Mole needs none of the units, so the order of the opening is the kit, the first run, and then the units as the crates and the ore allow.
	 */
	@GameTest
	public void everyUnitAsksOnlyForWhatTheYardOrTheRockGivesAndTheStockMoleNeedsNoUnit(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		Set<String> oreIds = ORES.stream().map(type -> type.itemId().toString()).collect(Collectors.toSet());
		for (ComponentTrack track : ALL) {
			for (String cell : BootstrapCost.cells(BootstrapCost.recipe(unitPath(track)))) {
				if (cell.isEmpty()) {
					continue;
				}
				boolean ore = oreIds.contains(cell);
				if (!FROM_SALVAGE.contains(cell) && !ore) {
					throw failure(helper, "the %s unit asks for %s, which neither the yard nor the rock gives", track, cell);
				}
				if (ore && !HAZARDS.contains(track)) {
					throw failure(helper, "the %s unit asks for ore: only a hazard answer may, the first six units are made on the surface from the crates", track);
				}
			}
			var pattern = BootstrapCost.recipe(unitPath(track)).getAsJsonArray("pattern");
			if (pattern.size() > 3 || pattern.asList().stream().anyMatch(row -> row.getAsString().length() > 3)) {
				throw failure(helper, "the %s unit's pattern does not fit the 3 by 3 grid", track);
			}
		}
		for (String block : List.of("minecraft:iron_block", "minecraft:copper_block")) {
			boolean used = ALL.stream().anyMatch(track -> BootstrapCost.cells(BootstrapCost.recipe(unitPath(track))).contains(block));
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
	 * A hazard unit's ore comes out of the pod's cargo at the processor (WITHDRAW ORE), so it is part of the haul of the zone where the hazard first bites: each ore it
	 * asks for lies in that zone, and the expected haul of the runs the unit was priced against holds it in at most {@link #MOST_RUNS_FOR_THE_ORE} runs. The runs are priced
	 * at their net, which counts that ore as sold, and a kept ore is that sale forgone.
	 */
	@GameTest
	public void theOreOfAHazardUnitIsInTheHaulWhereTheHazardDoes(GameTestHelper helper) {
		for (ComponentTrack track : HAZARDS) {
			Zone zone = Zone.load(ORE_ZONE.get(track));
			Run run = ORE_ZONE.get(track).equals("upper_levels") ? layerTwoRun() : layerOneRun(ORE_ZONE.get(track));
			Map<String, Integer> asked = new HashMap<>();
			for (String cell : BootstrapCost.cells(BootstrapCost.recipe(unitPath(track)))) {
				if (!cell.isEmpty() && cell.startsWith("deepcharter:")) {
					asked.merge(cell, 1, Integer::sum);
				}
			}
			double runs = 0;
			for (OreType type : ORES) {
				int need = asked.getOrDefault(type.itemId().toString(), 0);
				if (need == 0) {
					continue;
				}
				double chance = zone.chances().getOrDefault(type, 0.0);
				if (!(chance > 0)) {
					throw failure(helper, "the %s unit asks for %s, which the %s zone of its hazard does not hold", track, type, ORE_ZONE.get(track));
				}
				double perRun = run.ores() * chance / zone.oreChance();
				runs = Math.max(runs, need / perRun);
				LOGGER.info("[first-tier] {} unit: {} {} is {} runs of {} ({} {} a run)", track.id(), need, type, String.format("%.1f", need / perRun), ORE_ZONE.get(track),
						String.format("%.2f", perRun), type);
			}
			if (runs > MOST_RUNS_FOR_THE_ORE) {
				throw failure(helper, "the %s unit's ore takes %.1f runs of %s to haul, over the %d allowed", track, runs, ORE_ZONE.get(track), MOST_RUNS_FOR_THE_ORE);
			}
		}
		helper.succeed();
	}

	/**
	 * The crafting order from a fresh start: the kit, then each of the six first units in {@link #ORDER}, then the hazard answers' metal, drawing crate after crate
	 * from the real loot table (1,500 starts). Every start gets there, and the whole set takes a share of the yard's crates that leaves the rest of the world its
	 * salvage. The hazard units take copper and redstone, which the crates give beyond the iron the rest ask for, and ore.
	 */
	@GameTest(maxTicks = 200)
	public void aNewPilotCraftsTheKitAndThenEveryUnitFromOneYardsCrates(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		Needs total = BootstrapCost.kit();
		Spread kit = BootstrapCost.crates(level, total);
		LOGGER.info("[first-tier] the kit: {} iron, {} copper, {} redstone; crates {}", total.iron(), total.copper(), total.redstone(), kit);
		Spread last = kit;
		for (ComponentTrack track : ALL) {
			Needs unit = unitNeeds(track);
			total = total.plus(unit);
			Spread now = BootstrapCost.crates(level, total);
			LOGGER.info("[first-tier] + {} unit ({} iron, {} copper, {} redstone, ${} of ore): crates for the kit and the units so far {}; this unit adds {} on average",
					track.id(), unit.iron(), unit.copper(), unit.redstone(), unit.ore(), now, String.format("%.1f", now.mean() - last.mean()));
			last = now;
		}
		LOGGER.info("[first-tier] the kit and all {} units: {} iron, {} copper, {} redstone, ${} of ore; {} of the {} crates of a 12-chunk survey",
				ALL.size(), total.iron(), total.copper(), total.redstone(), total.ore(), last, YARD_CRATES);
		if (last.ninetieth() > MOST_CRATES_OF_ALL_UNITS) {
			throw failure(helper, "the kit and all the units take up to %d crates in 90 starts of 100, over the %d allowed", last.ninetieth(), MOST_CRATES_OF_ALL_UNITS);
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
	 * Each unit takes about the minutes its old price took to earn, at the pace of the run it was priced against; the ore in it counts at that pace, as the
	 * sales it forgoes. The first six also measure the opening, before and after. Before: a stock Mole runs layer 1 and its charter buys the six parts at their old
	 * prices as the money comes, and each run earns what the parts bought so far allow. After: the charter makes the six units from crates and fits them, and
	 * spends no money. Both are in minutes of play, the runs on one side and the crates, the furnace and the bench on the other (EarlyRunModel's (A)
	 * assumptions and BootstrapCost's).
	 */
	@GameTest(maxTicks = 200)
	public void craftingTheUnitsTakesAboutTheMinutesThatEarningTheirOldPricesTook(GameTestHelper helper) {
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

		// After: the crates and the furnace and the bench for each unit, and the ore of a hazard answer at its run's pace.
		Needs bag = BootstrapCost.kit();
		Spread before = BootstrapCost.crates(level, bag);
		double afterMinutes = 0;
		double inTheBag = 0;
		double oldMinutesOfAll = 0;
		List<String> outside = new ArrayList<>();
		for (ComponentTrack track : ALL) {
			Needs unit = unitNeeds(track);
			Pace pace = paceOf(track);
			// Alone, the crates that unit's own metal takes. In the bag the leftovers of the crates that came before it count too, so the set is cheaper than its parts.
			double metal = minutes(level, BootstrapCost.crates(level, unit).mean(), unit);
			double oreMinutes = pace.minutesFor(unit.ore());
			double minutes = metal + oreMinutes;
			double oldMinutes = pace.minutesFor(OLD_PRICE.get(track));
			LOGGER.info("[first-tier] {} unit: {} min ({} of crates, furnace and bench, {} of ${} ore); its old ${} took {} min of runs (share {})",
					track.id(), String.format("%.1f", minutes), String.format("%.1f", metal), String.format("%.1f", oreMinutes), unit.ore(), OLD_PRICE.get(track),
					String.format("%.1f", oldMinutes), String.format("%.2f", minutes / oldMinutes));
			if (minutes / oldMinutes < PART_SHARE_MIN || minutes / oldMinutes > PART_SHARE_MAX) {
				outside.add(String.format("the %s unit takes %.1f minutes, %.2f of the %.1f its old $%d took", track.id(), minutes, minutes / oldMinutes, oldMinutes,
						OLD_PRICE.get(track)));
			}
			if (ORDER.contains(track)) {
				bag = bag.plus(unit);
				Spread now = BootstrapCost.crates(level, bag);
				inTheBag += minutes(level, now.mean() - before.mean(), unit);
				before = now;
				afterMinutes += minutes;
				oldMinutesOfAll += oldMinutes;
			}
		}
		double beforeMinutes = beforeSeconds / 60;
		double kitMinutes = BootstrapCost.minutes(level, BootstrapCost.crates(level, BootstrapCost.kit()).mean(), BootstrapCost.kit(), BootstrapCost.kitParts().size());
		PodStats tierOne = EarlyRunModel.mole(1, 1, 1);
		Run tierOneRun = EarlyRunModel.run(zone, tierOne, 0);
		LOGGER.info("[first-tier] the kit takes {} min; the first full layer 1 run of a stock Mole: {} s, ${} net, {} slabs, {} L", String.format("%.1f", kitMinutes),
				String.format("%.0f", stockSeconds), Math.round(stockRun.net()), stockRun.slabs(), String.format("%.1f", stockRun.litres()));
		LOGGER.info("[first-tier] a Mole with tier 1 drill, tank and bay: {} s, ${} net, {} slabs", String.format("%.0f", EarlyRunModel.runSeconds(zone, tierOne, 0, tierOneRun) + RUN_OVERHEAD_SECONDS),
				Math.round(tierOneRun.net()), tierOneRun.slabs());
		LOGGER.info("[first-tier] before: {} layer 1 runs ({} min) and ${} spent to buy the first six; after: {} min of crates, furnace and bench ({} with the kit's leftovers) and $0 spent; the six old prices at the stock run's pace: {} min",
				beforeRuns, String.format("%.1f", beforeMinutes), spent, String.format("%.1f", afterMinutes), String.format("%.1f", inTheBag), String.format("%.1f", oldMinutesOfAll));
		double setShare = afterMinutes / beforeMinutes;
		LOGGER.info("[first-tier] the first six crafted take {} of the minutes the six bought took", String.format("%.2f", setShare));
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
