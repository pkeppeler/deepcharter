package io.github.pkeppeler.deepcharter.test.support;

import com.mojang.blaze3d.platform.Window;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

/** Moves the real cursor of the test client by GUI pixels, so a test hovers and clicks through the input the player uses. */
public final class ClientCursor {
    private ClientCursor() {}

    /** Puts the cursor on the GUI pixel ({@code guiX}, {@code guiY}) and lets a frame draw with it there. */
    public static void moveTo(ClientGameTestContext context, double guiX, double guiY) {
        double[] window = context.computeOnClient(client -> {
            Window screen = client.getWindow();
            return new double[] {
                guiX * screen.getScreenWidth() / screen.getGuiScaledWidth(),
                guiY * screen.getScreenHeight() / screen.getGuiScaledHeight()
            };
        });
        context.getInput().setCursorPos(window[0], window[1]);
        context.waitTicks(1);
    }
}
