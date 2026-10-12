package io.github.pkeppeler.deepcharter.test;

import java.util.EnumMap;
import java.util.Map;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;

import io.github.pkeppeler.deepcharter.layer.LayerChain;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.pod.Chassis;
import io.github.pkeppeler.deepcharter.pod.SignalDecay;
import io.github.pkeppeler.deepcharter.pod.SignalDecay.Fault;
import io.github.pkeppeler.deepcharter.pod.SignalDecay.Instrument;
import io.github.pkeppeler.deepcharter.pod.SignalDecayTuning;
import io.github.pkeppeler.deepcharter.test.support.MockPlayer;
import io.github.pkeppeler.deepcharter.test.support.MockPlayers;
import io.github.pkeppeler.deepcharter.test.support.ScannerPods;
import io.github.pkeppeler.deepcharter.upgrade.ComponentTrack;
import io.github.pkeppeler.deepcharter.upgrade.UpgradeTuning;

/**
 * Server GameTests for the signal decay (#502): the curve of each layer, the console part that pushes it deeper, the order the instruments fail in and that the
 * same seed and tick always give the same fault. The decay is a pure function of the pod's synced state, so a client and a test read the same numbers.
 */
public class SignalDecayTest {
	private static final int CELLS = 4000;
	private static final long SEED = 0x5EEDL;
	private static final double EPSILON = 1e-9;

	private static RuntimeException failure(GameTestHelper helper, String format, Object... args) {
		return helper.assertionException(Component.literal(String.format(format, args)));
	}

	private static int faults(Instrument instrument, double intensity, long seed, Fault kind) {
		int found = 0;
		for (long cell = 0; cell < CELLS; cell++) {
			if (SignalDecay.fault(instrument, intensity, seed, cell) == kind) {
				found++;
			}
		}
		return found;
	}

	private static int anyFault(Instrument instrument, double intensity) {
		return CELLS - faults(instrument, intensity, SEED, Fault.NONE);
	}

	@GameTest
	public void theCurveStartsAtTheOnsetRisesToTheFloorAndIsDarkInTheLastBand(GameTestHelper helper) {
		for (int layer = 1; layer <= 3; layer++) {
			SignalDecayTuning.Band band = SignalDecayTuning.DEFAULT.band(layer);
			if (SignalDecay.intensity(layer, band.onset(), 0) != 0 || SignalDecay.intensity(layer, band.onset() + 50, 0) != 0) {
				throw failure(helper, "layer %d: no decay at or above the onset of %d slabs", layer, band.onset());
			}
			if (SignalDecay.intensity(layer, band.blackout(), 0) != 1 || SignalDecay.intensity(layer, 0, 0) != 1) {
				throw failure(helper, "layer %d: full blackout at or under %d slabs and at the floor", layer, band.blackout());
			}
			double last = 0;
			for (int slabs = band.onset() - 1; slabs > band.blackout(); slabs--) {
				double now = SignalDecay.intensity(layer, slabs, 0);
				if (!(now > last) || now >= 1) {
					throw failure(helper, "layer %d: intensity must rise strictly toward the floor and stay under 1 above the blackout, got %s after %s at %d", layer, now, last, slabs);
				}
				last = now;
			}
		}
		helper.succeed();
	}

	@GameTest
	public void pinsTheStockCurveOfLayersOneAndTwoAndALayerPastTheTableUsesTheLast(GameTestHelper helper) {
		if (!new SignalDecayTuning.Band(24, 4).equals(SignalDecayTuning.DEFAULT.band(1)) || !new SignalDecayTuning.Band(40, 6).equals(SignalDecayTuning.DEFAULT.band(2))
				|| !SignalDecayTuning.DEFAULT.band(3).equals(SignalDecayTuning.DEFAULT.band(9))) {
			throw failure(helper, "the curve is 24/4 in layer 1, 40/6 in layer 2, and a deeper layer repeats the last entry");
		}
		if (Math.abs(SignalDecay.intensity(1, 14, 0) - 0.5) > EPSILON || Math.abs(SignalDecay.intensity(2, 23, 0) - 0.5) > EPSILON) {
			throw failure(helper, "the middle of the band reads 0.5, got %s and %s", SignalDecay.intensity(1, 14, 0), SignalDecay.intensity(2, 23, 0));
		}
		helper.succeed();
	}

