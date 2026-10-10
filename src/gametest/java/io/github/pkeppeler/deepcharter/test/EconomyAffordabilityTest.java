package io.github.pkeppeler.deepcharter.test;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;

import io.github.pkeppeler.deepcharter.hangar.HangarTuning;
import io.github.pkeppeler.deepcharter.market.WorkOrder;
import io.github.pkeppeler.deepcharter.ore.OreType;
import io.github.pkeppeler.deepcharter.pod.Chassis;
import io.github.pkeppeler.deepcharter.pod.HardLanding;
import io.github.pkeppeler.deepcharter.pod.PodLiningTuning;
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
	/** The spoil hopper is the entry to the lava ladder: a stock Mole buys it with one layer 1 run. */
	private static final int HOPPER_RUNS = 1;
	/** The liner's tiers, in runs of a Mole with tier 2 parts in layer 2, at most. */
	private static final int LINER_TIER_ONE_RUNS = 2;
	private static final int LINER_TIER_TWO_RUNS = 4;
	/** What a pack-fed liner's bricks may cost, as a share of what a stock Mole's layer 1 run nets (#363). */
	private static final double BRICK_BILL_SHARE = 0.5;
	/** Bays of spoil (a bay is 64, a dive brings home one) that the bricks of a pack-fed bore may take (#363). */
	private static final int BAY_TRIPS_PER_PACK_FED_BORE = 3;
	/** The seep sounder's tiers, in runs of a Mole with tier 2 parts in layer 2, at most. */
	private static final int SOUNDER_TIER_ONE_RUNS = 2;
	private static final int SOUNDER_TIER_TWO_RUNS = 4;
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
		return upgradedRunInLayerTwo(0f);
	}

	private static Run upgradedRunInLayerTwo(float extraMass) {
		return EarlyRunModel.run(Zone.load("upper_levels"), EarlyRunModel.mole(2, 2, 2), EarlyRunModel.layerOneBlocks(), extraMass);
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
			if (track == ComponentTrack.LINER) {
				continue; // A layer 2 buy with its own band: theLinerTiersAreAffordableAfterTheirLayerTwoRuns.
			}
			if (track == ComponentTrack.SOUNDER) {
				continue; // A layer 2 buy with its own band: theSounderTiersAreAffordableAfterTheirLayerTwoRuns.
			}
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
	 * The spoil hopper (#313) is the first lava counterplay and is bought before Deep Claim, so a stock Mole pays for it with one early layer 1
	 * run. It carries a full bay and rack, which cut lift (W2): the climb and the Prospector's layer 2 runs must come out as they do without it.
	 */
	@GameTest
	public void theSpoilHopperIsAffordableAfterOneLayerOneRunAndAFullBayDoesNotSlowTheClimb(GameTestHelper helper) {
		float mass = EarlyRunModel.hopperMass();
		PodStats stock = PodStats.base();
		Run run = EarlyRunModel.run(Zone.load("topsoil_claims"), stock, 0, mass);
		long price = UpgradeTuning.DEFAULT.price(ComponentTrack.SPOIL_HOPPER, 1);
		LOGGER.info("[economy] spoil hopper ${}: {} layer 1 runs of {}; a full bay and rack weigh {} of {} engine power", price, run.toAfford(price), run, mass, stock.enginePower());
		if (run.toAfford(price) != HOPPER_RUNS) {
			throw failure(helper, "the spoil hopper costs $%d, which is %d layer 1 runs of $%.0f with a full bay; %d expected", price, run.toAfford(price), run.net(), HOPPER_RUNS);
		}
		if (EarlyRunModel.climbSpeed(stock, mass) != stock.maxClimbSpeed()) {
			throw failure(helper, "a full bay and rack of mass %.1f slow the climb to %.3f from the rotor's %.3f", mass, EarlyRunModel.climbSpeed(stock, mass), stock.maxClimbSpeed());
		}
		int restoreRuns = upgradedRunInLayerTwo(mass).toAfford(HangarTuning.DEFAULT.restoreCost(Chassis.PROSPECTOR).money());
		if (restoreRuns != PROSPECTOR_RUNS) {
			throw failure(helper, "with a full bay and rack the Prospector restore takes %d layer 2 runs, expected %d", restoreRuns, PROSPECTOR_RUNS);
		}
		helper.succeed();
	}

	/**
	 * The liner (#339) is the lava ladder's second rung and is bought in layer 2: a run there pays for tier 1 in at most
	 * {@value #LINER_TIER_ONE_RUNS} runs and for tier 2 in at most {@value #LINER_TIER_TWO_RUNS}. The run is a hopper pod's (the liner lines from
	 * its rack, which the hopper fills) with the liner's drill penalty, so a dearer liner is a slower income.
	 */
	@GameTest
	public void theLinerTiersAreAffordableAfterTheirLayerTwoRuns(GameTestHelper helper) {
		int[] allowed = {LINER_TIER_ONE_RUNS, LINER_TIER_TWO_RUNS};
		for (int tier = 1; tier <= ComponentTrack.LINER.maxTier(); tier++) {
			Run run = EarlyRunModel.run(Zone.load("upper_levels"), EarlyRunModel.withLiner(EarlyRunModel.mole(2, 2, 2), tier), EarlyRunModel.layerOneBlocks(), EarlyRunModel.hopperMass());
			long price = UpgradeTuning.DEFAULT.price(ComponentTrack.LINER, tier);
			LOGGER.info("[economy] liner tier {} ${}: {} layer 2 runs of {}", tier, price, run.toAfford(price), run);
			if (run.toAfford(price) > allowed[tier - 1]) {
				throw failure(helper, "liner tier %d costs $%d, which is %d layer 2 runs of $%.0f; at most %d are allowed", tier, price, run.toAfford(price), run.net(), allowed[tier - 1]);
			}
		}
		if (allowed.length != ComponentTrack.LINER.maxTier()) {
			throw failure(helper, "the test has bands for %d liner tiers, the track has %d", allowed.length, ComponentTrack.LINER.maxTier());
		}
		helper.succeed();
	}

	/**
	 * A liner that draws on a stack in the pilot's pack lays {@value EarlyRunModel#PACK_FED_BRICKS} bricks a bore (#363), and a bore that reaches layer 2 must pay
	 * for them: the run nets more than an unlined layer 1 run once the bricks are paid, and the bill is at most {@value #BRICK_BILL_SHARE} of that layer 1 run.
	 */
	@GameTest
	public void aPackFedLinedRunToLayerTwoNetsMoreThanAnUnlinedLayerOneRun(GameTestHelper helper) {
		Run layerOne = stockRunInLayerOne();
		Run lined = EarlyRunModel.run(Zone.load("upper_levels"), EarlyRunModel.withLiner(EarlyRunModel.mole(2, 2, 2), 2), EarlyRunModel.layerOneBlocks(), EarlyRunModel.hopperMass());
		long bill = EarlyRunModel.brickBill(EarlyRunModel.PACK_FED_BRICKS);
		double net = EarlyRunModel.netOfBricks(lined, EarlyRunModel.PACK_FED_BRICKS);
		LOGGER.info("[economy] lined layer 2 run {} less ${} of bricks nets ${}; an unlined layer 1 run nets ${}", lined, bill, Math.round(net), Math.round(layerOne.net()));
		if (net <= layerOne.net()) {
			throw failure(helper, "a lined run to layer 2 nets $%.0f after $%d of bricks, no more than the $%.0f of an unlined layer 1 run", net, bill, layerOne.net());
		}
		if (bill > BRICK_BILL_SHARE * layerOne.net()) {
			throw failure(helper, "%d bricks cost $%d, over %.0f%% of the $%.0f that a layer 1 run nets", EarlyRunModel.PACK_FED_BRICKS, bill, BRICK_BILL_SHARE * 100, layerOne.net());
		}
		helper.succeed();
	}

	/**
	 * Slag brick costs spoil and no money (#363): the spoil hopper's own output lines for free. The spoil is the limit, one bay a dive, so a
	 * pack-fed bore takes at most {@value #BAY_TRIPS_PER_PACK_FED_BORE} bays of it.
	 */
	@GameTest
	public void slagBrickCostsSpoilAndNoMoneyAndAPackFedBoreTakesThreeBaysOfIt(GameTestHelper helper) {
		if (PodLiningTuning.DEFAULT.fusePrice() != 0) {
			throw failure(helper, "slag brick costs $%d each, expected it to be free", PodLiningTuning.DEFAULT.fusePrice());
		}
		int trips = EarlyRunModel.baySpoilTrips(EarlyRunModel.PACK_FED_BRICKS);
		if (trips > BAY_TRIPS_PER_PACK_FED_BORE) {
			throw failure(helper, "%d bricks take %d bays of spoil, at most %d are allowed", EarlyRunModel.PACK_FED_BRICKS, trips, BAY_TRIPS_PER_PACK_FED_BORE);
		}
		helper.succeed();
	}

	/**
	 * The seep sounder (#373) is the gas ladder's second rung and is bought in layer 2: a run there pays for tier 1 in at most
	 * {@value #SOUNDER_TIER_ONE_RUNS} runs and for tier 2 in at most {@value #SOUNDER_TIER_TWO_RUNS}. The run is a Mole's with tier 2 parts and the sounder's
	 * drill penalty, so a dearer sounder is a slower income.
	 */
	@GameTest
	public void theSounderTiersAreAffordableAfterTheirLayerTwoRuns(GameTestHelper helper) {
		int[] allowed = {SOUNDER_TIER_ONE_RUNS, SOUNDER_TIER_TWO_RUNS};
		for (int tier = 1; tier <= ComponentTrack.SOUNDER.maxTier(); tier++) {
			Run run = EarlyRunModel.run(Zone.load("upper_levels"), EarlyRunModel.withSounder(EarlyRunModel.mole(2, 2, 2), tier), EarlyRunModel.layerOneBlocks());
			long price = UpgradeTuning.DEFAULT.price(ComponentTrack.SOUNDER, tier);
			LOGGER.info("[economy] sounder tier {} ${}: {} layer 2 runs of {}", tier, price, run.toAfford(price), run);
			if (run.toAfford(price) > allowed[tier - 1]) {
				throw failure(helper, "sounder tier %d costs $%d, which is %d layer 2 runs of $%.0f; at most %d are allowed", tier, price, run.toAfford(price), run.net(), allowed[tier - 1]);
			}
		}
		if (allowed.length != ComponentTrack.SOUNDER.maxTier()) {
			throw failure(helper, "the test has bands for %d sounder tiers, the track has %d", allowed.length, ComponentTrack.SOUNDER.maxTier());
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
	 * Tank refills that a one-way bore of all of layer 1 takes, for a Mole whose tank is of tier 0 to 6, in the deepest zone (it
	 * changes drill time per slab through the ore's hardness, so it is the dearest). Literals: a change to the tank ladder, the burn
	 * rates, the drill or layer 1's height moves them.
	 */
	private static final int[] BORE_TANKS_BY_TANK_TIER = {14, 10, 6, 4, 3, 2, 1};
	private static final int BORE_LITRES = 139;
	/** Litres (in tenths) of a braked drive down layer 1's 192 blocks at 0.6 blocks per tick: 16 s, the rotor on half of it. */
	private static final int DRIVE_DOWN_DECILITRES = 20;
	/** The terminal sink speed of a pod in blocks per tick: gravity 0.08 times drag 0.98 over the 0.02 the drag removes. */
	private static final double FREE_FALL_TERMINAL_SINK = 3.92;
	private static final int PROSPECTOR_RUNS = 4;

	@GameTest
	public void aOneWayBoreOfLayerOneTakesTheTanksThatTheLadderGives(GameTestHelper helper) {
		Zone zone = Zone.load("deep_claim");
		double litres = EarlyRunModel.boreLitres(zone, PodStats.base(), EarlyRunModel.layerOneBlocks());
		LOGGER.info("[fuel] one-way bore of layer 1: {} L", litres);
		if (BORE_TANKS_BY_TANK_TIER.length != ComponentTrack.FUEL_TANK.maxTier() + 1) {
			throw failure(helper, "the test pins %d tank tiers, the track has %d", BORE_TANKS_BY_TANK_TIER.length, ComponentTrack.FUEL_TANK.maxTier() + 1);
		}
		if (Math.round(litres) != BORE_LITRES) {
			throw failure(helper, "a one-way bore of layer 1 burns %.1f L, expected %d", litres, BORE_LITRES);
		}
		for (int tier = 0; tier < BORE_TANKS_BY_TANK_TIER.length; tier++) {
			int tanks = (int) Math.ceil(litres / EarlyRunModel.mole(0, tier, 0).tankLitres());
			LOGGER.info("[fuel] tank tier {}: {} tanks", tier, tanks);
			if (tanks != BORE_TANKS_BY_TANK_TIER[tier]) {
				throw failure(helper, "a tier %d tank needs %d refills for the bore, expected %d", tier, tanks, BORE_TANKS_BY_TANK_TIER[tier]);
			}
		}
		helper.succeed();
	}

	@GameTest
	public void aStockMoleDrivesDownItsOwnShaftBrakedButCannotFallDownIt(GameTestHelper helper) {
		PodStats stock = PodStats.base();
		double litres = EarlyRunModel.driveDownLitres(stock, EarlyRunModel.layerOneBlocks());
		LOGGER.info("[fuel] a braked drive down layer 1's shaft burns {} L of a {} L tank", litres, stock.tankLitres());
		if (Math.round(litres * 10) != DRIVE_DOWN_DECILITRES) {
			throw failure(helper, "a braked drive down the shaft burns %.2f L, expected %.1f", litres, DRIVE_DOWN_DECILITRES / 10.0);
		}
		if (EarlyRunModel.DRIVE_DOWN_SINK > stock.hardLandingSpeed()
				|| HardLanding.hullDamage(stock, FREE_FALL_TERMINAL_SINK, 1f) < stock.maxHull()) {
			throw failure(helper, "a braked drive at %s should land softly and a free fall at %s should wreck a stock hull",
					EarlyRunModel.DRIVE_DOWN_SINK, FREE_FALL_TERMINAL_SINK);
		}
		helper.succeed();
	}

	/** The layer 2 runs assume a pod that starts at the bottom of layer 1's shaft; the Prospector takes this many of them. */
	@GameTest
	public void theProspectorRestoreTakesTheLayerTwoRunsThePlanSays(GameTestHelper helper) {
		int runs = upgradedRunInLayerTwo().toAfford(HangarTuning.DEFAULT.restoreCost(Chassis.PROSPECTOR).money());
		if (runs != PROSPECTOR_RUNS) {
			throw failure(helper, "the Prospector restore takes %d layer 2 runs, expected %d", runs, PROSPECTOR_RUNS);
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
