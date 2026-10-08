package io.github.pkeppeler.deepcharter.test;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;

import io.github.pkeppeler.deepcharter.hangar.HangarTuning;
import io.github.pkeppeler.deepcharter.market.WorkOrder;
import io.github.pkeppeler.deepcharter.ore.OreType;
import io.github.pkeppeler.deepcharter.pod.Chassis;
import io.github.pkeppeler.deepcharter.pod.PodStats;
import io.github.pkeppeler.deepcharter.repair.RepairTuning;
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
	/** A later charter's first refurbished Mole, with no pod yet, and its second one. */
	private static final int REFURBISHED_FIRST_RUNS = 2;
	private static final int REFURBISHED_SECOND_RUNS = 3;

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
}
