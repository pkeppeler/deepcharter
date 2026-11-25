package io.github.pkeppeler.deepcharter.test.support;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonInfo;

/**
 * Clicks and types through {@code MouseHandler.onButton} and {@code KeyboardHandler}, the way the window delivers input (#1304). Minecraft
 * 26.3 runs on SDL, which numbers mouse buttons from 1, so pass {@code InputConstants.MOUSE_BUTTON_LEFT} and the like, never a bare number.
 */
public final class RealInput {
    private RealInput() {}

    /** A press and a release of {@code button} at the cursor, then one tick. */
    public static void click(ClientGameTestContext context, int button) {
        for (int action : new int[] {InputConstants.PRESS, InputConstants.RELEASE}) {
            context.runOnClient(client ->
                    client.mouseHandler.onButton(client.getWindow().handle(), new MouseButtonInfo(button, 0), action));
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

    /** A key that also produces {@code character}: the key press, then the character the window sends after it. */
    public static void type(ClientGameTestContext context, int key, char character) {
        context.runOnClient(client -> {
            long window = client.getWindow().handle();
            client.keyboardHandler.keyPress(window, InputConstants.PRESS, new KeyEvent(key, 0, 0));
            client.keyboardHandler.charTyped(window, new CharacterEvent(character));
            client.keyboardHandler.keyPress(window, InputConstants.RELEASE, new KeyEvent(key, 0, 0));
        });
        context.waitTicks(1);
    }
}
