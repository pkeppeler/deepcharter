package io.github.pkeppeler.deepcharter.test.support;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

/** Reading what the HUD drew from a screenshot: the screen pixels of a GUI rectangle that are near a colour. */
public final class ScreenInk {
    /** How far, per channel, a screen pixel may be from the colour it counts as. */
    private static final int CHANNEL_SLACK = 24;

    private ScreenInk() {}

    /** A screenshot named {@code name}, with no toast over it, a few frames after the last change to the GUI. */
    public static BufferedImage shot(ClientGameTestContext context, String name) {
        context.runOnClient(client -> client.gui.toastManager().clear());
        context.waitTicks(3); // tick-wait: the frames after a scale change and the cleared toast
        Path file = context.takeScreenshot(name);
        try {
            return ImageIO.read(file.toFile());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * How many screen pixels of the GUI rectangle ({@code left}, {@code top}, {@code width} x {@code height}) at GUI scale {@code scale} are near
     * {@code color}.
     */
    public static int count(BufferedImage shot, int scale, int color, int left, int top, int width, int height) {
        int count = 0;
        for (int y = top * scale; y < (top + height) * scale; y++) {
            for (int x = left * scale; x < (left + width) * scale; x++) {
                if (x >= 0 && y >= 0 && x < shot.getWidth() && y < shot.getHeight() && near(shot.getRGB(x, y), color)) {
                    count++;
                }
            }
        }
        return count;
    }

    private static boolean near(int a, int b) {
        for (int shift = 0; shift <= 16; shift += 8) {
            if (Math.abs((a >> shift & 0xFF) - (b >> shift & 0xFF)) > CHANNEL_SLACK) {
                return false;
            }
        }
        return true;
    }
}
