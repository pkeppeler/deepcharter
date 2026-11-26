package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.ClientChecks.require;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.pkeppeler.deepcharter.client.handbook.HandbookScreen;
import io.github.pkeppeler.deepcharter.client.handbook.catalogue.CatalogueLayout;
import io.github.pkeppeler.deepcharter.client.handbook.catalogue.CataloguePanel;
import io.github.pkeppeler.deepcharter.client.handbook.catalogue.CatalogueState;
import io.github.pkeppeler.deepcharter.scanner.ScannerRegistry;
import io.github.pkeppeler.deepcharter.test.support.CatalogueScene;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.RealInput;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.input.KeyEvent;

/**
 * Client GameTest for #1385: a letter typed through {@link RealInput#type} presses the key the window would, so a key mapping bound to it
 * fires. With no screen open H opens the handbook (a world key, read through {@code consumeClick}); over a catalogue item R shows its
 * recipes (a screen key, read through {@code matches}). A character no key types is refused.
 */
public class RealInputTypingClientTest implements FabricClientGameTest {
    /** No keyboard layout has a key for it. */
    private static final int SNOWMAN = 0x2603;

    @Override
    public void runTest(ClientGameTestContext context) {
        ClientTestLog.start(this);
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            ClientWait.until(
                    context, "the client in the world", client -> client.player != null && client.level != null);
            eachCharacterPressesTheKeyThatTypesIt(context);
            anHTypedInTheWorldOpensTheHandbook(context);
            anRTypedOverAnItemShowsItsRecipes(context, singleplayer);
            context.setScreen(() -> null);
        }
    }

    /** The key a character presses is SDL's scancode, as {@code InputConstants.KEY_*} numbers keys, not its ASCII code (82 for R). */
    private static void eachCharacterPressesTheKeyThatTypesIt(ClientGameTestContext context) {
        requireKey(context, 'r', InputConstants.KEY_R, false);
        requireKey(context, 'h', InputConstants.KEY_H, false);
        requireKey(context, ' ', InputConstants.KEY_SPACE, false);
        requireKey(context, 'R', InputConstants.KEY_R, true);
        boolean refused = context.computeOnClient(client -> {
            try {
                RealInput.keyEvent(SNOWMAN);
                return false;
            } catch (IllegalArgumentException expected) {
                return true;
            }
        });
        require(refused, "a character no key types is refused, not sent as some key");
    }

    private static void requireKey(ClientGameTestContext context, char character, int key, boolean shifted) {
        KeyEvent event = context.computeOnClient(client -> RealInput.keyEvent(character));
        require(
                event.key() == key && ((event.modifiers() & InputConstants.MOD_SHIFT) != 0) == shifted,
                "'" + character + "' presses key " + key + (shifted ? " with Shift" : "") + ", got " + event);
    }

    private static void anHTypedInTheWorldOpensTheHandbook(ClientGameTestContext context) {
        context.setScreen(() -> null);
        RealInput.type(context, "h");
        require(
                context.computeOnClient(client -> client.gui.screen()) instanceof HandbookScreen,
                "h typed with no screen open fires the handbook key, got "
                        + context.computeOnClient(client -> client.gui.screen()));
        context.setScreen(() -> null);
    }

    private static void anRTypedOverAnItemShowsItsRecipes(
            ClientGameTestContext context, TestSingleplayerContext singleplayer) {
        CataloguePanel panel = CatalogueScene.teachScannerRecipe(context, singleplayer);
        CatalogueScene.clearSearch(context, panel);
        context.waitTicks(2); // tick-wait: the catalogue lists what it now knows before the cell is looked up
        CatalogueLayout layout = CatalogueScene.layout(context, panel);
        int perPage = layout.shelf().perPage();
        int index = CatalogueScene.indexOf(context, panel, ScannerRegistry.SCANNER_UNIT);
        CatalogueScene.showPage(context, panel, perPage, index / perPage);
        CatalogueScene.hover(context, CatalogueScene.gridCell(layout, index % perPage));
        RealInput.type(context, "r");
        CatalogueState.Lookup lookup =
                context.computeOnClient(client -> panel.state().lookup());
        require(
                lookup != null
                        && lookup.item() == ScannerRegistry.SCANNER_UNIT
                        && lookup.mode() == CatalogueState.Mode.RECIPES,
                "r typed over the scanner unit fires the catalogue's recipes key, got " + lookup);
        context.setScreen(() -> null);
    }
}
