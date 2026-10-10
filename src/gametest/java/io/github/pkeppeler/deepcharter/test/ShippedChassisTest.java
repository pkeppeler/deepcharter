package io.github.pkeppeler.deepcharter.test;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;

import io.github.pkeppeler.deepcharter.pod.Chassis;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;

/**
 * Server GameTests for #400: the {@link ChassisChecks} of #399 run on the two chassis the game ships, the Mole (1.9 wide, 2.9 tall: a
 * 2-wide, 3-tall bore) and the Prospector (2.9 by 2.9: a 3 by 3 bore), so a change to either size is checked against every behaviour that
 * reads it. The first test pins the sizes and the ladder between them.
 */
public class ShippedChassisTest {
	private static final ChassisChecks MOLE = new ChassisChecks(Chassis.MOLE, PodRegistry.typeOf(Chassis.MOLE), 2000);
	private static final ChassisChecks PROSPECTOR = new ChassisChecks(Chassis.PROSPECTOR, PodRegistry.typeOf(Chassis.PROSPECTOR), 3000);

	private static RuntimeException failure(GameTestHelper helper, String message) {
		return helper.assertionException(Component.literal(message));
	}

	/**
	 * The sizes are literals, so a change to one is a change to this test and to the table in mechanics.md. The ladder (ADR 0042): each
	 * chassis of {@link Chassis#all()} is wider than the one before it, and so has more cells in a slab and in the bore a sideways step cuts.
	 */
	@GameTest
	public void theMoleAndTheProspectorHaveTheSizesOfTheLadder(GameTestHelper helper) {
		if (Chassis.MOLE.width() != 1.9f || Chassis.MOLE.height() != 2.9f) {
			throw failure(helper, "the Mole is " + Chassis.MOLE.width() + " x " + Chassis.MOLE.height() + ", not 1.9 x 2.9");
		}
		if (Chassis.PROSPECTOR.width() != 2.9f || Chassis.PROSPECTOR.height() != 2.9f) {
			throw failure(helper, "the Prospector is " + Chassis.PROSPECTOR.width() + " x " + Chassis.PROSPECTOR.height() + ", not 2.9 x 2.9");
		}
		if (Chassis.MOLE.boreWidth() != 2 || Chassis.MOLE.boreHeight() != 3 || Chassis.PROSPECTOR.boreWidth() != 3 || Chassis.PROSPECTOR.boreHeight() != 3) {
			throw failure(helper, "the bores should be 2 x 3 and 3 x 3, they are " + Chassis.MOLE.boreWidth() + " x " + Chassis.MOLE.boreHeight() + " and "
					+ Chassis.PROSPECTOR.boreWidth() + " x " + Chassis.PROSPECTOR.boreHeight());
		}
		Chassis smaller = null;
		for (Chassis chassis : Chassis.all()) {
			if (smaller != null) {
				if (chassis.boreWidth() <= smaller.boreWidth() || chassis.slabCells() <= smaller.slabCells() || chassis.height() < smaller.height()
						|| chassis.boreWidth() * chassis.boreWidth() * chassis.boreHeight() <= smaller.boreWidth() * smaller.boreWidth() * smaller.boreHeight()) {
					throw failure(helper, "the " + chassis.id() + " should be bigger than the " + smaller.id() + " in width, slab and bore, and no lower");
				}
			}
			smaller = chassis;
		}
		helper.succeed();
	}

	@GameTest(maxTicks = ChassisChecks.TICKS)
	public void aMoleBoresDownTheWholeOfItsFootprintAndNothingMore(GameTestHelper helper) {
		MOLE.boresDownTheWholeFootprintAndNothingMore(helper);
	}

	@GameTest(maxTicks = ChassisChecks.TICKS)
	public void aMoleBoresSidewaysTheWholeOfItsHeightAndWidth(GameTestHelper helper) {
		MOLE.boresSidewaysTheWholeHeightAndWidth(helper);
	}

	@GameTest(maxTicks = ChassisChecks.TICKS + 1500)
	public void aMoleLinesTheRingOfItsWholeFootprintAndBoxHeight(GameTestHelper helper) {
		MOLE.linesTheRingOfTheWholeFootprintAndBoxHeight(helper);
	}

	@GameTest(maxTicks = ChassisChecks.TICKS)
	public void aMoleSounderHearsAPocketInTheFarCornerOfItsFootprint(GameTestHelper helper) {
		MOLE.soundsAPocketInTheFarCornerOfTheFootprint(helper);
	}

	@GameTest(maxTicks = ChassisChecks.TICKS)
	public void aMoleSounderMarksTheSideOfAPocketAtTheTopOfItsBox(GameTestHelper helper) {
		MOLE.soundsTheSideOfAPocketAtTheTopOfTheBox(helper);
	}

