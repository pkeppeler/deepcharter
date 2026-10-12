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
 * Server GameTests for #449 and #485: what a Retained's grip costs a run of {@link EarlyRunModel}. A grab takes litres from the tank until
 * the figure dies, so a run that suffers one has a smaller tank to spend. No world is built; the numbers go in {@code docs/design/mechanics.md}.
 *
 * <p>(A) A pilot who drills after a reaction time loses the drain over the reaction and over the time the cutter takes to kill the figure;
 * the burn of the drill itself is not counted, because the pilot would drill anyway. A pilot who does nothing loses the tank down to the floor.
 */
public class RetainedEconomyTest {
	private static final Logger LOGGER = LoggerFactory.getLogger(RetainedEconomyTest.class);
	private static final int TICKS_PER_SECOND = 20;
	/** (A) Seconds between the grab and the pilot drilling, for a pilot who read the approach cue and watches the pod: the figure is in view. */
	private static final double CUED_REACTION_SECONDS = 0.5;
	/** (A) Seconds for a pilot who did not see it come: the action bar line at the grab, and a look at the hull. */
	private static final double UNCUED_REACTION_SECONDS = 2;
	/** A first-hour pilot who reacts to the cue loses at most this share of the litres a layer 1 run burns to a grab. */
	private static final double CUED_SHARE_MAX = 0.05;
	/** One who did not see it come loses at most this share. */
	private static final double UNCUED_SHARE_MAX = 0.10;
	/** A grip nobody answers costs at least this share of a layer 1 run, or nobody would need to answer it. */
	private static final double UNANSWERED_SHARE_MIN = 0.9;
	/** A lights part must pay for itself in this many unanswered grabs, at most: a part the dark pilot can believe in. */
	private static final double LIGHTS_PAYBACK_GRABS_MAX = 10;

	/**
	 * Measured in #449, pinned: the game's spawner placed 6 figures in 12,000 ticks (10 minutes) of one player in layer 2 at a roll of 0.02, so
	 * 300 tries in 10 minutes passed every rule but the roll, which is 30 a minute for each unit of chance. It depends on the spawner's tries
	 * reaching a dark floor with room, 24 or more blocks from the player, and not on the cap while the level is under it; it does not move
	 * when the roll or the cap changes. The cap and the delay after a kill are applied by {@link #placedPerRun}, from the tuning.
	 */
	private static final double PLACED_PER_MINUTE_PER_CHANCE = 30;
	/** (A) Minutes of a first-hour run in layer 1, down and back up; and of a run in layer 2, which is as long. */
	private static final double RUN_MINUTES = 4;
	/** (A) Share of placed figures that find the pod: they appear 24 or more blocks off, sense 20, and the pod moves. */
	private static final double MEET_SHARE = 0.5;
	/** The expected loss to figures over a layer 1 run stays under this share of the run, so that a first-hour pilot who answers each is never worn down. */
	private static final double LAYER_ONE_EXPECTED_SHARE_MAX = 0.05;
	/** A deep run meets at least this many figures: a presence and not a rare sight (#506). */
	private static final double LAYER_TWO_GRABS_MIN = 1;
	/** A deep run meets at most this many figures on average, or the Retained are the whole run and not a hazard in it. */
	private static final double LAYER_TWO_GRABS_MAX = 5;

	private static RuntimeException failure(GameTestHelper helper, String format, Object... args) {
		return helper.assertionException(Component.literal(String.format(format, args)));
	}

	/** Seconds the cutter takes to kill a figure: a bite as the drill starts and one each {@code biteTicks}, until the health is gone. */
	private static double killSeconds(CreatureTuning tuning) {
		int bites = (int) Math.ceil(tuning.maxHealth() / tuning.cling().biteDamage());
		return (double) (bites - 1) * tuning.cling().biteTicks() / TICKS_PER_SECOND;
	}

	/** Litres a grip takes before the pilot's drilling kills the figure: the drain while the pilot reacts and while the cutter works. */
	private static double killedLitres(CreatureTuning tuning, double reactionSeconds) {
		return tuning.cling().drainLitres() * (reactionSeconds + killSeconds(tuning));
	}

