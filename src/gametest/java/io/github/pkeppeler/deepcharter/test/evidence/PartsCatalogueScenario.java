package io.github.pkeppeler.deepcharter.test.evidence;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.pkeppeler.deepcharter.client.handbook.catalogue.CatalogueFavourites;
import io.github.pkeppeler.deepcharter.client.handbook.catalogue.CatalogueLayout;
import io.github.pkeppeler.deepcharter.client.handbook.catalogue.CataloguePanel;
import io.github.pkeppeler.deepcharter.scanner.ScannerRegistry;
import io.github.pkeppeler.deepcharter.test.support.CatalogueScene;
import io.github.pkeppeler.deepcharter.test.support.ClientChecks;
import io.github.pkeppeler.deepcharter.test.support.ClientSession;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.RealInput;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

/**
 * Evidence scenario "m2-parts-catalogue" for #846: the inventory with the parts catalogue, five items starred, and the recipe of the
 * scanner unit shown (R on its cell, with two iron and two copper in hand so the have/need rows differ), a hover and then a search typed
 * (stills {@code console-recipe}, {@code console-hover} and {@code console-search}). The recipes are the ones the server taught from
 * what the player holds (ADR 0070), not a fixture.
 *
 * <p>Then #1258: a long name whole on three lines of the name strip ({@code console-long-name}), the "craftable now" switch with its
 * count and the scanner unit, made only at a crafting table, marked with a table ({@code console-craftable}), and the same state after
 * the inventory was closed and opened again ({@code console-remembered}).
 */
public class PartsCatalogueScenario extends EvidenceScenario {
    private static final int HOLD_FRAMES = 4;
    /** An item whose name is longer than one line of the catalogue, and which iron makes. */
    private static final Item LONG_NAMED = Items.HEAVY_WEIGHTED_PRESSURE_PLATE;

    private static final int FAVOURITES = 5;
    /** The advancement toasts of the first items held, which sit over the top of the catalogue, fade by now. */
    private static final int TOAST_TICKS = 60;

    @Override
    protected String name() {
        return "m2-parts-catalogue";
    }

