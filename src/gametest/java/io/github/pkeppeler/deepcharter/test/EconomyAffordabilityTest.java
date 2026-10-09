package io.github.pkeppeler.deepcharter.test;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;

import io.github.pkeppeler.deepcharter.fuel.FuelTuning;
import io.github.pkeppeler.deepcharter.hangar.HangarTuning;
import io.github.pkeppeler.deepcharter.market.WorkOrder;
import io.github.pkeppeler.deepcharter.ore.OreType;
import io.github.pkeppeler.deepcharter.pod.Chassis;
import io.github.pkeppeler.deepcharter.pod.PodStats;
import io.github.pkeppeler.deepcharter.repair.Consumable;
import io.github.pkeppeler.deepcharter.repair.RepairTuning;
import io.github.pkeppeler.deepcharter.scanner.ScannerTuning;
import io.github.pkeppeler.deepcharter.test.support.EarlyRunModel;
import io.github.pkeppeler.deepcharter.test.support.EarlyRunModel.Run;
import io.github.pkeppeler.deepcharter.test.support.EarlyRunModel.Zone;
import io.github.pkeppeler.deepcharter.upgrade.ComponentTrack;
import io.github.pkeppeler.deepcharter.upgrade.UpgradeTuning;

/**
 * Server GameTests for #201: the early purchases cost a number of early runs that a new charter will accept. The income comes
 * from {@link EarlyRunModel}, which works it out from the ore tables and the pod's stats. No world is built.
 *
 * <p>Each purchase is counted from an empty account, with no purchase before it. The bounds are the issue's: a tier 1 part after
 * one or two early runs, the Prospector restore near the end of onboarding.
 */
public class EconomyAffordabilityTest {
	private static final Logger LOGGER = LoggerFactory.getLogger(EconomyAffordabilityTest.class);

	/** A tier 1 part, the scanner and the lights are an early buy: this many runs of a stock Mole in layer 1, at most. */
	private static final int EARLY_BUY_RUNS = 2;
	/** The radiator matters from layer 3 on, and its tier 1 costs what the others' tier 2 does in the original. */
	private static final int RADIATOR_RUNS = 5;
	/** The Prospector restore takes this many runs of a Mole with tier 2 parts in layer 2, at least and at most. */
	private static final int RESTORE_RUNS_MIN = 3;
	private static final int RESTORE_RUNS_MAX = 6;
	/**
	 * Cicatrium sells for $1,500. What a run finds of it in the deepest zone is at most this share of the run's other income (it is
	 * about a fifth today). Half is room for a retune of the ore and still a stop for one that makes selling it the main income.
	 */
	private static final double CATALYST_SALE_SHARE = 0.5;
	/** A later charter's first refurbished Mole, with no pod yet, and its second one. */
	private static final int REFURBISHED_FIRST_RUNS = 2;
	private static final int REFURBISHED_SECOND_RUNS = 3;

	/**
	 * Each repair station item against the run of the layer where it starts to matter (the transmitter, a layer 3 item, is measured in
	 * layer 2 runs), as a band of that run's net. See {@link Consumable} for the scale.
	 */
	private record Target(boolean layerTwo, double minRuns, double maxRuns) {
	}

	private static final Map<Consumable, Target> ITEM_TARGETS = Map.of(
			Consumable.RESERVE_FUEL_TANK, new Target(false, 0.25, 1),
			Consumable.DYNAMITE, new Target(false, 0.25, 1),
			Consumable.QUANTUM_TELEPORTER, new Target(true, 1.5, 2),
			Consumable.PLASTIC_EXPLOSIVES, new Target(true, 0.25, 1),
			Consumable.HULL_NANOBOTS, new Target(true, 0.25, 1),
			Consumable.MATTER_TRANSMITTER, new Target(true, 3.5, 4));

	private static Run stockRunInLayerOne() {
		return EarlyRunModel.run(Zone.load("topsoil_claims"), PodStats.base(), 0);
	}

	private static Run upgradedRunInLayerTwo() {
		return EarlyRunModel.run(Zone.load("upper_levels"), EarlyRunModel.mole(2, 2, 2), EarlyRunModel.layerOneBlocks());
	}

	// assertionException(String, Object...) leaves the placeholders unfilled in the report.
	private static RuntimeException failure(GameTestHelper helper, String format, Object... args) {
		return helper.assertionException(Component.literal(String.format(format, args)));
	}