	/** Litres a grip nobody answers takes: the tank down to the floor. */
	private static double unansweredLitres(PodStats stats, CreatureTuning tuning) {
		return stats.tankLitres() * (1 - tuning.cling().floorPercent() / 100.0);
	}

	private static Run runWithLoss(Zone zone, PodStats stats, int shaft, double litresLost) {
		return EarlyRunModel.run(zone, stats.withTankLitres((float) (stats.tankLitres() - litresLost)), shaft);
	}

	/** The pick (docs/design/mechanics.md), measured the way the candidates of #449 were; the bands pin it. */
	@GameTest
	public void aGripCostsAStockMoleInLayerOneAShareOfARunAndALitPodCostsNothing(GameTestHelper helper) {
		PodStats stock = PodStats.base();
		Zone zone = Zone.load("topsoil_claims");
		Run whole = EarlyRunModel.run(zone, stock, 0);
		CreatureTuning tuning = CreatureTuning.DEFAULT;
		LOGGER.info("[retained-economy] stock Mole, layer 1: whole run {} slabs net ${}, {} L; the cutter kills a figure in {} s", whole.slabs(), whole.net(), whole.litres(), killSeconds(tuning));
		// The model counts whole slabs, so its net moves in steps of a slab (6%): the share of a run is the share of the litres the run burns.
		double cuedLitres = killedLitres(tuning, CUED_REACTION_SECONDS);
		double uncuedLitres = killedLitres(tuning, UNCUED_REACTION_SECONDS);
		double cuedShare = cuedLitres / whole.litres();
		double uncuedShare = uncuedLitres / whole.litres();
		double unansweredLitres = unansweredLitres(stock, tuning);
		Run unanswered = runWithLoss(zone, stock, 0, unansweredLitres);
		double unansweredShare = unansweredLitres / whole.litres();
		long lights = UpgradeTuning.DEFAULT.price(ComponentTrack.LIGHTS, 1);
		double unansweredLoss = whole.net() - Math.max(0, unanswered.net());
		LOGGER.info("[retained-economy] the pick: cued {} L ({} of a run), uncued {} L ({}), unanswered {} L ({}): {} slabs net ${}; lights ${} = {} unanswered grabs",
				cuedLitres, cuedShare, uncuedLitres, uncuedShare, unansweredLitres, unansweredShare, unanswered.slabs(), unanswered.net(), lights, lights / unansweredLoss);
		if (cuedShare > CUED_SHARE_MAX) {
			throw failure(helper, "a grip met after the cue costs %.1f%% of a layer 1 run, over the %.0f%% allowed", 100 * cuedShare, 100 * CUED_SHARE_MAX);
		}
		if (uncuedShare > UNCUED_SHARE_MAX) {
			throw failure(helper, "a grip met without the cue costs %.1f%% of a layer 1 run, over the %.0f%% allowed", 100 * uncuedShare, 100 * UNCUED_SHARE_MAX);
		}
		if (unansweredShare < UNANSWERED_SHARE_MIN) {
			throw failure(helper, "an unanswered grip costs %.0f%% of a layer 1 run, under the %.0f%% that makes answering it worth the trouble", 100 * unansweredShare, 100 * UNANSWERED_SHARE_MIN);
		}
		if (lights / unansweredLoss > LIGHTS_PAYBACK_GRABS_MAX) {
			throw failure(helper, "a lights part of $%d pays back after %.1f unanswered grabs, over %.0f", lights, lights / unansweredLoss, LIGHTS_PAYBACK_GRABS_MAX);
		}
		helper.succeed();
	}

	/** The floor is what keeps a grip nobody answers from stranding the pod by itself: it leaves a few litres for the way up. */
	@GameTest
	public void anUnansweredGripLeavesTheStockTankItsFloor(GameTestHelper helper) {
		PodStats stock = PodStats.base();
		double left = stock.tankLitres() - unansweredLitres(stock, CreatureTuning.DEFAULT);
		if (left <= 0) {
			throw failure(helper, "an unanswered grip empties a %.0f litre stock tank", stock.tankLitres());
		}
		helper.succeed();
	}

