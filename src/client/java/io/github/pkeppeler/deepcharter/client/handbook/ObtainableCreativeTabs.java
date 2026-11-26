package io.github.pkeppeler.deepcharter.client.handbook;

import io.github.pkeppeler.deepcharter.handbook.ObtainableItems;
import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;

/**
 * Takes the vanilla items the campaign world does not yield out of every creative tab and out of the search (ADR 0093).
 */
public final class ObtainableCreativeTabs {
    private ObtainableCreativeTabs() {}

    public static void init() {
        CreativeModeTabEvents.MODIFY_OUTPUT_ALL.register((tab, output) -> {
            output.getDisplayStacks().removeIf(stack -> !ObtainableItems.isObtainable(stack));
            output.getSearchTabStacks().removeIf(stack -> !ObtainableItems.isObtainable(stack));
        });
    }
}
