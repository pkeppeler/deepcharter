package io.github.pkeppeler.deepcharter.test.support;

import io.github.pkeppeler.deepcharter.client.handbook.catalogue.CatalogueLayout;
import io.github.pkeppeler.deepcharter.client.handbook.catalogue.CatalogueLayout.Rect;
import io.github.pkeppeler.deepcharter.client.handbook.catalogue.CataloguePanel;
import io.github.pkeppeler.deepcharter.client.handbook.catalogue.CatalogueScreens;
import io.github.pkeppeler.deepcharter.client.theme.CatalogueLook;
import java.util.List;
import java.util.Objects;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.world.entity.player.StackedItemContents;
import net.minecraft.world.item.Item;

/** Opens the inventory and finds and hovers the parts catalogue's cells, for the catalogue's client test and its evidence scenario. */
public final class CatalogueScene {
    private CatalogueScene() {}

    /** Opens the player's inventory and waits until it is drawn. */
    public static void openInventory(ClientGameTestContext context) {
        context.setScreen(() -> new InventoryScreen(ClientSession.player(Minecraft.getInstance())));
        ClientWait.screen(context, InventoryScreen.class);
        context.waitTicks(3);
    }

    /** The catalogue of the screen the client shows. */
    public static CataloguePanel panel(ClientGameTestContext context) {
        return context.computeOnClient(client -> Objects.requireNonNull(
                CatalogueScreens.panelOf(Objects.requireNonNull(client.gui.screen(), "a screen is open")),
                "the open screen has a catalogue"));
    }

    /** Where the panels are, which must be shown (a window wide enough). */
    public static CatalogueLayout layout(ClientGameTestContext context, CataloguePanel panel) {
        return Objects.requireNonNull(
                context.computeOnClient(client -> panel.layout()), "the panels are laid out at the window's size");
    }

    /** The cell of grid position {@code index} on the page shown. */
    public static Rect gridCell(CatalogueLayout layout, int index) {
        CatalogueLayout.Shelf shelf = layout.shelf();
        CatalogueLook look = CatalogueLook.current();
        return new Rect(
                shelf.gridX() + (index % shelf.columns()) * look.gridCell(),
                shelf.gridY() + (index / shelf.columns()) * look.gridCell(),
                look.slotSize(),
                look.slotSize());
    }

    /** The position of {@code item} in the catalogue's list on the first page, with nothing searched or filtered. */
    public static int indexOf(ClientGameTestContext context, CataloguePanel panel, Item item) {
        List<Item> shown =
                context.computeOnClient(client -> panel.state().shown(panel.catalogue(), new StackedItemContents()));
        int index = shown.indexOf(item);
        if (index < 0) {
            throw new AssertionError("the catalogue lists " + shown + ", not " + item);
        }
        return index;
    }

    /** Moves the cursor onto the centre of {@code cell}. */
    public static void hover(ClientGameTestContext context, Rect cell) {
        ClientCursor.moveTo(context, cell.x() + cell.width() / 2.0, cell.y() + cell.height() / 2.0);
    }
}
