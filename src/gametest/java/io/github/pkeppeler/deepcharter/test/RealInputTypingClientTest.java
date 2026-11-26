package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.ClientChecks.require;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.pkeppeler.deepcharter.client.handbook.HandbookKeys;
import io.github.pkeppeler.deepcharter.client.handbook.HandbookScreen;
import io.github.pkeppeler.deepcharter.client.handbook.catalogue.CatalogueKeys;
import io.github.pkeppeler.deepcharter.client.handbook.catalogue.CataloguePanel;
import io.github.pkeppeler.deepcharter.client.handbook.catalogue.CatalogueState;
import io.github.pkeppeler.deepcharter.scanner.ScannerRegistry;
import io.github.pkeppeler.deepcharter.test.support.CatalogueScene;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.RealInput;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import org.lwjgl.sdl.SDLKeyboard;

/**
 * Client GameTest for #1385: a letter typed through {@link RealInput#type} presses the key the window would, so a key mapping bound to it
 * fires. With no screen open H opens the handbook (a world key, read through {@code consumeClick}); over a catalogue item R shows its
 * recipes (a screen key, read through {@code matches}). A character no key types is refused. Each letter is the one the bound key types
 * on the current layout, so the test holds on any keyboard layout.
 */
public class RealInputTypingClientTest implements FabricClientGameTest {
    /** The US layout has no key for it. */
    private static final int SNOWMAN = 0x2603;

    @Override
    public void runTest(ClientGameTestContext context) {
        ClientTestLog.start(this);
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            ClientWait.until(
                    context, "the client in the world", client -> client.player != null && client.level != null);
            int recipesKey = boundKey(context, CatalogueKeys.RECIPES);
            int handbookKey = boundKey(context, HandbookKeys.OPEN);
            int typesR = typedBy(context, recipesKey);
            int typesH = typedBy(context, handbookKey);
            eachCharacterPressesTheKeyThatTypesIt(context, typesR, recipesKey, typesH, handbookKey);
            anHTypedInTheWorldOpensTheHandbook(context, typesH);
            anRTypedOverAnItemShowsItsRecipes(context, singleplayer, typesR);
            context.setScreen(() -> null);
        }
    }

    /**
     * The scancode the mapping is bound to now, read from the mapping, so a changed default binding does not change what the test types.
     * An unbound or mouse-bound mapping has no scancode to type, so it fails here.
     */
    private static int boundKey(ClientGameTestContext context, KeyMapping mapping) {
        InputConstants.Key bound = context.computeOnClient(client -> KeyMappingHelper.getBoundKeyOf(mapping));
        require(
                bound.getType() == InputConstants.Type.KEYBOARD && bound.getValue() > 0,
                mapping.getName() + " must be bound to a keyboard key, was " + bound);
        return bound.getValue();
    }

    /** The character the key at {@code scancode} types, unshifted, on the current keyboard layout ('r' for KEY_R on QWERTY). */
    private static int typedBy(ClientGameTestContext context, int scancode) {
        return context.computeOnClient(client -> SDLKeyboard.SDL_GetKeyFromScancode(scancode, (short) 0, false));
    }

    /**
     * The key a character presses is SDL's scancode, as {@code InputConstants.KEY_*} numbers keys, not its ASCII code (82 for R on QWERTY),
     * and its keycode is the character itself.
     */
    private static void eachCharacterPressesTheKeyThatTypesIt(
            ClientGameTestContext context, int typesR, int recipesKey, int typesH, int handbookKey) {
        requireKey(context, typesR, recipesKey);
        requireKey(context, typesH, handbookKey);
        requireKey(context, ' ', InputConstants.KEY_SPACE);
        // The keycode of a shifted letter depends on SDL's KEYCODE_OPTIONS, so only the key and Shift are pinned.
        KeyEvent upper = context.computeOnClient(client -> RealInput.keyEvent(Character.toUpperCase(typesR)));
        require(
                upper.key() == recipesKey && (upper.modifiers() & InputConstants.MOD_SHIFT) != 0,
                "'" + Character.toString(Character.toUpperCase(typesR)) + "' presses key " + recipesKey
                        + " with Shift, got " + upper);
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

    private static void requireKey(ClientGameTestContext context, int character, int key) {
        KeyEvent event = context.computeOnClient(client -> RealInput.keyEvent(character));
        require(
                event.key() == key
                        && event.keycode() == character
                        && (event.modifiers() & InputConstants.MOD_SHIFT) == 0,
                "'" + Character.toString(character) + "' presses key " + key + " with keycode " + character + ", got "
                        + event);
    }

    private static void anHTypedInTheWorldOpensTheHandbook(ClientGameTestContext context, int typesH) {
        context.setScreen(() -> null);
        RealInput.type(context, Character.toString(typesH));
        Screen screen = context.computeOnClient(client -> client.gui.screen());
        require(
                screen instanceof HandbookScreen,
                Character.toString(typesH) + " typed with no screen open fires the handbook key, got " + screen);
        context.setScreen(() -> null);
    }

    private static void anRTypedOverAnItemShowsItsRecipes(
            ClientGameTestContext context, TestSingleplayerContext singleplayer, int typesR) {
        CataloguePanel panel = CatalogueScene.hoverScannerUnit(context, singleplayer);
        RealInput.type(context, Character.toString(typesR));
        CatalogueState.Lookup lookup =
                context.computeOnClient(client -> panel.state().lookup());
        require(
                lookup != null
                        && lookup.item() == ScannerRegistry.SCANNER_UNIT
                        && lookup.mode() == CatalogueState.Mode.RECIPES,
                Character.toString(typesR) + " typed over the scanner unit fires the catalogue's recipes key, got "
                        + lookup);
        context.setScreen(() -> null);
    }
}
