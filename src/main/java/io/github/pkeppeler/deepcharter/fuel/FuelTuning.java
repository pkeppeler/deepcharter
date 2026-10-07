package io.github.pkeppeler.deepcharter.fuel;

import java.util.List;

/**
 * Tunables for the fuel feature, read as {@code FuelTuning.DEFAULT.thing()}. What a fuel item gives is data
 * ({@code pod_fuel/<item>.json}), not a tunable.
 *
 * @param pricePerLitre dollars the pump charges for one litre (the original's price)
 * @param pumpRadius    blocks from the pump within which a pod counts as parked at it
 * @param purchaseSteps the litres of the pump screen's buy buttons, besides FILL
 * @param reserveLitres what a reserve tank adds to the tank, and what it holds when it rescues a stranded pod
 */
public record FuelTuning(long pricePerLitre, double pumpRadius, List<Integer> purchaseSteps, float reserveLitres) {
	public static final FuelTuning DEFAULT = new FuelTuning(1, 6.0, List.of(1, 5, 10), 25f);

	public FuelTuning {
		if (pricePerLitre < 1) {
			throw new IllegalArgumentException("the price of a litre must be at least $1, got " + pricePerLitre);
		}
		if (!(pumpRadius > 0)) {
			throw new IllegalArgumentException("the pump radius must be positive, got " + pumpRadius);
		}
		purchaseSteps = List.copyOf(purchaseSteps);
		if (purchaseSteps.stream().anyMatch(step -> step < 1)) {
			throw new IllegalArgumentException("every purchase step must be at least 1 litre, got " + purchaseSteps);
		}
		if (!(reserveLitres > 0)) {
			throw new IllegalArgumentException("the reserve must hold some litres, got " + reserveLitres);
		}
	}
}