    @Override
    protected void run(ClientGameTestContext context) {
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            CataloguePanel panel = CatalogueScene.teachScannerRecipe(context, singleplayer);
            context.waitTicks(TOAST_TICKS);

            ClientChecks.withHudHidden(context, () -> {
                recordStills(context, panel);
                recordNamesAndState(context, singleplayer, panel);
            });
            context.setScreen(() -> null);
        }
    }

    /** The three stills: the recipe of the scanner unit looked up, a hover, and a search typed. */
    private void recordStills(ClientGameTestContext context, CataloguePanel panel) {
        CatalogueScene.clearSearch(context, panel);
        context.waitTicks(2);
        CatalogueLayout layout = CatalogueScene.layout(context, panel);
        int perPage = layout.shelf().perPage();
        int index = CatalogueScene.indexOf(context, panel, ScannerRegistry.SCANNER_UNIT);
        CatalogueScene.showPage(context, panel, perPage, index / perPage);
        star(context, panel, layout, index / perPage);
        int cell = index % perPage;
        CatalogueScene.hover(context, CatalogueScene.gridCell(layout, cell));
        context.getInput().pressKey(InputConstants.KEY_R);
        context.waitTicks(2);
        hold(context);
        screenshot(context, "console-recipe");

        CatalogueScene.hover(context, CatalogueScene.gridCell(layout, cell > 0 ? cell - 1 : cell + 1));
        hold(context);
        screenshot(context, "console-hover");

        CatalogueScene.hover(context, layout.shelf().search());
        context.getInput().pressMouse(InputConstants.MOUSE_BUTTON_LEFT);
        RealInput.type(context, "iron");
        context.waitTicks(2);
        hold(context);
        screenshot(context, "console-search");
        context.getInput().pressKey(InputConstants.KEY_ESCAPE);
    }

    /** The stills of #1258: a long name whole, "craftable now" with a 3 x 3 recipe, and the catalogue as the player left it. */
    private void recordNamesAndState(
            ClientGameTestContext context, TestSingleplayerContext singleplayer, CataloguePanel panel) {
        singleplayer.getServer().runCommand("clear @p");
        singleplayer.getServer().runCommand("give @p minecraft:iron_ingot 8");
        singleplayer.getServer().runCommand("give @p minecraft:copper_ingot 2");
        singleplayer.getServer().runCommand("give @p minecraft:redstone 2");
        ClientWait.until(
                context,
                "the recipes of the iron, taught by what the player holds",
                client -> !panel.catalogue().recipesFor(LONG_NAMED).isEmpty()
                        && !panel.catalogue()
                                .recipesFor(ScannerRegistry.SCANNER_UNIT)
                                .isEmpty()
                        && panel.craftableCount() > 0);
        CatalogueScene.clearSearch(context, panel);
        context.waitTicks(2);
        CatalogueLayout layout = CatalogueScene.layout(context, panel);
        int perPage = layout.shelf().perPage();

        CatalogueScene.hover(context, layout.shelf().craftable());
        context.getInput().pressMouse(InputConstants.MOUSE_BUTTON_LEFT);
        context.waitTicks(2);
        int plate = CatalogueScene.indexOf(context, panel, LONG_NAMED);
        CatalogueScene.showPage(context, panel, perPage, plate / perPage);
        CatalogueScene.hover(context, CatalogueScene.gridCell(layout, plate % perPage));
        ClientChecks.require(
                context.computeOnClient(client -> panel.nameLines(LONG_NAMED).size()) > 1,
                "the long name wraps, or the still shows nothing about it");
        hold(context);
        screenshot(context, "console-long-name");

        context.runOnClient(client -> panel.state().setQuery("unit"));
        context.waitTicks(2);
        int scanner = CatalogueScene.indexOf(context, panel, ScannerRegistry.SCANNER_UNIT);
        CatalogueScene.showPage(context, panel, perPage, scanner / perPage);
        CatalogueScene.hover(context, CatalogueScene.gridCell(layout, scanner % perPage));
        context.getInput().pressKey(InputConstants.KEY_R);
        context.waitTicks(2);
        CatalogueScene.parkCursor(context, layout);
        hold(context);
        screenshot(context, "console-craftable");

        context.setScreen(() -> null);
        CatalogueScene.openInventory(context);
        context.waitTicks(2);
        hold(context);
        screenshot(context, "console-remembered");
        CataloguePanel again = CatalogueScene.panel(context);
        context.runOnClient(client -> {
            again.state().setQuery("");
            again.state().clear();
            again.state().toggleCraftableOnly();
        });
    }

    /** Stars the scanner unit and four more of {@code page}, the ones not starred already (a run keeps its favourites). */
    private void star(ClientGameTestContext context, CataloguePanel panel, CatalogueLayout layout, int page) {
        UUID player =
                context.computeOnClient(client -> ClientSession.player(client).getUUID());
        int perPage = layout.shelf().perPage();
        List<Item> shown = context.computeOnClient(client -> panel.shownItems());
        List<Item> onPage = shown.subList(page * perPage, Math.min(shown.size(), (page + 1) * perPage));
        List<Item> starred = new ArrayList<>(List.of(ScannerRegistry.SCANNER_UNIT));
        for (Item item : onPage) {
            if (starred.size() < FAVOURITES && !starred.contains(item)) {
                starred.add(item);
            }
        }
        for (Item item : starred) {
            if (onPage.contains(item)
                    && !CatalogueFavourites.shared().items(player).contains(item)) {
                CatalogueScene.hover(context, CatalogueScene.gridCell(layout, onPage.indexOf(item)));
                context.getInput().pressKey(InputConstants.KEY_A);
                context.waitTicks(1);
            }
        }
    }

    private void hold(ClientGameTestContext context) {
        context.runOnClient(client -> client.gui.toastManager().clear());
        for (int frame = 0; frame < HOLD_FRAMES; frame++) {
            frame(context);
        }
    }
}
