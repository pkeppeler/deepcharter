package io.github.pkeppeler.deepcharter.test.evidence;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.pkeppeler.deepcharter.client.handbook.catalogue.CatalogueLayout;
import io.github.pkeppeler.deepcharter.client.handbook.catalogue.CataloguePanel;
import io.github.pkeppeler.deepcharter.scanner.ScannerRegistry;
import io.github.pkeppeler.deepcharter.test.support.CatalogueScene;
import io.github.pkeppeler.deepcharter.test.support.ClientSession;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Evidence scenario "m2-parts-catalogue" for #846: the inventory with the parts catalogue beside it and the recipe of the scanner unit
 * shown (R on its cell, with an iron and a copper in hand so the have/need rows differ), then a search typed. The recipes are the ones the
 * server taught from what the player holds (ADR 0070), not a fixture.
 */
public class PartsCatalogueScenario extends EvidenceScenario {
    private static final int HOLD_FRAMES = 4;
    private static final int LEFT_MOUSE = 0;

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
            context.waitTicks(2);

            CatalogueLayout layout = CatalogueScene.layout(context, panel);
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
            context.setScreen(() -> null);
        }
    }

    private void hold(ClientGameTestContext context) {
        for (int frame = 0; frame < HOLD_FRAMES; frame++) {
            frame(context);
        }
    }
}