	@GameTest
	public void eachConsoleTierPushesTheOnsetDeeperAndTheTopTierIsImmune(GameTestHelper helper) {
		int slabs = 3;
		double last = SignalDecay.intensity(1, slabs, 0);
		for (int tier = 1; tier <= ComponentTrack.CONSOLE.maxTier(); tier++) {
			double now = SignalDecay.intensity(1, slabs, tier);
			if (!(now < last)) {
				throw failure(helper, "console tier %d must read less decay than tier %d at %d slabs: %s against %s", tier, tier - 1, slabs, now, last);
			}
			last = now;
		}
		for (int height = 0; height < 80; height++) {
			for (int layer = 1; layer <= 3; layer++) {
				if (SignalDecay.intensity(layer, height, ComponentTrack.CONSOLE.maxTier()) != 0) {
					throw failure(helper, "the top console tier is immune, got %s in layer %d at %d slabs", SignalDecay.intensity(layer, height, ComponentTrack.CONSOLE.maxTier()), layer, height);
				}
			}
		}
		// A Mole's cap is 2: its best console still has the last slab or so, and only the Prospector's third tier is immune.
		if (!(SignalDecay.intensity(1, 0, 2) == 1) || Chassis.MOLE.tierCap() >= ComponentTrack.CONSOLE.maxTier()) {
			throw failure(helper, "a tier 2 console is not immune at the floor, and a Mole cannot fit the immune tier");
		}
		helper.succeed();
	}

	@GameTest
	public void theConsoleTiersAreSoldAtTheTerminalAndTierZeroIsTheStockOne(GameTestHelper helper) {
		if (UpgradeTuning.DEFAULT.price(ComponentTrack.CONSOLE, 0) != 0 || UpgradeTuning.DEFAULT.ratio(ComponentTrack.CONSOLE, 0) != 1f
				|| ComponentTrack.CONSOLE.firstTierCrafted()) {
			throw failure(helper, "the console ships with every pod (tier 0 free, ratio 1) and its tier 1 is bought");
		}
		if (UpgradeTuning.DEFAULT.price(ComponentTrack.CONSOLE, 1) != 200 || UpgradeTuning.DEFAULT.price(ComponentTrack.CONSOLE, 2) != 500
				|| UpgradeTuning.DEFAULT.price(ComponentTrack.CONSOLE, 3) != 1250) {
			throw failure(helper, "the console costs $200, $500 and $1,250");
		}
		helper.succeed();
	}

	@GameTest
	public void theSameSeedAndCellAlwaysGiveTheSameFaultAndAnotherSeedGivesAnother(GameTestHelper helper) {
		boolean differs = false;
		for (long cell = 0; cell < CELLS; cell++) {
			for (Instrument instrument : Instrument.values()) {
				Fault first = SignalDecay.fault(instrument, 0.95, SEED, cell);
				if (first != SignalDecay.fault(instrument, 0.95, SEED, cell) || SignalDecay.jump(instrument, SEED, cell) != SignalDecay.jump(instrument, SEED, cell)) {
					throw failure(helper, "the fault of %s in cell %d changed between two reads", instrument, cell);
				}
				differs |= first != SignalDecay.fault(instrument, 0.95, SEED + 1, cell);
			}
		}
		if (!differs) {
			throw failure(helper, "two seeds gave the same faults in every cell");
		}
		helper.succeed();
	}

	@GameTest
	public void theInstrumentsFailInOrderAndEachIsCleanUnderItsThreshold(GameTestHelper helper) {
		Instrument[] order = {Instrument.SCANNER, Instrument.HEAT, Instrument.DEPTH, Instrument.CARGO, Instrument.FUEL, Instrument.PLATE, Instrument.LAMPS, Instrument.HULL};
		double last = -1;
		for (Instrument instrument : order) {
			double threshold = SignalDecayTuning.DEFAULT.thresholds().get(instrument);
			if (!(threshold > last) && instrument != Instrument.SCANNER) {
				throw failure(helper, "%s must fail after the one before it, threshold %s after %s", instrument, threshold, last);
			}
			last = threshold;
			if (anyFault(instrument, threshold) != 0) {
				throw failure(helper, "%s must be clean at its threshold %s", instrument, threshold);
			}
			if (threshold < 0.99 && anyFault(instrument, Math.min(0.999, threshold + 0.05)) == 0) {
				throw failure(helper, "%s must fail a little over its threshold %s", instrument, threshold);
			}
		}
		helper.succeed();
	}

