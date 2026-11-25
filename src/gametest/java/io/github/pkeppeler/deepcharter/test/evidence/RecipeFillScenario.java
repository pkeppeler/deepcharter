package io.github.pkeppeler.deepcharter.test.evidence;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.pkeppeler.deepcharter.client.handbook.catalogue.CatalogueLayout;
import io.github.pkeppeler.deepcharter.client.handbook.catalogue.CataloguePanel;
import io.github.pkeppeler.deepcharter.test.support.CatalogueScene;
import io.github.pkeppeler.deepcharter.test.support.ClientChecks;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.FillScenes;
import io.github.pkeppeler.deepcharter.test.support.RealInput;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.world.item.Items;

/**
 * Evidence scenario "m2-recipe-fill" for #1259: the recipe card's fill switch. The iron block's recipe (3 x 3) with nine iron held: on
 * the inventory's 2 x 2 grid the switch says it needs a table (still {@code needs-table}); at a crafting table with three iron held it
 * shows the recipe as a ghost, the six slots the iron cannot fill marked (still {@code ghost}); with the rest of the iron in hand, one
 * press moves the iron into the grid and the iron block appears (still {@code filled}). The table's menu is the server's, so the fill is
 * the real recipe-book placement.
 */
public class RecipeFillScenario extends EvidenceScenario {
    private static final int FULL_HAND = 9;
    private static final int SHORT_HAND = 3;
    /** Frames of the cursor on a control before the press, and of the result after it. */
    private static final int REST_FRAMES = 12;
    /** The advancement toasts of the first items held, which sit over the top of the catalogue, fade by now. */
    private static final int TOAST_TICKS = 60;

    @Override
    protected String name() {
        return "m2-recipe-fill";
    }

    @Override
    protected void run(ClientGameTestContext context) {
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            ClientWait.until(context, "the player", client -> client.player != null && client.level != null);
            singleplayer.getServer().runCommand("give @p minecraft:iron_ingot " + FULL_HAND);
            CatalogueScene.openInventory(context);
            CataloguePanel known = CatalogueScene.panel(context);
            ClientWait.until(
                    context,
                    "the iron block's recipe, taught by the iron",
                    client -> FillScenes.crafting(known, Items.IRON_BLOCK) != null);
            context.waitTicks(TOAST_TICKS);
            ClientChecks.withHudHidden(context, () -> recordStills(context, singleplayer));
            context.setScreen(() -> null);
        }
    }

    private void recordStills(ClientGameTestContext context, TestSingleplayerContext singleplayer) {
        CataloguePanel pocket = FillScenes.inventoryHolding(context, singleplayer, FULL_HAND);
        FillScenes.showCrafting(context, pocket, Items.IRON_BLOCK);
        press(context, pocket);
        screenshot(context, "needs-table");

        CataloguePanel table = FillScenes.tableHolding(context, singleplayer, SHORT_HAND);
        FillScenes.showCrafting(context, table, Items.IRON_BLOCK);
        press(context, table);
        screenshot(context, "ghost");

        singleplayer.getServer().runCommand("give @p minecraft:iron_ingot " + (FULL_HAND - SHORT_HAND));
        ClientWait.until(
                context,
                "the rest of the iron, so the switch offers the fill",
                client -> table.fillLegend().equals("FILL GRID"));
        press(context, table);
        ClientWait.until(
                context,
                "the iron block in the result",
                client -> client.player != null
                        && client.player.containerMenu.getSlot(0).getItem().is(Items.IRON_BLOCK));
        CatalogueScene.parkCursor(context, CatalogueScene.layout(context, table));
        hold(context);
        screenshot(context, "filled");
    }

    /** Rests the cursor on the fill switch a while, as a player reads it, then presses it and shows what follows. */
    private void press(ClientGameTestContext context, CataloguePanel panel) {
        CatalogueLayout layout = CatalogueScene.layout(context, panel);
        CatalogueScene.hover(context, layout.card().fill());
        hold(context);
        RealInput.click(context, InputConstants.MOUSE_BUTTON_LEFT);
        context.waitTicks(2);
        CatalogueScene.parkCursor(context, layout);
        hold(context);
    }

    private void hold(ClientGameTestContext context) {
        context.runOnClient(client -> client.gui.toastManager().clear());
        for (int frame = 0; frame < REST_FRAMES; frame++) {
            frame(context);
        }
    }
}
