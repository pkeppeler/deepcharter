package io.github.pkeppeler.deepcharter.fuel;

import java.util.List;

/**
 * Tunables for the fuel feature, read as {@code FuelTuning.DEFAULT.thing()}. What a fuel item gives is data
 * ({@code pod_fuel/<item>.json}), not a tunable.
 *
 * @param pricePerLitre dollars the pump charges for one litre (the original's price)
 * @param purchaseSteps the litres of the pump screen's buy buttons, besides FILL
 * @param reserveLitres what a reserve tank adds to the tank, and what it holds when it rescues a stranded pod
 * @param solidStack    how many of a solid fuel (coal, charcoal, biofuel) stack: a stack of 64 was 128 L in one slot
 * (#1280)
 * @param weight        what a solid fuel weighs, in pod units, so a pack of it slows a person and cuts a pod's lift as
 * ore does
 * @param soot          the fouling of the engine by solid fuel
 */
public record FuelTuning(
        long pricePerLitre,
        List<Integer> purchaseSteps,
        float reserveLitres,
        int solidStack,
        Weight weight,
        Soot soot) {
    public static final FuelTuning DEFAULT =
            new FuelTuning(1, List.of(1, 5, 10), 25f, 16, new Weight(0.6f, 0.4f, 0.5f), new Soot(3f, 0.03f, 0.5f, 2));

    /** Mass of one item of each solid fuel. */
    public record Weight(float coal, float charcoal, float biofuel) {
        public Weight {
            if (!(coal > 0 && charcoal > 0 && biofuel > 0)) {
                throw new IllegalArgumentException(
                        "a solid fuel must weigh something, got " + coal + ", " + charcoal + ", " + biofuel);
            }
        }
    }

    /**
     * What soot does. A litre of fuel adds its fuel's {@code soot} (data, {@code pod_fuel/*.json}) litres of soot as it
     * burns.
     *
     * @param graceLitres      the soot the engine takes before it burns more: the indicator and the first line come
     * here
     * @param penaltyPerLitre  the extra share of fuel the engine burns for each litre of soot past the grace
     * @param maxPenalty       the most extra share it burns, however fouled
     * @param servicePerLitre  dollars the repair station charges to clear a litre of soot
     */
    public record Soot(float graceLitres, float penaltyPerLitre, float maxPenalty, long servicePerLitre) {
        public Soot {
            if (!(graceLitres >= 0 && penaltyPerLitre > 0 && maxPenalty > 0 && servicePerLitre >= 1)) {
                throw new IllegalArgumentException("bad soot tuning: " + graceLitres + ", " + penaltyPerLitre + ", "
                        + maxPenalty + ", " + servicePerLitre);
            }
        }
    }

    public FuelTuning {
        if (pricePerLitre < 1) {
            throw new IllegalArgumentException("the price of a litre must be at least $1, got " + pricePerLitre);
        }
        purchaseSteps = List.copyOf(purchaseSteps);
        if (purchaseSteps.stream().anyMatch(step -> step < 1)) {
            throw new IllegalArgumentException("every purchase step must be at least 1 litre, got " + purchaseSteps);
        }
        if (solidStack < 1 || solidStack > 64) {
            throw new IllegalArgumentException("a solid fuel stack is 1 to 64, got " + solidStack);
        }
        if (!(reserveLitres > 0)) {
            throw new IllegalArgumentException("the reserve must hold some litres, got " + reserveLitres);
        }
    }
}
