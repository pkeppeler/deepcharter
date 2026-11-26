package io.github.pkeppeler.deepcharter.test.support;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.systems.RenderSystem;
import java.nio.ShortBuffer;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonInfo;
import org.lwjgl.sdl.SDLKeyboard;
import org.lwjgl.sdl.SDLScancode;
import org.lwjgl.system.MemoryStack;

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
     * Types {@code text} one character at a time, the way SDL sends it: the key that types the character goes down ({@link #keyEvent}),
     * then {@code KeyboardHandler.textInput} delivers its text (it calls {@code textEditing}, then {@code charTyped} per code point), then
     * the key goes up. So a letter bound to a key mapping fires it, as a typist's would (#1385), and a text box that has the keyboard
     * must keep the key from its screen. Shift for an upper-case letter is carried in the event's modifiers but is not held, so
     * {@code InputConstants.isKeyDown} on {@code KEY_LSHIFT} does not see it during {@code type}.
     */
    public static void type(ClientGameTestContext context, String text) {
        text.codePoints()
                .forEach(codePoint -> context.runOnClient(client -> {
                    KeyEvent event = keyEvent(codePoint);
                    long window = client.getWindow().handle();
                    client.keyboardHandler.keyPress(window, InputConstants.PRESS, event);
                    client.keyboardHandler.textInput(window, Character.toString(codePoint));
                    client.keyboardHandler.keyPress(window, InputConstants.RELEASE, event);
                }));
        context.waitTicks(1);
    }

    /**
     * The key event SDL sends for the key that types {@code codePoint} on the current keyboard layout: its scancode as the key (what
     * {@code InputConstants.KEY_*} and key mappings number), its SDL keycode, and the modifiers it needs (Shift for an upper-case letter;
     * SDL's modifier bits are {@code InputConstants.MOD_*}). Call it on the client thread. Throws for a character no key types.
     */
    public static KeyEvent keyEvent(int codePoint) {
        RenderSystem.assertOnRenderThread();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ShortBuffer modifiers = stack.mallocShort(1);
            int scancode = SDLKeyboard.SDL_GetScancodeFromKey(codePoint, modifiers);
            if (scancode == SDLScancode.SDL_SCANCODE_UNKNOWN) {
                throw new IllegalArgumentException(
                        "no key types '" + Character.toString(codePoint) + "' (U+" + Integer.toHexString(codePoint)
                                + ") on this keyboard layout: type it with RealInput.key and its InputConstants.KEY_*");
            }
            short held = modifiers.get(0);
            return new KeyEvent(scancode, SDLKeyboard.SDL_GetKeyFromScancode(scancode, held, true), held);
        }
    }
}
