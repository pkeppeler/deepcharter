package io.github.pkeppeler.deepcharter.test.evidence;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.pkeppeler.deepcharter.client.handbook.catalogue.CatalogueLayout;
import io.github.pkeppeler.deepcharter.client.handbook.catalogue.CataloguePanel;
import io.github.pkeppeler.deepcharter.client.handbook.catalogue.CatalogueScreens;
import io.github.pkeppeler.deepcharter.scanner.ScannerRegistry;
import io.github.pkeppeler.deepcharter.test.support.CatalogueScene;
import io.github.pkeppeler.deepcharter.test.support.ClientChecks;
import io.github.pkeppeler.deepcharter.test.support.RealInput;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

/**
 * Evidence scenario "m2-catalogue-history" for #1266 (b): the recipe history. R on the scanner unit, then on the iron ingot, then U on
 * the copper ({@code history-third}); Backspace goes back to the iron ({@code history-back-key}) and a click on the back knob under the
 * recipe goes back to the scanner unit ({@code history-back-knob}), where the knob, with nothing left, goes dull.
 */
public class CatalogueHistoryScenario extends EvidenceScenario {
    private static final int REST_FRAMES = 12;
    /** The advancement toasts of the first items held fade by now. */
    private static final int TOAST_TICKS = 60;

    @Override
    protected String name() {
        return "m2-catalogue-history";
    }

    @Override
    protected void run(ClientGameTestContext context) {
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            context.runOnClient(client -> CatalogueScreens.resetState());
            CataloguePanel panel = CatalogueScene.teachScannerRecipe(context, singleplayer);
            context.waitTicks(TOAST_TICKS);
            ClientChecks.withHudHidden(context, () -> record(context, panel));
            context.setScreen(() -> null);
        }
    }

    private void record(ClientGameTestContext context, CataloguePanel panel) {
        CatalogueScene.clearSearch(context, panel);
        context.waitTicks(2); // tick-wait: the catalogue lists what it knows before its cells are found
        CatalogueLayout layout = CatalogueScene.layout(context, panel);
        press(context, panel, layout, ScannerRegistry.SCANNER_UNIT, InputConstants.KEY_R);
        press(context, panel, layout, Items.IRON_INGOT, InputConstants.KEY_R);
        press(context, panel, layout, Items.COPPER_INGOT, InputConstants.KEY_U);
        screenshot(context, "history-third");

        CatalogueScene.parkCursor(context, layout);
        RealInput.key(context, InputConstants.KEY_BACKSPACE, 0);
        hold(context);
        screenshot(context, "history-back-key");

        CatalogueScene.hover(context, layout.card().back());
        hold(context);
        RealInput.click(context, InputConstants.MOUSE_BUTTON_LEFT);
        hold(context);
        screenshot(context, "history-back-knob");
        context.runOnClient(client -> panel.state().clear());
    }

    /** Hovers {@code item}'s cell, rests on it, presses {@code key} and rests on what it shows. */
    private void press(
            ClientGameTestContext context, CataloguePanel panel, CatalogueLayout layout, Item item, int key) {
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
