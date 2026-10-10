package io.github.pkeppeler.deepcharter.test;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.StreamSupport;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;

import io.github.pkeppeler.deepcharter.pod.Chassis;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.pod.PodTowing;
import io.github.pkeppeler.deepcharter.pod.TowReachProbe;
import io.github.pkeppeler.deepcharter.pod.TowTuning;
import io.github.pkeppeler.deepcharter.test.support.OddPods;
import io.github.pkeppeler.deepcharter.upgrade.ComponentTrack;

/**
 * Server GameTests for #399 on a wide chassis ({@link OddPods#CHASSIS}: 3.9 wide, 2.9 tall, a 4-wide, 3-tall bore), neither the Mole's
 * 2 x 2 nor the Prospector's 3 x 3. The checks are {@link ChassisChecks}, the same as {@link TallChassisTest} runs on a tall one. The
 * tests at the end hold for every chassis there is: the tow reach, the part tier the constructor accepts, and the doors of the colony's bays.
 */
public class OddChassisTest {
	private static final ChassisChecks CHECKS = new ChassisChecks(OddPods.CHASSIS, OddPods.TYPE, 0);
	/** The layout the colony is built from; its doors come from {@code tools/colony/town.py}. */
	private static final String LAYOUT = "/data/deepcharter/colony/layout.json";

	private static List<Chassis> everyChassis() {
		List<Chassis> all = new ArrayList<>(Chassis.all());
		all.add(OddPods.CHASSIS);
		all.add(OddPods.TALL);
		return all;
	}

	private static RuntimeException failure(GameTestHelper helper, String format, Object... args) {
		return helper.assertionException(Component.literal(String.format(format, args)));
	}

	@GameTest(maxTicks = ChassisChecks.TICKS)
	public void anOddPodBoresDownTheWholeOfItsFootprintAndNothingMore(GameTestHelper helper) {
		CHECKS.boresDownTheWholeFootprintAndNothingMore(helper);
	}

	@GameTest(maxTicks = ChassisChecks.TICKS)
	public void anOddPodBoresSidewaysTheWholeOfItsHeightAndWidth(GameTestHelper helper) {
		CHECKS.boresSidewaysTheWholeHeightAndWidth(helper);
	}

	@GameTest(maxTicks = ChassisChecks.TICKS + 1500)
	public void anOddPodLinesTheRingOfItsWholeFootprintAndBoxHeight(GameTestHelper helper) {
		CHECKS.linesTheRingOfTheWholeFootprintAndBoxHeight(helper);
	}

	@GameTest(maxTicks = ChassisChecks.TICKS)
	public void anOddPodSounderHearsAPocketInTheFarCornerOfItsFootprint(GameTestHelper helper) {
		CHECKS.soundsAPocketInTheFarCornerOfTheFootprint(helper);
	}

	@GameTest(maxTicks = ChassisChecks.TICKS)
	public void anOddPodSounderMarksTheSideOfAPocketAtTheTopOfItsBox(GameTestHelper helper) {
		CHECKS.soundsTheSideOfAPocketAtTheTopOfTheBox(helper);
	}

	@GameTest(maxTicks = ChassisChecks.TICKS)
	public void anOddPodThermalScannerMarksLavaAMarginBeyondItsWholeBore(GameTestHelper helper) {
		CHECKS.scansLavaAMarginBeyondTheWholeBore(helper);
	}

	@GameTest(maxTicks = ChassisChecks.TICKS)
	public void anOddPodLandsHardBySinkSpeedAndTheHullTakesIt(GameTestHelper helper) {
		CHECKS.landsHardBySinkSpeedAndTheHullTakesIt(helper);
	}

	@GameTest(maxTicks = ChassisChecks.TICKS)
	public void anOddPodTowedByAnotherTrailsWithoutTheTwoHullsOverlapping(GameTestHelper helper) {
		CHECKS.trailsTowedWithoutTheTwoHullsOverlapping(helper);
	}

	@GameTest
	public void anOddPodBoreHoldsOreInProportionToTheCellsOfItsSlab(GameTestHelper helper) {
		CHECKS.holdsOreInProportionToTheCellsOfASlab(helper);
	}

	@GameTest(maxTicks = ChassisChecks.TICKS)
	public void anOddPodWreckKeepsItsBoxAndTheBayParksClearOfIt(GameTestHelper helper) {
		CHECKS.wreckKeepsItsBoxAndTheBayParksClearOfIt(helper);
	}

