package io.github.pkeppeler.deepcharter.test.evidence;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.pkeppeler.deepcharter.client.handbook.catalogue.CatalogueFavourites;
import io.github.pkeppeler.deepcharter.client.handbook.catalogue.CatalogueLayout;
import io.github.pkeppeler.deepcharter.client.handbook.catalogue.CataloguePanel;
import io.github.pkeppeler.deepcharter.scanner.ScannerRegistry;
import io.github.pkeppeler.deepcharter.test.support.CatalogueScene;
import io.github.pkeppeler.deepcharter.test.support.ClientChecks;
import io.github.pkeppeler.deepcharter.test.support.ClientSession;
import io.github.pkeppeler.deepcharter.test.support.RealInput;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.world.item.Item;

/**
 * Evidence scenario "m2-catalogue-favourites" for #1266 (c): the favourites row empty, with its hint ({@code favourites-empty}); A on
 * the scanner unit stars it ({@code favourites-key}); its recipe shows a full star, and a click on it takes the star off and the hint
 * comes back ({@code favourites-unstarred}); a second click stars it again ({@code favourites-star}).
 */
public class CatalogueFavouritesScenario extends EvidenceScenario {
    private static final int REST_FRAMES = 12;
    /** The advancement toasts of the first items held fade by now. */
    private static final int TOAST_TICKS = 60;

    @Override
    protected String name() {
        return "m2-catalogue-favourites";
    }

    @Override
    protected void run(ClientGameTestContext context) {
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            CataloguePanel panel = CatalogueScene.teachScannerRecipe(context, singleplayer);
            unstarAll(context);
            context.waitTicks(TOAST_TICKS);
            ClientChecks.withHudHidden(context, () -> record(context, panel));
            unstarAll(context);
            context.setScreen(() -> null);
        }
    }

    /** Takes off every star a run left, so the row starts empty. */
    private static void unstarAll(ClientGameTestContext context) {
        context.runOnClient(client -> {
            var player = ClientSession.player(client).getUUID();
            for (Item left : CatalogueFavourites.shared().items(player)) {
                CatalogueFavourites.shared().toggle(player, left);
            }
        });
    }

    private void record(ClientGameTestContext context, CataloguePanel panel) {
        CatalogueScene.clearSearch(context, panel);
        context.waitTicks(2); // tick-wait: the catalogue lists what it knows before its cells are found
        CatalogueLayout layout = CatalogueScene.layout(context, panel);
        CatalogueScene.parkCursor(context, layout);
        hold(context);
        screenshot(context, "favourites-empty");

        int perPage = layout.shelf().perPage();
        int index = CatalogueScene.indexOf(context, panel, ScannerRegistry.SCANNER_UNIT);
        CatalogueScene.showPage(context, panel, perPage, index / perPage);
        CatalogueScene.hover(context, CatalogueScene.gridCell(layout, index % perPage));
        hold(context);
        RealInput.key(context, InputConstants.KEY_A, 0);
        hold(context);
        screenshot(context, "favourites-key");

        RealInput.key(context, InputConstants.KEY_R, 0);
        hold(context);
        CatalogueScene.hover(context, layout.card().star());
        hold(context);
        RealInput.click(context, InputConstants.MOUSE_BUTTON_LEFT);
        hold(context);
        screenshot(context, "favourites-unstarred");
        RealInput.click(context, InputConstants.MOUSE_BUTTON_LEFT);
        hold(context);
        screenshot(context, "favourites-star");
        context.runOnClient(client -> panel.state().clear());
    }

    private void hold(ClientGameTestContext context) {
        context.runOnClient(client -> client.gui.toastManager().clear());
        for (int frame = 0; frame < REST_FRAMES; frame++) {
            frame(context);
        }
    }
}
