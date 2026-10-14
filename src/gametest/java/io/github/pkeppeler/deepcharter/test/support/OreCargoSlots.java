package io.github.pkeppeler.deepcharter.test.support;

import io.github.pkeppeler.deepcharter.ore.OreCargoMenu;
import java.util.List;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/** The ore a cargo menu shows, read from its slots as the cargo screen draws them. */
public final class OreCargoSlots {
    private OreCargoSlots() {}

    /** The ore in the menu's slots, in slot order, empty slots left out. */
    public static List<ItemStack> shown(OreCargoMenu menu) {
        return menu.slots.stream()
                .map(Slot::getItem)
                .filter(stack -> !stack.isEmpty())
                .map(ItemStack::copy)
                .toList();
    }
}
