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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.world.entity.player.StackedItemContents;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Evidence scenario "m2-parts-catalogue" for #846: the inventory with the parts catalogue beside it, five items starred, and the recipe
 * of the scanner unit shown (R on its cell, with two iron and two copper in hand so the have/need rows differ), then a search typed. The
 * recipes are the ones the server taught from what the player holds (ADR 0070), not a fixture.
 */
public class PartsCatalogueScenario extends EvidenceScenario {
    private static final int HOLD_FRAMES = 4;
    private static final int LEFT_MOUSE = 0;
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
            ClientWait.until(context, "the player", client -> client.player != null && client.level != null);
            singleplayer.getServer().runCommand("give @p minecraft:iron_ingot 2");
            singleplayer.getServer().runCommand("give @p minecraft:copper_ingot 2");
            ClientWait.until(
                    context,
                    "the iron and the copper in the inventory",
                    client -> ClientSession.player(client).getInventory().contains(new ItemStack(Items.IRON_INGOT, 2))
                            && ClientSession.player(client)
                                    .getInventory()
                                    .contains(new ItemStack(Items.COPPER_INGOT, 2)));
            CatalogueScene.openInventory(context);
            CataloguePanel panel = CatalogueScene.panel(context);
            ClientWait.until(
                    context,
                    "the scanner unit's recipe, taught by what the player holds",
                    client -> !panel.catalogue()
                            .recipesFor(ScannerRegistry.SCANNER_UNIT)
                            .isEmpty());
            context.waitTicks(TOAST_TICKS);

            ClientChecks.withHudHidden(context, () -> {
                CatalogueLayout layout = CatalogueScene.layout(context, panel);
                star(context, panel, layout);
                int index = CatalogueScene.indexOf(context, panel, ScannerRegistry.SCANNER_UNIT);
                CatalogueScene.hover(context, CatalogueScene.gridCell(layout, index));
                context.getInput().pressKey(InputConstants.KEY_R);
                context.waitTicks(2);
                hold(context);
                screenshot(context, "catalogue-recipe");

                CatalogueScene.hover(context, layout.shelf().search());
                context.getInput().pressMouse(LEFT_MOUSE);
                context.getInput().typeChars("iron");
                context.waitTicks(2);
                hold(context);
                screenshot(context, "catalogue-search");
                context.getInput().pressKey(InputConstants.KEY_ESCAPE);
            });
            context.setScreen(() -> null);
        }
    }

    /** Stars the scanner unit and four more of the first page, the ones not starred already (a run keeps its favourites). */
    private void star(ClientGameTestContext context, CataloguePanel panel, CatalogueLayout layout) {
        UUID player =
                context.computeOnClient(client -> ClientSession.player(client).getUUID());
        List<Item> page = context.computeOnClient(
                client -> panel.state().shown(panel.catalogue(), new StackedItemContents()).stream()
                        .limit(layout.shelf().perPage())
                        .toList());
        List<Item> starred = new ArrayList<>(List.of(ScannerRegistry.SCANNER_UNIT));
        for (Item item : page) {
            if (starred.size() < FAVOURITES && !starred.contains(item)) {
                starred.add(item);
            }
        }
        for (Item item : starred) {
            if (page.contains(item)
                    && !CatalogueFavourites.shared().items(player).contains(item)) {
                CatalogueScene.hover(context, CatalogueScene.gridCell(layout, page.indexOf(item)));
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
