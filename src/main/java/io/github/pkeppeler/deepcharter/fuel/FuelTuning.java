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
    public static final FuelTuning DEFAULT = new FuelTuning(
            1,
            List.of(1, 5, 10),
            25f,
            16,
            new Weight(0.6f, 0.4f, 0.5f),
            new Soot(3f, 12f, 0.5f, 0.5f, 0.3f, 2f, 10, 1200, 11f));

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
     * burns. Up to the grace nothing happens. From the grace to the limp limit the engine loses output and burns more,
     * in a straight line. At the limit it is in limp mode (never stopped, so a pod deep down can always climb home).
     *
     * @param graceLitres      the soot the engine takes before it loses anything: the readout and the first line come
     *                         here
     * @param limpLitres       the soot at which the engine drops to limp mode, which only a service at the colony ends
     * @param lossAtLimit      the share of top speed and climb speed lost just under the limit
     * @param extraBurnAtLimit the extra share of fuel burned just under the limit
     * @param limpOutput       the share of top speed and climb speed left in limp mode
     * @param limpBurn         how many times the clean burn the engine burns in limp mode
     * @param servicePerLitre  dollars the repair station charges to clear a litre of soot
     * @param scrapeTicks      ticks the free flue scrape at the colony's repair station takes (a minute, as long as
     *                         the Company's recovery makes a stranded pilot wait)
     * @param scrapeLeavesLitres the soot a flue scrape leaves: under the limp limit, so the pod launches, and over the
     *                         grace, so it is still slow until it is serviced
     */
    public record Soot(
            float graceLitres,
            float limpLitres,
            float lossAtLimit,
            float extraBurnAtLimit,
            float limpOutput,
            float limpBurn,
            long servicePerLitre,
            int scrapeTicks,
            float scrapeLeavesLitres) {
        public Soot {
            if (!(graceLitres >= 0 && limpLitres > graceLitres)) {
                throw new IllegalArgumentException(
                        "the limp limit must come after the grace: " + graceLitres + ", " + limpLitres);
            }
            if (!(lossAtLimit > 0 && lossAtLimit < 1 && extraBurnAtLimit > 0)) {
                throw new IllegalArgumentException("bad soot loss: " + lossAtLimit + ", " + extraBurnAtLimit);
            }
            if (!(limpOutput > 0 && limpOutput < 1 - lossAtLimit + 1e-6 && limpBurn >= 1 + extraBurnAtLimit)) {
                throw new IllegalArgumentException(
                        "limp mode must be the worst of the engine, but still move: " + limpOutput + ", " + limpBurn);
            }
            if (servicePerLitre < 1) {
                throw new IllegalArgumentException("a litre of soot must cost at least $1, got " + servicePerLitre);
            }
            if (scrapeTicks < 1 || !(scrapeLeavesLitres > graceLitres && scrapeLeavesLitres < limpLitres)) {
                throw new IllegalArgumentException(
                        "a flue scrape takes time and leaves the engine between the grace and" + " the limit, got "
                                + scrapeTicks + " ticks, " + scrapeLeavesLitres + " L");
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
