package io.github.pkeppeler.deepcharter.client.handbook.catalogue;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import org.jspecify.annotations.Nullable;

/** STUB */
public final class RecipeConsoleScreen extends Screen {
    public RecipeConsoleScreen(@Nullable Screen parent, Item item, CatalogueState.Mode mode) {
        super(Component.empty());
    }

    public CatalogueLayout.@Nullable Console console() {
        return null;
    }
}
