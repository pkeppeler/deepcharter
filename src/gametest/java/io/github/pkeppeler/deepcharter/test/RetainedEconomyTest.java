package io.github.pkeppeler.deepcharter.test;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;

import io.github.pkeppeler.deepcharter.creature.CreatureTuning;
import io.github.pkeppeler.deepcharter.pod.PodStats;
import io.github.pkeppeler.deepcharter.test.support.EarlyRunModel;
import io.github.pkeppeler.deepcharter.test.support.EarlyRunModel.Run;
import io.github.pkeppeler.deepcharter.test.support.EarlyRunModel.Zone;
import io.github.pkeppeler.deepcharter.upgrade.ComponentTrack;
import io.github.pkeppeler.deepcharter.upgrade.UpgradeTuning;

/**
 * Server GameTests for #449: what a Retained's grip costs a run of {@link EarlyRunModel}. A grab takes litres from the tank, so a run that
 * suffers one has a smaller tank to spend. No world is built; the numbers go in {@code docs/design/mechanics.md}.
 *
 * <p>(A) A pilot who notices the grip after {@link #REACTION_SECONDS} holds the rotor for the shake, which burns the moving rate in place of
 * the idle one for {@code shakeTicks}; a pilot who does nothing pays the whole fill.
 */
public class RetainedEconomyTest {
	private static final Logger LOGGER = LoggerFactory.getLogger(RetainedEconomyTest.class);
	private static final int TICKS_PER_SECOND = 20;
	/** (A) Seconds between the grab and the pilot holding the rotor: the action bar line, and a look at the hull. */
	private static final double REACTION_SECONDS = 2;
	/** A prompt shake costs at most this share of the net of a run of a stock Mole in layer 1: the careless pilot's price, small enough to shrug off. */
	private static final double SHAKE_SHARE_MAX = 0.15;
	/** A grip nobody answers costs at most this share: a real loss, and still a run that pays. */
	private static final double FILL_SHARE_MAX = 0.40;
	/** And at least this much, or a dark pod would not be a decision. */
	private static final double FILL_SHARE_MIN = 0.15;
	/** A lights part must pay for itself in this many unanswered grabs, at most: a part the dark pilot can believe in. */
	private static final double LIGHTS_PAYBACK_GRABS_MAX = 10;

	private static RuntimeException failure(GameTestHelper helper, String format, Object... args) {
		return helper.assertionException(Component.literal(String.format(format, args)));
	}

	/** Litres a grip takes before the pilot's shake frees the pod, with the extra burn of the rotor. */
	private static double shakenLitres(PodStats stats) {
		CreatureTuning.Cling cling = CreatureTuning.DEFAULT.cling();
		double shakeSeconds = (double) cling.shakeTicks() / TICKS_PER_SECOND;
		double drained = cling.drainLitres() * (REACTION_SECONDS + shakeSeconds);
		double rotorExtra = (stats.movingLitresPerSecond() - stats.idleLitresPerSecond()) * shakeSeconds;
		return drained + rotorExtra;
	}

	private static Run runWithLoss(Zone zone, PodStats stats, int shaft, double litresLost) {
		return EarlyRunModel.run(zone, stats.withTankLitres((float) (stats.tankLitres() - litresLost)), shaft);
	}

	@GameTest
	public void aGripCostsAStockMoleInLayerOneAShareOfARunAndALitPodCostsNothing(GameTestHelper helper) {
		PodStats stock = PodStats.base();
		Zone zone = Zone.load("topsoil_claims");
		Run whole = EarlyRunModel.run(zone, stock, 0);
		double fill = CreatureTuning.DEFAULT.cling().fillLitres();
		Run shaken = runWithLoss(zone, stock, 0, shakenLitres(stock));
		Run unanswered = runWithLoss(zone, stock, 0, fill);
		double shakenShare = 1 - shaken.net() / whole.net();
		double fillShare = 1 - unanswered.net() / whole.net();
		long lights = UpgradeTuning.DEFAULT.price(ComponentTrack.LIGHTS, 1);
		double fillLoss = whole.net() - unanswered.net();
		LOGGER.info("[retained-economy] stock Mole, layer 1: whole run {} slabs net ${}; shaken after {} s ({} L) {} slabs net ${} ({} lost); unanswered ({} L) {} slabs net ${} ({} lost); lights ${} = {} unanswered grabs",
				whole.slabs(), whole.net(), REACTION_SECONDS, shakenLitres(stock), shaken.slabs(), shaken.net(), shakenShare, fill, unanswered.slabs(), unanswered.net(), fillShare, lights, lights / fillLoss);
		if (shakenShare > SHAKE_SHARE_MAX) {
			throw failure(helper, "a grip shaken off after %.0f s costs %.0f%% of a layer 1 run, over the %.0f%% allowed", REACTION_SECONDS, 100 * shakenShare, 100 * SHAKE_SHARE_MAX);
		}
		if (fillShare > FILL_SHARE_MAX || fillShare < FILL_SHARE_MIN) {
			throw failure(helper, "an unanswered grip costs %.0f%% of a layer 1 run, outside %.0f%% to %.0f%%", 100 * fillShare, 100 * FILL_SHARE_MIN, 100 * FILL_SHARE_MAX);
		}
		if (unanswered.net() <= 0) {
			throw failure(helper, "a run that suffers a grip no longer pays: net $%.1f", unanswered.net());
		}
		if (lights / fillLoss > LIGHTS_PAYBACK_GRABS_MAX) {
			throw failure(helper, "a lights part of $%d pays back after %.1f unanswered grabs, over %.0f", lights, lights / fillLoss, LIGHTS_PAYBACK_GRABS_MAX);
		}
		helper.succeed();
	}

	@GameTest
	public void aGripIsNeverMoreThanAThirdOfAStockTank(GameTestHelper helper) {
		PodStats stock = PodStats.base();
		double fill = CreatureTuning.DEFAULT.cling().fillLitres();
		if (fill > stock.tankLitres() / 3) {
			throw failure(helper, "a grip takes %.2f litres of a %.0f litre stock tank", fill, stock.tankLitres());
		}
		helper.succeed();
	}

	@GameTest
	public void aGripTakesALessShareOfALargerTankAndTheLayerTwoRunStillPays(GameTestHelper helper) {
		PodStats upgraded = EarlyRunModel.mole(2, 2, 2);
		Zone zone = Zone.load("upper_levels");
		int shaft = EarlyRunModel.layerOneBlocks();
		Run whole = EarlyRunModel.run(zone, upgraded, shaft);
		double fill = CreatureTuning.DEFAULT.cling().fillLitres();
		Run unanswered = runWithLoss(zone, upgraded, shaft, fill);
		double share = 1 - unanswered.net() / whole.net();
		LOGGER.info("[retained-economy] Mole with tier 2 parts, layer 2: whole run {} slabs net ${}; unanswered ({} L of {} L) {} slabs net ${} ({} lost)",
				whole.slabs(), whole.net(), fill, upgraded.tankLitres(), unanswered.slabs(), unanswered.net(), share);
		double stockShare = 1 - runWithLoss(Zone.load("topsoil_claims"), PodStats.base(), 0, fill).net() / EarlyRunModel.run(Zone.load("topsoil_claims"), PodStats.base(), 0).net();
		if (share >= stockShare) {
			throw failure(helper, "a bigger tank loses %.0f%% to a grip, as much as a stock tank's %.0f%%", 100 * share, 100 * stockShare);
		}
		helper.succeed();
	}
}
