package io.github.pkeppeler.deepcharter.pod;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import io.github.pkeppeler.deepcharter.pod.SignalDecay.Instrument;

/**
 * Tunables for the signal decay (#502), read as {@code SignalDecayTuning.DEFAULT.thing()}. The console part's tiers and prices are {@code UpgradeTuning}'s.
 *
 * @param layers          for layer 1, 2, ... the distance from the floor at which the decay starts and the distance under which the cockpit is dark; a layer past the list uses its last entry
 * @param thresholds      for each instrument, the intensity above which it starts to fail, in [0, 1): the order they fail in
 * @param maxFaultChance  the chance that an instrument is faulty in a time cell, when its intensity is just under 1
 * @param jumpShare       the share of faults that are a jump (a false reading), the rest are drop-outs
 */
public record SignalDecayTuning(List<Band> layers, Map<Instrument, Double> thresholds, double maxFaultChance, double jumpShare) {
	/** One layer's curve: {@code onset} slabs above the floor the decay starts, {@code blackout} slabs above it (and under) the cockpit is dark. */
	public record Band(int onset, int blackout) {
		public Band {
			if (blackout < 0 || onset <= blackout) {
				throw new IllegalArgumentException("the decay must start above the blackout, got onset " + onset + " and blackout " + blackout);
			}
		}
	}

	public static final SignalDecayTuning DEFAULT = new SignalDecayTuning(List.of(new Band(24, 4), new Band(40, 6), new Band(56, 8)), defaultThresholds(), 0.55, 0.5);

	public SignalDecayTuning {
		layers = List.copyOf(layers);
		thresholds = Map.copyOf(thresholds);
		if (layers.isEmpty()) {
			throw new IllegalArgumentException("the decay needs a curve for at least one layer");
		}
		for (Instrument instrument : Instrument.values()) {
			Double threshold = thresholds.get(instrument);
			if (threshold == null || !(threshold >= 0 && threshold < 1)) {
				throw new IllegalArgumentException("the " + instrument + " needs a threshold in [0, 1), got " + threshold);
			}
		}
		if (!(maxFaultChance > 0 && maxFaultChance <= 1) || !(jumpShare >= 0 && jumpShare <= 1)) {
			throw new IllegalArgumentException("chances are in (0, 1], got " + maxFaultChance + " and " + jumpShare);
		}
	}

	/** The curve of {@code layer} (1 or more). */
	public Band band(int layer) {
		return layers.get(Math.min(layer, layers.size()) - 1);
	}

	/** (A) The scanner fails first, noise from the first slab; then the heat gauge, depth, cargo, fuel, the readout plate and the lamps; the hull gauge last. */
	private static Map<Instrument, Double> defaultThresholds() {
		Map<Instrument, Double> thresholds = new EnumMap<>(Instrument.class);
		thresholds.put(Instrument.SCANNER, 0.0);
		thresholds.put(Instrument.HEAT, 0.2);
		thresholds.put(Instrument.DEPTH, 0.35);
		thresholds.put(Instrument.CARGO, 0.5);
		thresholds.put(Instrument.FUEL, 0.6);
		thresholds.put(Instrument.PLATE, 0.7);
		thresholds.put(Instrument.LAMPS, 0.8);
		thresholds.put(Instrument.HULL, 0.9);
		return thresholds;
	}
}
