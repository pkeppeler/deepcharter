package io.github.pkeppeler.deepcharter.test.evidence;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.pkeppeler.deepcharter.client.handbook.catalogue.CatalogueLayout;
import io.github.pkeppeler.deepcharter.client.handbook.catalogue.CataloguePanel;
import io.github.pkeppeler.deepcharter.test.support.CatalogueScene;
import io.github.pkeppeler.deepcharter.test.support.ClientChecks;
import io.github.pkeppeler.deepcharter.test.support.ClientSession;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.RealInput;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Evidence scenario "m3-catalogue-planks" for #1263: the player holds the oak planks a salvage crate drops, which
 * teaches every recipe that takes any planks; the parts catalogue shows the chest's recipe (still
 * {@code chest-recipe}), then is searched for "planks" (still {@code planks-search}). Before #1263 the search listed
 * every wood in the game; after it, only the woods the campaign world yields.
 */
public class CataloguePlanksScenario extends EvidenceScenario {
    private static final int HOLD_FRAMES = 4;
    private static final int LEFT_MOUSE = 0;
    /** The toasts of the first items held, which sit over the top of the catalogue, fade by now. */
    private static final int TOAST_TICKS = 60;

    @Override
    protected String name() {
        return "m3-catalogue-planks";
    }

    @Override
    protected void run(ClientGameTestContext context) {
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            ClientWait.until(context, "the player", client -> client.player != null && client.level != null);
            singleplayer.getServer().runCommand("give @p minecraft:oak_planks 8");
            ClientWait.until(
                    context,
                    "the oak planks in the inventory",
                    client -> ClientSession.player(client).getInventory().contains(new ItemStack(Items.OAK_PLANKS)));
            CatalogueScene.openInventory(context);
            CataloguePanel panel = CatalogueScene.panel(context);
            ClientWait.until(
                    context,
                    "the chest's recipe, taught by the planks",
                    client -> !panel.catalogue().recipesFor(Items.CHEST).isEmpty());
            context.waitTicks(TOAST_TICKS); // tick-wait: the toasts of the first items held fade
            ClientChecks.withHudHidden(context, () -> recordStills(context, panel));
            context.setScreen(() -> null);
        }
    }

    /** The chest's recipe first: the search is typed last, as it keeps the keys (R would type into it). */
    private void recordStills(ClientGameTestContext context, CataloguePanel panel) {
        CatalogueScene.clearSearch(context, panel);
        context.waitTicks(2); // tick-wait: the list follows the cleared search
        CatalogueLayout layout = CatalogueScene.layout(context, panel);
        int perPage = layout.shelf().perPage();
        int index = CatalogueScene.indexOf(context, panel, Items.CHEST);
        CatalogueScene.showPage(context, panel, perPage, index / perPage);
        CatalogueScene.hover(context, CatalogueScene.gridCell(layout, index % perPage));
        context.getInput().pressKey(InputConstants.KEY_R);
        ClientWait.until(context, "the recipe console", client -> panel.showsRecipePanel());
        hold(context);
        screenshot(context, "chest-recipe");

        CatalogueScene.clearSearch(context, panel);
        context.waitTicks(2); // tick-wait: the list follows the cleared search
        CatalogueScene.hover(context, layout.shelf().search());
        context.getInput().pressMouse(LEFT_MOUSE);
        RealInput.type(context, "planks");
        context.waitTicks(2); // tick-wait: the list follows the typed search
        CatalogueScene.parkCursor(context, layout);
        hold(context);
        screenshot(context, "planks-search");
        context.getInput().pressKey(InputConstants.KEY_ESCAPE);
    }

    private void hold(ClientGameTestContext context) {
        context.runOnClient(client -> client.gui.toastManager().clear());
        for (int frame = 0; frame < HOLD_FRAMES; frame++) {
            frame(context);
        }
    }
}
