package io.github.pkeppeler.deepcharter.test;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.gametest.framework.GameTestHelper;

import io.github.pkeppeler.deepcharter.test.support.OddPods;

/**
 * Server GameTests for #399 on a tall, narrow chassis ({@link OddPods#TALL}: 1.9 wide, 3.9 tall, a 2-wide, 4-tall bore), the shape a
 * taller Mole would have. The checks are {@link ChassisChecks}, the same as {@link OddChassisTest} runs on a wide one.
 */
public class TallChassisTest {
	private static final ChassisChecks CHECKS = new ChassisChecks(OddPods.TALL, OddPods.TALL_TYPE, 1000);

	@GameTest(maxTicks = ChassisChecks.TICKS)
	public void aTallPodBoresDownTheWholeOfItsFootprintAndNothingMore(GameTestHelper helper) {
		CHECKS.boresDownTheWholeFootprintAndNothingMore(helper);
	}

	@GameTest(maxTicks = ChassisChecks.TICKS)
	public void aTallPodBoresSidewaysTheWholeOfItsHeightAndWidth(GameTestHelper helper) {
		CHECKS.boresSidewaysTheWholeHeightAndWidth(helper);
	}

	@GameTest(maxTicks = ChassisChecks.TICKS + 1500)
	public void aTallPodLinesTheRingOfItsWholeFootprintAndBoxHeight(GameTestHelper helper) {
		CHECKS.linesTheRingOfTheWholeFootprintAndBoxHeight(helper);
	}

	@GameTest(maxTicks = ChassisChecks.TICKS)
	public void aTallPodSounderHearsAPocketInTheFarCornerOfItsFootprint(GameTestHelper helper) {
		CHECKS.soundsAPocketInTheFarCornerOfTheFootprint(helper);
	}

	@GameTest(maxTicks = ChassisChecks.TICKS)
	public void aTallPodSounderMarksTheSideOfAPocketAtTheTopOfItsBox(GameTestHelper helper) {
		CHECKS.soundsTheSideOfAPocketAtTheTopOfTheBox(helper);
	}

	@GameTest(maxTicks = ChassisChecks.TICKS)
	public void aTallPodThermalScannerMarksLavaAMarginBeyondItsWholeBore(GameTestHelper helper) {
		CHECKS.scansLavaAMarginBeyondTheWholeBore(helper);
	}

	@GameTest(maxTicks = ChassisChecks.TICKS)
	public void aTallPodLandsHardBySinkSpeedAndTheHullTakesIt(GameTestHelper helper) {
		CHECKS.landsHardBySinkSpeedAndTheHullTakesIt(helper);
	}

	@GameTest(maxTicks = ChassisChecks.TICKS)
	public void aTallPodTowedByAnotherTrailsWithoutTheTwoHullsOverlapping(GameTestHelper helper) {
		CHECKS.trailsTowedWithoutTheTwoHullsOverlapping(helper);
	}

	@GameTest
	public void aTallPodBoreHoldsOreInProportionToTheCellsOfItsSlab(GameTestHelper helper) {
		CHECKS.holdsOreInProportionToTheCellsOfASlab(helper);
	}

	@GameTest(maxTicks = ChassisChecks.TICKS)
	public void aTallPodWreckKeepsItsBoxAndTheBayParksClearOfIt(GameTestHelper helper) {
		CHECKS.wreckKeepsItsBoxAndTheBayParksClearOfIt(helper);
	}

	@GameTest(maxTicks = ChassisChecks.TICKS)
	public void aTallPodBayParksInPlacesThatFitAndNeverOverlap(GameTestHelper helper) {
		CHECKS.parksInBayPlacesThatFitAndNeverOverlap(helper);
	}
}