	@GameTest
	public void earlyPartsScannerAndLightsAreAffordableAfterOneOrTwoRuns(GameTestHelper helper) {
		Run run = stockRunInLayerOne();
		LOGGER.info("[economy] stock Mole, layer 1 topsoil: {}", run);
		for (ComponentTrack track : ComponentTrack.values()) {
			long price = UpgradeTuning.DEFAULT.price(track, 1);
			int runs = run.toAfford(price);
			LOGGER.info("[economy] tier 1 {} ${}: {} runs", track.id(), price, runs);
			int allowed = track == ComponentTrack.RADIATOR ? RADIATOR_RUNS : EARLY_BUY_RUNS;
			if (runs > allowed) {
				throw failure(helper, "tier 1 %s costs $%d, which is %d runs of $%.0f; at most %d are allowed",
						track.id(), price, runs, run.net(), allowed);
			}
		}
		helper.succeed();
	}

	/**
	 * The thermal tier (#300) is the scanner's tier 2, which the standard ladder prices with the other tier 2 parts. A tier 2 part is a
	 * layer 2 buy: a run there, with tier 2 parts, pays for it in two runs at most.
	 */
	@GameTest
	public void theThermalScannerTierIsAffordableAfterOneOrTwoLayerTwoRuns(GameTestHelper helper) {
		Run run = upgradedRunInLayerTwo();
		int tier = ScannerTuning.DEFAULT.lavaTier();
		long price = UpgradeTuning.DEFAULT.price(ComponentTrack.SCANNER, tier);
		int runs = run.toAfford(price);
		LOGGER.info("[economy] thermal scanner, tier {} ${}: {} layer 2 runs of {}", tier, price, runs, run);
		if (runs > EARLY_BUY_RUNS) {
			throw failure(helper, "the thermal scanner (tier %d) costs $%d, which is %d layer 2 runs of $%.0f; at most %d are allowed",
					tier, price, runs, run.net(), EARLY_BUY_RUNS);
		}
		helper.succeed();
	}

	/**
	 * Trips to bore layer 1 from the top to the breach, for a Mole whose tank is of tier 0 to 6, in the deepest zone (the fuel burn does
	 * not differ between the zones today). Literals: a change to the tank ladder, the burn rates or layer 1's height moves them.
	 */
	private static final int[] DESCENT_TRIPS_BY_TANK_TIER = {28, 14, 7, 4, 3, 2, 1};
	/** With the best tank a Mole takes (SPEC section 7), the way to layer 2 is this many round trips from the pump, at most. */
	private static final int DESCENT_TRIPS_MAX = 8;

	@GameTest
	public void theWayDownLayerOneTakesTheTripsThatTheTankLadderGives(GameTestHelper helper) {
		Zone zone = Zone.load("deep_claim");
		for (int tier = 0; tier <= ComponentTrack.FUEL_TANK.maxTier(); tier++) {
			EarlyRunModel.Descent descent = EarlyRunModel.descent(zone, EarlyRunModel.mole(0, tier, 0));
			LOGGER.info("[fuel] tank tier {}: {}", tier, descent);
			if (descent.trips() != DESCENT_TRIPS_BY_TANK_TIER[tier]) {
				throw failure(helper, "a tier %d tank bores layer 1 in %d trips, expected %d", tier, descent.trips(), DESCENT_TRIPS_BY_TANK_TIER[tier]);
			}
		}
		helper.succeed();
	}

	@GameTest
	public void aMoleWithItsBestTankReachesLayerTwoInFewTrips(GameTestHelper helper) {
		int tier = UpgradeTuning.DEFAULT.tierCap(Chassis.MOLE.id());
		EarlyRunModel.Descent descent = EarlyRunModel.descent(Zone.load("deep_claim"), EarlyRunModel.mole(0, tier, 0));
		double bill = descent.litres() * FuelTuning.DEFAULT.pricePerLitre();
		double layerTwoRun = upgradedRunInLayerTwo().net();
		LOGGER.info("[fuel] Mole with a tier {} tank, down layer 1: {} trips, ${} of fuel, against a layer 2 run of ${}", tier, descent.trips(),
				Math.round(bill), Math.round(layerTwoRun));
		if (descent.trips() > DESCENT_TRIPS_MAX) {
			throw failure(helper, "a tier %d tank needs %d trips to bore layer 1; at most %d are allowed", tier, descent.trips(), DESCENT_TRIPS_MAX);
		}
		if (bill > layerTwoRun) {
			throw failure(helper, "the fuel for the way down layer 1 costs $%.0f, more than the $%.0f of a layer 2 run", bill, layerTwoRun);
		}
		helper.succeed();
	}