	@GameTest(maxTicks = ChassisChecks.TICKS)
	public void anOddPodBayParksInPlacesThatFitAndNeverOverlap(GameTestHelper helper) {
		CHECKS.parksInBayPlacesThatFitAndNeverOverlap(helper);
	}

	/**
	 * A cable's reach covers the trail of the widest chassis, even when the configured reach is short. With a reach of 1 and the widest
	 * hull the 3.9 of {@link OddPods#CHASSIS}, the reach in use is 5.1: 3.9 + 0.6 for the trail, and 0.6 to spare. Two Moles 5 blocks
	 * apart are then in reach for fitting a cable and for the tower to feel the pod it tows.
	 */
	@GameTest
	public void theTowReachCoversTheTrailOfTheWidestChassis(GameTestHelper helper) {
		TowTuning shortReach = new TowTuning(1.0, 0.6, 25f, 4, 0.4, 16);
		double reach = TowReachProbe.reach(shortReach);
		if (Math.abs(reach - 5.1) > 1e-4) {
			throw failure(helper, "the tow reach should be 5.1 for a widest hull of 3.9 and a configured reach of 1, it is %s", reach);
		}
		PodEntity tower = helper.spawn(PodRegistry.POD, new Vec3(1.5, 2, 1.5));
		PodEntity towed = helper.spawn(PodRegistry.POD, new Vec3(1.5, 2, 6.5));
		Optional<PodTowing.Refusal> refusal = TowReachProbe.refusal(tower, towed, shortReach);
		if (refusal.isPresent()) {
			throw failure(helper, "a pod 5 blocks away should be in reach, but a cable is refused: %s", refusal.get());
		}
		PodTowing.attach(tower, towed);
		if (!TowReachProbe.towedBy(tower, shortReach).contains(towed)) {
			throw failure(helper, "the tower should find the pod it tows 5 blocks away");
		}
		helper.succeed();
	}

	/** The constructor refuses a part tier no track has: 0, and one above the best, so a swap of the cap and the seats cannot register. */
	@GameTest
	public void aChassisRefusesAPartTierThatDoesNotExist(GameTestHelper helper) {
		int best = 0;
		for (ComponentTrack track : ComponentTrack.values()) {
			best = Math.max(best, track.maxTier());
		}
		for (int cap : new int[] {0, best + 1}) {
			try {
				new Chassis("bad", 1, cap, 1.9f, 1.9f);
			} catch (IllegalArgumentException expected) {
				continue;
			}
			throw failure(helper, "a chassis with part tier cap %s should be refused, tiers go from 1 to %s", cap, best);
		}
		helper.succeed();
	}

	/**
	 * The colony's hangar and works bays are the doors a pod leaves by. Their size is read from the layout the game loads, so shrinking
	 * a door in {@code tools/colony/town.py} fails here for every chassis it no longer passes.
	 */
	@GameTest
	public void everyChassisPassesTheColonysBayDoors(GameTestHelper helper) {
		JsonObject layout;
		try (Reader reader = new InputStreamReader(OddChassisTest.class.getResourceAsStream(LAYOUT))) {
			layout = JsonParser.parseReader(reader).getAsJsonObject();
		} catch (IOException e) {
			throw failure(helper, "the colony layout %s cannot be read: %s", LAYOUT, e);
		}
		List<JsonObject> bays = StreamSupport.stream(layout.getAsJsonArray("doors").spliterator(), false).map(JsonElement::getAsJsonObject)
				.filter(door -> door.get("building").getAsString().endsWith(" bay")).toList();
		if (bays.size() != 2) {
			throw failure(helper, "the colony should have the hangar bay and the works bay, the layout has %s", bays);
		}
		for (JsonObject bay : bays) {
			for (Chassis chassis : everyChassis()) {
				if (chassis.width() > bay.get("width").getAsInt() || chassis.height() > bay.get("height").getAsInt()) {
					throw failure(helper, "the chassis %s is %s x %s, which does not pass the %s door of %s x %s", chassis.id(), chassis.width(), chassis.height(),
							bay.get("building").getAsString(), bay.get("width").getAsInt(), bay.get("height").getAsInt());
				}
			}
		}
		helper.succeed();
	}
}
