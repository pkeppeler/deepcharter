package io.github.pkeppeler.deepcharter.client.handbook;

import java.util.function.Consumer;
import net.minecraft.resources.Identifier;

/**
 * Where a click on an item of a step's index card goes: its recipe, one click away. The parts catalogue (#846) is that view; it
 * wires itself here with {@link #handleWith}. Until it does a click does nothing.
 */
public final class ItemRecipeLink {
    private static Consumer<Identifier> handler = item -> {};

    private ItemRecipeLink() {}

    // test-seam: the hook #846 (parts catalogue) wires; nothing in this build sets it, a test proves a click reaches
    // it, and #846 deletes this marker
    public static void handleWith(Consumer<Identifier> newHandler) {
        handler = newHandler;
    }

    static void open(Identifier item) {
        handler.accept(item);
    }
}
