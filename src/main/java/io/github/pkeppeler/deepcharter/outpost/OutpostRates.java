package io.github.pkeppeler.deepcharter.outpost;

import io.github.pkeppeler.deepcharter.fuel.FuelTuning;
import io.github.pkeppeler.deepcharter.layer.LayerChain;
import io.github.pkeppeler.deepcharter.ore.OreType;
import java.util.OptionalInt;
import net.minecraft.world.level.Level;

/**
 * What a terminal charges and pays depends on where it stands: the colony's own are priced by {@link FuelTuning} and
 * {@link OreType#value()}, and a pump or a sell terminal in a layer, which is an outpost's (SPEC section 11), sells fuel dearer and buys ore
 * for less ({@link OutpostTuning}). The terminals read it on the server and the screens on the client, from the level they are in, so both show the
 * price that is charged.
 */
public final class OutpostRates {
    private OutpostRates() {}

    /** The layer {@code level} is in the chain, or empty on the surface (the colony's level) and in any other dimension. */
    public static OptionalInt layerOf(Level level) {
        return level.dimensionTypeRegistration()
                .unwrapKey()
                .map(type -> LayerChain.layerOf(type.identifier()))
                .orElse(OptionalInt.empty());
    }

    /** True in a layer of the chain, false on the surface (the colony's level) and in any other dimension. */
    public static boolean atDepth(Level level) {
        return layerOf(level).isPresent();
    }

    /** Dollars one litre costs at a fuel pump in {@code level}. */
    public static long fuelPricePerLitre(Level level) {
        long colony = FuelTuning.DEFAULT.pricePerLitre();
        return atDepth(level) ? colony * OutpostTuning.DEFAULT.fuelPriceFactor() : colony;
    }

    /** Dollars one {@code ore} sells for at a sell terminal in {@code level}: a whole number, rounded down. */
    public static long orePrice(Level level, OreType ore) {
        return atDepth(level) ? ore.value() * OutpostTuning.DEFAULT.sellPercent() / 100 : ore.value();
    }
}
