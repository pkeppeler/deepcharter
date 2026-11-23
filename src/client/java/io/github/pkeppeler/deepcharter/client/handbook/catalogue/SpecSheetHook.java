package io.github.pkeppeler.deepcharter.client.handbook.catalogue;

import java.util.function.Consumer;
import net.minecraft.world.item.Item;

/**
 * Where the recipe panel's SPEC SHEET button goes. The spec sheet itself is #848: it sets {@link #open} to the screen it builds. Until
 * then the button is drawn, as in the mockup, and does nothing.
 */
public final class SpecSheetHook {
    private static volatile Consumer<Item> opener = item -> {};

    private SpecSheetHook() {}

    /** Makes the SPEC SHEET button open the spec sheet of an item through {@code opener}. */
    // test-seam: #848 registers its spec sheet screen here; the catalogue test registers a probe
    public static void set(Consumer<Item> opener) {
        SpecSheetHook.opener = opener;
    }

    static void open(Item item) {
        opener.accept(item);
    }
}