	@GameTest
	public void aKilledGripTakesALessShareOfALargerTank(GameTestHelper helper) {
		CreatureTuning tuning = CreatureTuning.DEFAULT;
		PodStats stock = PodStats.base();
		PodStats upgraded = EarlyRunModel.mole(2, 2, 2);
		double litres = killedLitres(tuning, CUED_REACTION_SECONDS);
		double stockShare = litres / stock.tankLitres();
		double upgradedShare = litres / upgraded.tankLitres();
		LOGGER.info("[retained-economy] a killed grip takes {} L: {} of a stock tank, {} of a tier 2 tank of {} L", litres, stockShare, upgradedShare, upgraded.tankLitres());
		if (upgradedShare >= stockShare) {
			throw failure(helper, "a bigger tank loses %.1f%% to a killed grip, as much as a stock tank's %.1f%%", 100 * upgradedShare, 100 * stockShare);
		}
		helper.succeed();
	}

	/**
	 * Figures placed in a run in a layer whose roll passes {@code chance}: the pinned rate, held down by the two limits the tuning sets. The
	 * delay after a kill allows one placement per {@code respawnDelayTicks}; and the figures that are not met are not killed, so they stand
	 * until the cap, which leaves {@code maxPerLevel / (1 - MEET_SHARE)} placements before the level is full.
	 */
	private static double placedPerRun(CreatureTuning tuning, double chance) {
		double perMinute = Math.min(PLACED_PER_MINUTE_PER_CHANCE * chance, 60.0 * TICKS_PER_SECOND / tuning.respawnDelayTicks());
		return Math.min(perMinute * RUN_MINUTES, tuning.maxPerLevel() / (1 - MEET_SHARE));
	}

	/** Grabs a run of {@code RUN_MINUTES} meets in a layer whose roll passes {@code chance} of the spawner's tries. */
	private static double grabsPerRun(CreatureTuning tuning, double chance) {
		return placedPerRun(tuning, chance) * MEET_SHARE;
	}

	/**
	 * (A, #506) The Retained are a presence in the deep and a minor cost in layer 1. Layer 1: the expected cost of the grabs a run meets, each
	 * answered after the cue, stays under 5 per cent of the run. Layer 2: a run meets at least one and at most five.
	 */
	@GameTest
	public void theFiguresRiseWithDepthAndStayFairInLayerOne(GameTestHelper helper) {
		CreatureTuning tuning = CreatureTuning.DEFAULT;
		PodStats stock = PodStats.base();
		double whole = EarlyRunModel.run(Zone.load("topsoil_claims"), stock, 0).litres();
		double grabLitres = killedLitres(tuning, CUED_REACTION_SECONDS);
		double oneGrabs = grabsPerRun(tuning, tuning.natural().layerOneChancePerTry());
		double twoGrabs = grabsPerRun(tuning, tuning.natural().chancePerTry());
		double oneShare = oneGrabs * grabLitres / whole;
		PodStats deep = EarlyRunModel.mole(2, 2, 2);
		double twoShare = twoGrabs * grabLitres / deep.tankLitres();
		LOGGER.info("[retained-economy] #506: layer 1 {} grabs a run, {} of a run lost; layer 2 {} grabs a run, {} of a tier 2 tank lost", oneGrabs, oneShare, twoGrabs, twoShare);
		if (oneShare > LAYER_ONE_EXPECTED_SHARE_MAX) {
			throw failure(helper, "the grabs of a layer 1 run cost %.1f%% of it on average, over the %.0f%% allowed", 100 * oneShare, 100 * LAYER_ONE_EXPECTED_SHARE_MAX);
		}
		if (twoGrabs < LAYER_TWO_GRABS_MIN || twoGrabs > LAYER_TWO_GRABS_MAX) {
			throw failure(helper, "a layer 2 run meets %.2f figures on average, outside %.0f to %.0f", twoGrabs, LAYER_TWO_GRABS_MIN, LAYER_TWO_GRABS_MAX);
		}
		if (placedPerRun(tuning, tuning.natural().chancePerTry()) >= tuning.maxPerLevel() / (1 - MEET_SHARE)) {
			throw failure(helper, "the cap of %d binds a layer 2 run: the model's placements stop at the cap, not at the roll", tuning.maxPerLevel());
		}
		if (twoGrabs <= oneGrabs) {
			throw failure(helper, "a layer 2 run meets %.2f figures and layer 1 %.2f: they do not rise with depth", twoGrabs, oneGrabs);
		}
		helper.succeed();
	}
}