	@GameTest(maxTicks = ChassisChecks.TICKS)
	public void aMoleThermalScannerMarksLavaAMarginBeyondItsWholeBore(GameTestHelper helper) {
		MOLE.scansLavaAMarginBeyondTheWholeBore(helper);
	}

	@GameTest(maxTicks = ChassisChecks.TICKS)
	public void aMoleLandsHardBySinkSpeedAndTheHullTakesIt(GameTestHelper helper) {
		MOLE.landsHardBySinkSpeedAndTheHullTakesIt(helper);
	}

	@GameTest(maxTicks = ChassisChecks.TICKS)
	public void aMoleTowedByAnotherTrailsWithoutTheTwoHullsOverlapping(GameTestHelper helper) {
		MOLE.trailsTowedWithoutTheTwoHullsOverlapping(helper);
	}

	@GameTest
	public void aMoleBoreHoldsOreInProportionToTheCellsOfItsSlab(GameTestHelper helper) {
		MOLE.holdsOreInProportionToTheCellsOfASlab(helper);
	}

	@GameTest(maxTicks = ChassisChecks.TICKS)
	public void aMoleWreckKeepsItsBoxAndTheBayParksClearOfIt(GameTestHelper helper) {
		MOLE.wreckKeepsItsBoxAndTheBayParksClearOfIt(helper);
	}

	@GameTest(maxTicks = ChassisChecks.TICKS)
	public void aMoleBayParksInPlacesThatFitAndNeverOverlap(GameTestHelper helper) {
		MOLE.parksInBayPlacesThatFitAndNeverOverlap(helper);
	}

	@GameTest(maxTicks = ChassisChecks.TICKS)
	public void aProspectorBoresDownTheWholeOfItsFootprintAndNothingMore(GameTestHelper helper) {
		PROSPECTOR.boresDownTheWholeFootprintAndNothingMore(helper);
	}

	@GameTest(maxTicks = ChassisChecks.TICKS)
	public void aProspectorBoresSidewaysTheWholeOfItsHeightAndWidth(GameTestHelper helper) {
		PROSPECTOR.boresSidewaysTheWholeHeightAndWidth(helper);
	}

	@GameTest(maxTicks = ChassisChecks.TICKS + 1500)
	public void aProspectorLinesTheRingOfItsWholeFootprintAndBoxHeight(GameTestHelper helper) {
		PROSPECTOR.linesTheRingOfTheWholeFootprintAndBoxHeight(helper);
	}

	@GameTest(maxTicks = ChassisChecks.TICKS)
	public void aProspectorSounderHearsAPocketInTheFarCornerOfItsFootprint(GameTestHelper helper) {
		PROSPECTOR.soundsAPocketInTheFarCornerOfTheFootprint(helper);
	}

	@GameTest(maxTicks = ChassisChecks.TICKS)
	public void aProspectorSounderMarksTheSideOfAPocketAtTheTopOfItsBox(GameTestHelper helper) {
		PROSPECTOR.soundsTheSideOfAPocketAtTheTopOfTheBox(helper);
	}

	@GameTest(maxTicks = ChassisChecks.TICKS)
	public void aProspectorThermalScannerMarksLavaAMarginBeyondItsWholeBore(GameTestHelper helper) {
		PROSPECTOR.scansLavaAMarginBeyondTheWholeBore(helper);
	}

	@GameTest(maxTicks = ChassisChecks.TICKS)
	public void aProspectorLandsHardBySinkSpeedAndTheHullTakesIt(GameTestHelper helper) {
		PROSPECTOR.landsHardBySinkSpeedAndTheHullTakesIt(helper);
	}

	@GameTest(maxTicks = ChassisChecks.TICKS)
	public void aProspectorTowedByAnotherTrailsWithoutTheTwoHullsOverlapping(GameTestHelper helper) {
		PROSPECTOR.trailsTowedWithoutTheTwoHullsOverlapping(helper);
	}

	@GameTest
	public void aProspectorBoreHoldsOreInProportionToTheCellsOfItsSlab(GameTestHelper helper) {
		PROSPECTOR.holdsOreInProportionToTheCellsOfASlab(helper);
	}

	@GameTest(maxTicks = ChassisChecks.TICKS)
	public void aProspectorWreckKeepsItsBoxAndTheBayParksClearOfIt(GameTestHelper helper) {
		PROSPECTOR.wreckKeepsItsBoxAndTheBayParksClearOfIt(helper);
	}

	@GameTest(maxTicks = ChassisChecks.TICKS)
	public void aProspectorBayParksInPlacesThatFitAndNeverOverlap(GameTestHelper helper) {
		PROSPECTOR.parksInBayPlacesThatFitAndNeverOverlap(helper);
	}
}
