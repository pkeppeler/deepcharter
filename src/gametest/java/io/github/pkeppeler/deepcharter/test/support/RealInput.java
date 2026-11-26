package io.github.pkeppeler.deepcharter.test.support;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonInfo;

/**
 * Clicks and types through {@code MouseHandler.onButton} and {@code KeyboardHandler}, the way the window delivers input (#1304). Minecraft
 * 26.3 runs on SDL, which numbers mouse buttons from 1, so pass {@code InputConstants.MOUSE_BUTTON_LEFT} and the like, never a bare number. {@code type} calls {@code textInput} directly, past SDL's text-input gate, so it cannot catch a missing {@code
 * TextInputManager.startTextInput}; only an assertion on {@code SDLKeyboard.SDL_TextInputActive} can (#1269).
 */
public final class RealInput {
    private RealInput() {}

    /** A press and a release of {@code button} at the cursor, then one tick. */
    public static void click(ClientGameTestContext context, int button) {
        click(context, button, 0);
    }

    /** A press and a release of {@code button} with {@code modifiers} held (an {@code InputConstants.MOD_*}) at the cursor, then one tick. */
    public static void click(ClientGameTestContext context, int button, int modifiers) {
        for (int action : new int[] {InputConstants.PRESS, InputConstants.RELEASE}) {
            context.runOnClient(client -> client.mouseHandler.onButton(
                    client.getWindow().handle(), new MouseButtonInfo(button, modifiers), action));
        }
        context.waitTicks(1);
    }

    /** A key press and release with {@code modifiers}, then one tick. */
    public static void key(ClientGameTestContext context, int key, int modifiers) {
        for (int action : new int[] {InputConstants.PRESS, InputConstants.RELEASE}) {
            context.runOnClient(client -> client.keyboardHandler.keyPress(
                    client.getWindow().handle(), action, new KeyEvent(key, 0, modifiers)));
        }
        context.waitTicks(1);
    }

    /**
     * Holds {@code key} down for {@code ticks} client ticks, then lets it go and waits one tick: a key that acts while
     * held ({@code KeyMapping.isDown}) sees it down that long.
     */
    public static void hold(ClientGameTestContext context, int key, int ticks) {
        context.runOnClient(client -> client.keyboardHandler.keyPress(
                client.getWindow().handle(), InputConstants.PRESS, new KeyEvent(key, 0, 0)));
        context.waitTicks(ticks);
        context.runOnClient(client -> client.keyboardHandler.keyPress(
                client.getWindow().handle(), InputConstants.RELEASE, new KeyEvent(key, 0, 0)));
        context.waitTicks(1);
    }

    /**
     * Types {@code text} one letter, digit or space at a time, the way SDL sends it: each key goes down, then {@code
     * KeyboardHandler.textInput} delivers its text (it calls {@code textEditing}, then {@code charTyped} per code point), then the key
     * goes up. The key code of such a character is its upper-case ASCII value.
     */
    public static void type(ClientGameTestContext context, String text) {
        for (int i = 0; i < text.length(); i++) {
            char character = text.charAt(i);
            int key = Character.toUpperCase(character);
            context.runOnClient(client -> {
                long window = client.getWindow().handle();
                client.keyboardHandler.keyPress(window, InputConstants.PRESS, new KeyEvent(key, 0, 0));
                client.keyboardHandler.textInput(window, String.valueOf(character));
                client.keyboardHandler.keyPress(window, InputConstants.RELEASE, new KeyEvent(key, 0, 0));
            });
        }
        context.waitTicks(1);
    }
}