	@GameTest
	public void everyTierCostsMoreThanTheOneBelow(GameTestHelper helper) {
		for (ComponentTrack track : ComponentTrack.values()) {
			for (int tier = 2; tier <= track.maxTier(); tier++) {
				long below = UpgradeTuning.DEFAULT.price(track, tier - 1);
				long price = UpgradeTuning.DEFAULT.price(track, tier);
				if (price < 2 * below) {
					throw failure(helper, "%s tier %d costs $%d, under twice the $%d of tier %d", track.id(), tier, price, below, tier - 1);
				}
			}
		}
		helper.succeed();
	}

	@GameTest
	public void theProspectorRestoreLandsNearTheEndOfOnboarding(GameTestHelper helper) {
		Run run = upgradedRunInLayerTwo();
		long money = HangarTuning.DEFAULT.restoreCost(Chassis.PROSPECTOR).money();
		int runs = run.toAfford(money);
		LOGGER.info("[economy] Mole with tier 2 drill, tank and bay, layer 2 upper levels: {}; Prospector restore ${}: {} runs", run, money, runs);
		if (runs < RESTORE_RUNS_MIN || runs > RESTORE_RUNS_MAX) {
			throw failure(helper, "the Prospector restore costs $%d, which is %d runs of $%.0f; %d to %d are allowed",
					money, runs, run.net(), RESTORE_RUNS_MIN, RESTORE_RUNS_MAX);
		}
		helper.succeed();
	}

	/** The invariant of issue 209: the Company's advance covers the restore's Cicatrium, so the ore never gates the Prospector. */
	@GameTest
	public void theCompanyAdvanceIsAtLeastTheCicatriumTheProspectorRestoreTakes(GameTestHelper helper) {
		HangarTuning.RestoreCost cost = HangarTuning.DEFAULT.restoreCost(Chassis.PROSPECTOR);
		Run run = EarlyRunModel.run(Zone.load("prospectors_run"), EarlyRunModel.mole(2, 2, 2), EarlyRunModel.layerOneBlocks());
		LOGGER.info("[economy] Prospector restore: {} Cicatrium, {} advanced; from ore alone, at {} per run in the deepest zone, it would take {} runs",
				cost.catalysts(), cost.advance(), run.catalysts(), Math.ceil(cost.catalysts() / run.catalysts()));
		if (cost.advance() < cost.catalysts()) {
			throw failure(helper, "the Prospector restore takes %d Cicatrium but the Company advances only %d, so the ore gates it again",
					cost.catalysts(), cost.advance());
		}
		helper.succeed();
	}

	@GameTest
	public void cicatriumFoundInTheOreIsNotAnIncomeStream(GameTestHelper helper) {
		Zone deepest = Zone.load("prospectors_run");
		Run run = EarlyRunModel.run(deepest, EarlyRunModel.mole(2, 2, 2), EarlyRunModel.layerOneBlocks());
		double sold = run.catalysts() * OreType.CICATRIUM.value();
		LOGGER.info("[economy] a layer 2 run in prospectors_run: {} Cicatrium, ${} if sold, against ${} of other ore", run.catalysts(),
				Math.round(sold), Math.round(run.net()));
		if (sold > CATALYST_SALE_SHARE * run.net()) {
			throw failure(helper, "Cicatrium sells for $%d, so a run's %.3f of it is $%.0f, over %.0f%% of the $%.0f that its other ore nets",
					OreType.CICATRIUM.value(), run.catalysts(), sold, CATALYST_SALE_SHARE * 100, run.net());
		}
		helper.succeed();
	}

