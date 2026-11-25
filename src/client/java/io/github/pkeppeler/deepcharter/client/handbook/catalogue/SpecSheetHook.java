package io.github.pkeppeler.deepcharter.client.handbook.catalogue;

import java.util.function.Consumer;
import net.minecraft.world.item.Item;

/**
 * Where an item's spec sheet is opened from: the recipe panel's SPEC SHEET button and the pop-up the server asks for on a first craft
 * or buy both call {@link #open}. {@link SpecSheetOpener#register} sets the opener to the real one when the client starts; until then
 * nothing opens.
 */
public final class SpecSheetHook {
    private static volatile Consumer<Item> opener = item -> {};

    private SpecSheetHook() {}

    /** Sets what opens the spec sheet of an item to {@code opener}. */
    public static void set(Consumer<Item> opener) {
        SpecSheetHook.opener = opener;
    }

    static void open(Item item) {
        opener.accept(item);
    }
}