	@GameTest
	public void faultsGetMoreFrequentTheDeeperAndEveryInstrumentIsDarkInTheBlackout(GameTestHelper helper) {
		int low = anyFault(Instrument.SCANNER, 0.2);
		int middle = anyFault(Instrument.SCANNER, 0.5);
		int high = anyFault(Instrument.SCANNER, 0.9);
		if (!(low < middle && middle < high)) {
			throw failure(helper, "the scanner's faults must grow with the intensity, got %d, %d and %d in %d cells", low, middle, high, CELLS);
		}
		double ceiling = SignalDecayTuning.DEFAULT.maxFaultChance();
		if (high > CELLS * (ceiling + 0.05)) {
			throw failure(helper, "under the blackout no instrument fails more often than %s of the time, got %d of %d cells", ceiling, high, CELLS);
		}
		Map<Instrument, Integer> jumps = new EnumMap<>(Instrument.class);
		for (Instrument instrument : Instrument.values()) {
			if (faults(instrument, 1.0, SEED, Fault.DROP) != CELLS) {
				throw failure(helper, "%s must be dark in every cell of the blackout", instrument);
			}
			jumps.put(instrument, faults(instrument, 0.99, SEED, Fault.JUMP));
		}
		double share = jumps.get(Instrument.SCANNER) / (double) (jumps.get(Instrument.SCANNER) + faults(Instrument.SCANNER, 0.99, SEED, Fault.DROP));
		if (Math.abs(share - SignalDecayTuning.DEFAULT.jumpShare()) > 0.08) {
			throw failure(helper, "about %s of the faults are jumps, got %s", SignalDecayTuning.DEFAULT.jumpShare(), share);
		}
		helper.succeed();
	}

	@GameTest
	public void aJumpReadsAFalseValueInRangeThatChangesFromCellToCell(GameTestHelper helper) {
		double min = 1;
		double max = 0;
		for (long cell = 0; cell < CELLS; cell++) {
			double value = SignalDecay.jump(Instrument.FUEL, SEED, cell);
			if (!(value >= 0 && value < 1)) {
				throw failure(helper, "a false reading is in [0, 1), got %s", value);
			}
			min = Math.min(min, value);
			max = Math.max(max, value);
		}
		if (max - min < 0.9) {
			throw failure(helper, "false readings must spread over the gauge, got %s to %s", min, max);
		}
		helper.succeed();
	}

	@GameTest
	public void aPodOnTheSurfaceReadsNoDecayAndAPodInALayerReadsItsFloorDistanceAndConsole(GameTestHelper helper) {
		PodEntity surface = helper.spawn(PodRegistry.POD, helper.absoluteVec(new net.minecraft.world.phys.Vec3(1.5, 2, 1.5)));
		if (SignalDecay.intensity(surface) != 0) {
			throw failure(helper, "a pod outside the layers has no decay");
		}
		surface.discard();
		ServerLevel layer = helper.getLevel().getServer().getLevel(LayerChain.dimension(1));
		PodEntity pod = PodRegistry.typeOf(Chassis.MOLE).create(layer, EntitySpawnReason.COMMAND);
		MockPlayer owner = MockPlayers.join(helper, "Decay owner");
		try {
			pod.setPos(0.5, layer.getMinY() + 2, 0.5);
			if (SignalDecay.intensity(pod) != 1) {
				throw failure(helper, "a stock pod 2 slabs over the floor of layer 1 is in the blackout, got %s", SignalDecay.intensity(pod));
			}
			pod.setPos(0.5, layer.getMinY() + 100, 0.5);
			if (SignalDecay.intensity(pod) != 0) {
				throw failure(helper, "a pod 100 slabs over the floor reads no decay, got %s", SignalDecay.intensity(pod));
			}
			pod.setPos(0.5, layer.getMinY() + 14, 0.5);
			double stock = SignalDecay.intensity(pod);
			ScannerPods.fit(helper.getLevel().getServer(), owner.player(), pod, ComponentTrack.CONSOLE, 2);
			if (Math.abs(stock - 0.5) > EPSILON || !(SignalDecay.intensity(pod) < stock)
					|| Math.abs(SignalDecay.intensity(pod) - SignalDecay.intensity(1, 14, 2)) > EPSILON) {
				throw failure(helper, "the pod reads the curve at its height and its fitted console: stock %s, fitted %s", stock, SignalDecay.intensity(pod));
			}
			helper.succeed();
		} finally {
			owner.leave();
			pod.discard();
		}
	}
}
