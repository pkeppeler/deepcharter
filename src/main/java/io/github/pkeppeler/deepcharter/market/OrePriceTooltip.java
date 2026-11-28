package io.github.pkeppeler.deepcharter.market;

import java.util.Optional;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/** Red-phase stub for #1669. */
public final class OrePriceTooltip {
    private OrePriceTooltip() {}

    public static Optional<Component> line(ItemStack stack) {
        return Optional.empty();
    }
}
