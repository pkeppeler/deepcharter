package io.github.pkeppeler.deepcharter.test.evidence;

import io.github.pkeppeler.deepcharter.client.charter.ClientCharter;
import io.github.pkeppeler.deepcharter.client.theme.PanelLook;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.TerminalPanelScene;
import io.github.pkeppeler.deepcharter.test.support.TerminalPanelScene.Scene;
import io.github.pkeppeler.deepcharter.test.support.TerminalPanelScreens;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import org.jspecify.annotations.Nullable;

/**
 * Evidence scenario "crt-corner" for #907: the corner of the bezel on the CRT terminals. Stills: {@code crt-corner-<screen>} for the upgrade terminal,
 * the hangar console and the contract terminal (the whole screen), and for the upgrade terminal the top-left and bottom-right corners of the
 * glass scaled up with nearest-neighbour ({@code crt-corner-top-left-zoom}, {@code crt-corner-bottom-right-zoom}) so the pixels read.
 * The crops are placed from the panel's own glass insets, not from a fixed spot.
 *
 * <p>#1012 adds the processor, repair station, fuel pump and generic terminal to the whole-screen stills, and for every screen the top-left corner
 * zoomed ({@code crt-corner-title-zoom-<screen>}): the title's first stem sits there, and the glass used to shade it.
 */
public class CrtCornerScenario extends EvidenceScenario {
    private static final int HOLD_FRAMES = 4;
    private static final int TICKS_PER_FRAME = 3;
    /** Where the cursor is parked: well outside the window, so that no button is hovered. */
    private static final double OFF_SCREEN = -10_000;
    /** The side of a corner crop, in GUI pixels: the arc and a margin of bezel and glass round it. */
    private static final int CROP = 40;
    /** How far the crop reaches out past the glass edge, in GUI pixels. */
    private static final int REACH_OUT = 10;

    @Override
    protected String name() {
        return "crt-corner";
    }

    @Override
    protected void run(ClientGameTestContext context) {
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            Scene scene = singleplayer.getServer().computeOnServer(TerminalPanelScene::setUp);
            ClientWait.until(
                    context,
                    "the charter's account",
                    client -> ClientCharter.view()
                            .map(charter -> charter.balance() == TerminalPanelScene.ACCOUNT)
                            .orElse(false),
                    client -> "charter " + ClientCharter.view());
            for (String screen : TerminalPanelScreens.SCENE_SCREENS) {
                shoot(context, screen, scene, null, screen.equals("upgrade"));
            }
        }
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            BlockPos contract = singleplayer.getServer().computeOnServer(TerminalPanelScene::setUpContract);
            shoot(context, TerminalPanelScreens.CONTRACT, null, contract, false);
        }
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            BlockPos offline = singleplayer.getServer().computeOnServer(TerminalPanelScene::setUpOffline);
            shoot(context, TerminalPanelScreens.GENERIC, null, offline, false);
        }
    }

    private void shoot(
            ClientGameTestContext context,
            String screen,
            @Nullable Scene scene,
            @Nullable BlockPos elsewhere,
            boolean corners) {
        Screen open = TerminalPanelScreens.open(context, screen, scene, elsewhere);
        context.getInput().setCursorPos(OFF_SCREEN, OFF_SCREEN);
        for (int i = 0; i < HOLD_FRAMES; i++) {
            context.waitTicks(TICKS_PER_FRAME);
            frame(context);
        }
        String still = "crt-corner-" + screen;
        screenshot(context, still);
        // #1012: the title starts near the top-left corner of the glass, so every screen gets that corner zoomed, to
        // show the title's first stem.
        PanelLook.Insets titleGlass = PanelLook.current().glass();
        zoomStill(
                context,
                still,
                "crt-corner-title-zoom-" + screen,
                titleGlass.left() - REACH_OUT,
                titleGlass.top() - REACH_OUT,
                CROP,
                CROP);
        if (corners) {
            PanelLook.Insets glass = PanelLook.current().glass();
            zoomStill(
                    context,
                    still,
                    "crt-corner-top-left-zoom",
                    glass.left() - REACH_OUT,
                    glass.top() - REACH_OUT,
                    CROP,
                    CROP);
            zoomStill(
                    context,
                    still,
                    "crt-corner-bottom-right-zoom",
                    open.width - glass.right() + REACH_OUT - CROP,
                    open.height - glass.bottom() + REACH_OUT - CROP,
                    CROP,
                    CROP);
        }
        TerminalPanelScreens.close(context);
    }
}
