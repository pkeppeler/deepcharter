package io.github.pkeppeler.deepcharter.test.evidence;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.pkeppeler.deepcharter.client.handbook.catalogue.CatalogueLayout.Rect;
import io.github.pkeppeler.deepcharter.client.handbook.catalogue.CataloguePanel;
import io.github.pkeppeler.deepcharter.client.handbook.catalogue.CatalogueToggleButton;
import io.github.pkeppeler.deepcharter.test.support.CatalogueScene;
import io.github.pkeppeler.deepcharter.test.support.ClientChecks;
import java.util.Objects;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.screen.v1.Screens;

/**
 * Evidence scenario "m2-catalogue-button" for #1282: the inventory with the parts catalogue's show/hide button under the cursor and its
 * tooltip ({@code button-tooltip}), after a click with the catalogue put away ({@code catalogue-hidden}) and after a second click, which
 * brings it back ({@code catalogue-shown}).
 */
public class CatalogueButtonScenario extends EvidenceScenario {
    private static final int HOLD_FRAMES = 4;
    /** A vanilla button answers to the game's left button. */
    private static final int LEFT_MOUSE = InputConstants.MOUSE_BUTTON_LEFT;
    /** The advancement toasts of the first items held, which sit over the top of the catalogue, fade by now. */
    private static final int TOAST_TICKS = 60;
    /** Frames the cursor rests on the button, so the tooltip's delay has passed. */
    private static final int TOOLTIP_FRAMES = 40;

    @Override
    protected String name() {
        return "m2-catalogue-button";
    }

    @Override
    protected void run(ClientGameTestContext context) {
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            CataloguePanel panel = CatalogueScene.teachScannerRecipe(context, singleplayer);
            context.waitTicks(TOAST_TICKS);
            ClientChecks.withHudHidden(context, () -> {
                Rect button = context.computeOnClient(client -> {
                    CatalogueToggleButton found =
                            Screens.getWidgets(Objects.requireNonNull(client.gui.screen(), "the inventory is open"))
                                    .stream()
                                    .filter(CatalogueToggleButton.class::isInstance)
                                    .map(CatalogueToggleButton.class::cast)
                                    .findFirst()
                                    .orElseThrow();
                    return new Rect(found.getX(), found.getY(), found.getWidth(), found.getHeight());
                });
                CatalogueScene.clearSearch(context, panel);
                CatalogueScene.hover(context, button);
                for (int frame = 0; frame < TOOLTIP_FRAMES; frame++) {
                    frame(context);
                }
                screenshot(context, "button-tooltip");

                context.getInput().pressMouse(LEFT_MOUSE);
                context.waitTicks(2);
                hold(context);
                screenshot(context, "catalogue-hidden");

                context.getInput().pressMouse(LEFT_MOUSE);
                context.waitTicks(2);
                CatalogueScene.parkCursor(context, CatalogueScene.layout(context, panel));
                hold(context);
                screenshot(context, "catalogue-shown");
            });
            context.setScreen(() -> null);
        }
    }

    private void hold(ClientGameTestContext context) {
        context.runOnClient(client -> client.gui.toastManager().clear());
        for (int frame = 0; frame < HOLD_FRAMES; frame++) {
            frame(context);
        }
    }
}
