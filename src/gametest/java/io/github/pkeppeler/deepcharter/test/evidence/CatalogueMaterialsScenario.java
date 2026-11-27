package io.github.pkeppeler.deepcharter.test.evidence;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.pkeppeler.deepcharter.client.handbook.catalogue.CatalogueLayout;
import io.github.pkeppeler.deepcharter.client.handbook.catalogue.CataloguePanel;
import io.github.pkeppeler.deepcharter.client.handbook.catalogue.CatalogueScreens;
import io.github.pkeppeler.deepcharter.scanner.ScannerRegistry;
import io.github.pkeppeler.deepcharter.test.support.CatalogueScene;
import io.github.pkeppeler.deepcharter.test.support.ClientChecks;
import io.github.pkeppeler.deepcharter.test.support.ClientSession;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.RealInput;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

/**
 * Evidence scenario "m2-catalogue-materials" for #1378: the recipe console's total materials. Holding two iron and two copper, R on the
 * scanner unit shows its recipe ({@code materials-recipe}); M shows what it takes in all: the two iron it lacks as raw iron to smelt,
 * and the redstone, short, then the iron and copper held ({@code materials-total}); a row names its item on hover ({@code
 * materials-row}). With two raw iron and two redstone given, the console says the player has it all ({@code materials-all}), and M again
 * shows the recipe. Then, on the test fixtures, a ladder of seven materials fills the rows and the last says how many more
 * ({@code materials-more}); the wheel shows them ({@code materials-scrolled}).
 */
public class CatalogueMaterialsScenario extends EvidenceScenario {
    private static final int REST_FRAMES = 12;
    /** The advancement toasts of the first items held fade by now. */
    private static final int TOAST_TICKS = 60;

    @Override
    protected String name() {
        return "m2-catalogue-materials";
    }

    @Override
    protected void run(ClientGameTestContext context) {
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            context.runOnClient(client -> CatalogueScreens.resetState());
            CataloguePanel panel = CatalogueScene.teachScannerRecipe(context, singleplayer);
            context.waitTicks(TOAST_TICKS);
            ClientChecks.withHudHidden(context, () -> scanner(context, singleplayer, panel));
            context.setScreen(() -> null);
            CataloguePanel fixtures = CatalogueScene.knowFixturesHoldingIronAndCopper(context, singleplayer);
            context.waitTicks(TOAST_TICKS);
            ClientChecks.withHudHidden(context, () -> longList(context, fixtures));
            context.setScreen(() -> null);
        }
    }

    private void scanner(ClientGameTestContext context, TestSingleplayerContext singleplayer, CataloguePanel panel) {
        CatalogueScene.clearSearch(context, panel);
        context.waitTicks(2); // tick-wait: the catalogue lists what it knows before its cells are found
        CatalogueLayout layout = CatalogueScene.layout(context, panel);
        press(context, panel, ScannerRegistry.SCANNER_UNIT, InputConstants.KEY_R);
        CatalogueScene.parkCursor(context, layout);
        hold(context);
        screenshot(context, "materials-recipe");

        RealInput.key(context, InputConstants.KEY_M, 0);
        hold(context);
        screenshot(context, "materials-total");

        CatalogueScene.hover(context, CatalogueScene.firstMaterialsRow(layout));
        hold(context);
        screenshot(context, "materials-row");

        CatalogueScene.parkCursor(context, layout);
        singleplayer.getServer().runCommand("give @p minecraft:raw_iron 2");
        singleplayer.getServer().runCommand("give @p minecraft:redstone 2");
        ClientWait.until(
                context,
                "the raw iron and the redstone in the inventory",
                client -> ClientSession.player(client).getInventory().countItem(Items.RAW_IRON) == 2
                        && ClientSession.player(client).getInventory().countItem(Items.REDSTONE) == 2);
        hold(context);
        screenshot(context, "materials-all");

        RealInput.key(context, InputConstants.KEY_M, 0);
        hold(context);
        context.runOnClient(client -> panel.state().clear());
    }

    private void longList(ClientGameTestContext context, CataloguePanel panel) {
        CatalogueLayout layout = CatalogueScene.layout(context, panel);
        press(context, panel, Items.LADDER, InputConstants.KEY_R);
        // On the console, by its heading: the wheel there scrolls the rows, and no row is hovered.
        CatalogueScene.hover(context, CatalogueScene.consoleHeading(layout));
        RealInput.key(context, InputConstants.KEY_M, 0);
        hold(context);
        screenshot(context, "materials-more");
        for (int turn = 0; turn < 2; turn++) {
            RealInput.scroll(context, -1);
            hold(context);
        }
        screenshot(context, "materials-scrolled");
        context.runOnClient(client -> panel.state().clear());
    }

    /** Hovers {@code item}'s cell, rests on it, presses {@code key} and rests on what it shows. */
    private void press(ClientGameTestContext context, CataloguePanel panel, Item item, int key) {
        CatalogueLayout layout = CatalogueScene.layout(context, panel);
        int perPage = layout.shelf().perPage();
        int index = CatalogueScene.indexOf(context, panel, item);
        CatalogueScene.showPage(context, panel, perPage, index / perPage);
        CatalogueScene.hover(context, CatalogueScene.gridCell(layout, index % perPage));
        hold(context);
        RealInput.key(context, key, 0);
        hold(context);
    }

    private void hold(ClientGameTestContext context) {
        context.runOnClient(client -> client.gui.toastManager().clear());
        for (int frame = 0; frame < REST_FRAMES; frame++) {
            frame(context);
        }
    }
}
