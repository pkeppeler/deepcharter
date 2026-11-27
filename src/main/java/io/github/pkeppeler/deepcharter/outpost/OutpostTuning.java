package io.github.pkeppeler.deepcharter.outpost;

/**
 * Tunables for the outpost feature, read as {@code OutpostTuning.DEFAULT.thing()}.
 *
 * @param sellPercent    the share of an ore's colony price that a sell terminal at depth pays (SPEC section 11: outposts sell at worse
 *                       rates than the colony, so the surface trip still matters)
 * @param fuelPriceFactor how many times the colony's price a litre costs at a fuel pump at depth: the fuel is hauled down the shaft
 * @param floorBand      how many blocks above the top of the breach crust the kit still works: the floor of a layer is not flat
 */
public record OutpostTuning(int sellPercent, int fuelPriceFactor, int floorBand) {
    public static final OutpostTuning DEFAULT = new OutpostTuning(80, 2, 8);

    public OutpostTuning {
        if (sellPercent < 1 || sellPercent > 99) {
            throw new IllegalArgumentException(
                    "an outpost pays a share of the colony's price, 1 to 99 percent, got " + sellPercent);
        }
        if (fuelPriceFactor < 2) {
            throw new IllegalArgumentException(
                    "outpost fuel costs at least twice the colony's, got " + fuelPriceFactor);
        }
        if (floorBand < 0) {
            throw new IllegalArgumentException("the floor band cannot be negative, got " + floorBand);
        }
    }
}
