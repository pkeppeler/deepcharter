package io.github.pkeppeler.deepcharter.ore;

import java.util.Optional;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/**
 * The "Mass N" line of a heavy item's tooltip (#1538): what {@link OreEncumbrance#massOf} counts for the whole stack (an ore at its
 * {@link DrilledMass} when it has one, a fuel at its {@link CargoWeight} times its count), written by {@link OreEncumbrance#shown}, as
 * the pod screen writes the hold. A tooltip provider is not handed the stack, so the client adds this line from the tooltip event.
 */
public final class MassTooltip {
    private MassTooltip() {}

    /** The line for {@code stack}, or empty when it is neither ore nor a {@link CargoWeight} item. */
    public static Optional<Component> line(ItemStack stack) {
        if (OreRegistry.typeOf(stack).isEmpty() && !stack.has(OreRegistry.CARGO_WEIGHT)) {
            return Optional.empty();
        }
        return Optional.of(Component.translatable(
                        "deepcharter.ore.tooltip.mass",
                        OreEncumbrance.shown(OreEncumbrance.tenths(OreEncumbrance.massOf(stack))))
                .withStyle(ChatFormatting.GRAY));
    }
}