	@GameTest
	public void aRefurbishedMoleIsModestForALaterCharter(GameTestHelper helper) {
		Run run = stockRunInLayerOne();
		long first = HangarTuning.DEFAULT.refurbishedPrice(0);
		long second = HangarTuning.DEFAULT.refurbishedPrice(1);
		int firstRuns = run.toAfford(first);
		int secondRuns = run.toAfford(second);
		LOGGER.info("[economy] refurbished Mole: ${} for the first pod ({} runs), ${} for the second ({} runs)", first, firstRuns,
				second, secondRuns);
		if (firstRuns > REFURBISHED_FIRST_RUNS) {
			throw failure(helper, "a first refurbished Mole costs $%d, which is %d runs of $%.0f; at most %d are allowed",
					first, firstRuns, run.net(), REFURBISHED_FIRST_RUNS);
		}
		if (secondRuns > REFURBISHED_SECOND_RUNS) {
			throw failure(helper, "a second refurbished Mole costs $%d, which is %d runs of $%.0f; at most %d are allowed",
					second, secondRuns, run.net(), REFURBISHED_SECOND_RUNS);
		}
		helper.succeed();
	}

	@GameTest
	public void mendingAStockHullCostsNoMoreThanOneRun(GameTestHelper helper) {
		Run run = stockRunInLayerOne();
		long full = Math.round(PodStats.base().maxHull()) * RepairTuning.DEFAULT.repairCostPerHp();
		LOGGER.info("[economy] full repair of the stock hull: ${} against a run of ${}", full, Math.round(run.net()));
		if (full > run.net()) {
			throw failure(helper, "a full repair of the stock hull costs $%d, more than the $%.0f of a run", full, run.net());
		}
		helper.succeed();
	}

	@GameTest
	public void theFoundersHandsRewardIsWhatTheTenBronziumFetch(GameTestHelper helper) {
		WorkOrder order = WorkOrder.FOUNDERS_HANDS;
		long sold = (long) order.quantity() * OreType.BRONZIUM.value();
		if (order.reward() != sold) {
			throw failure(helper, "the order pays $%d, but its %d bronzium sell for $%d", order.reward(), order.quantity(), sold);
		}
		helper.succeed();
	}

	@GameTest
	public void everyRepairItemIsAffordableForTheLayerItMattersIn(GameTestHelper helper) {
		if (ITEM_TARGETS.size() != Consumable.values().length) {
			throw failure(helper, "the test has targets for %d items, the shop sells %d", ITEM_TARGETS.size(), Consumable.values().length);
		}
		Run layerOne = stockRunInLayerOne();
		Run layerTwo = upgradedRunInLayerTwo();
		for (Consumable item : Consumable.values()) {
			Target target = ITEM_TARGETS.get(item);
			Run run = target.layerTwo() ? layerTwo : layerOne;
			double runs = item.price() / run.net();
			LOGGER.info("[economy] {} ${}: {} runs of ${}", item, item.price(), runs, Math.round(run.net()));
			if (runs > target.maxRuns() || runs < target.minRuns()) {
				throw failure(helper, "%s costs $%d, which is %.2f runs of $%.0f; %.2f to %.2f are allowed", item, item.price(), runs,
						run.net(), target.minRuns(), target.maxRuns());
			}
		}
		helper.succeed();
	}

	@GameTest
	public void theDearerItemOfEachPairCostsMore(GameTestHelper helper) {
		if (Consumable.DYNAMITE.price() >= Consumable.PLASTIC_EXPLOSIVES.price()) {
			throw failure(helper, "the plastic explosives outdo the dynamite, so they must cost more than its $%d, not $%d",
					Consumable.DYNAMITE.price(), Consumable.PLASTIC_EXPLOSIVES.price());
		}
		if (Consumable.QUANTUM_TELEPORTER.price() >= Consumable.MATTER_TRANSMITTER.price()) {
			throw failure(helper, "the transmitter must cost more than the teleporter's $%d, not $%d",
					Consumable.QUANTUM_TELEPORTER.price(), Consumable.MATTER_TRANSMITTER.price());
		}
		helper.succeed();
	}

	@GameTest
	public void theNanobotsCostMoreThanMendingTheSameHullAtTheStation(GameTestHelper helper) {
		long atStation = Math.round(RepairTuning.DEFAULT.nanobotHp()) * RepairTuning.DEFAULT.repairCostPerHp();
		if (Consumable.HULL_NANOBOTS.price() <= atStation) {
			throw failure(helper, "the nanobots cost $%d, no more than the $%d that the station charges for their %.0f HP",
					Consumable.HULL_NANOBOTS.price(), atStation, RepairTuning.DEFAULT.nanobotHp());
		}
		helper.succeed();
	}
}
