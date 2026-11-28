package io.github.pkeppeler.deepcharter.market;

import io.github.pkeppeler.deepcharter.ore.OreRegistry;
import io.github.pkeppeler.deepcharter.outpost.OutpostRates;
import java.util.Locale;
import java.util.Optional;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/**
 * The price line of an ore's tooltip (#1669): what the ore processor pays for one at the colony and what an outpost's
 * sell terminal pays at depth, read from {@link OutpostRates}, which the processor pays from, so the line and the sale
 * cannot drift. The figures are written as the ore's spec sheet writes its worth. A tooltip provider is not handed the
 * stack, so the client adds this line from the tooltip event, under the mass line.
 */
public final class OrePriceTooltip {
    private OrePriceTooltip() {}

    /** The line for {@code stack}, or empty when it is not ore. */
    public static Optional<Component> line(ItemStack stack) {
        return OreRegistry.typeOf(stack)
                .map(ore -> Component.translatable(
                                "deepcharter.market.tooltip.price",
                                dollars(OutpostRates.colonyOrePrice(ore)),
                                dollars(OutpostRates.outpostOrePrice(ore)))
                        .withStyle(ChatFormatting.GRAY));
    }

    private static String dollars(long amount) {
        return String.format(Locale.ROOT, "%,d", amount);
    }
}
