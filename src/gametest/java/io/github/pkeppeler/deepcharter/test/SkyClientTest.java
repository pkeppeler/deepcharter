package io.github.pkeppeler.deepcharter.test;

import io.github.pkeppeler.deepcharter.test.support.ClientChecks;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

/**
 * Client GameTest for #239 and #1606: what the player sees. Looking straight up, the sky is the held deep night
 * (#07050A) at every tick of the sky clock, the playtest's 80993 among them, whatever the gameplay clock says.
 */
public class SkyClientTest implements FabricClientGameTest {
    /** Sky-clock ticks: the old brightest dusk, the playtest's moment, and the deep night. */
    private static final long[] SKY_TICKS = {0, 80_993, 144_000};
    /** Straight up, from high above the colony. */
    private static final String LOOK_UP = "tp @p 18 250 0 0 -90";
    /** The brightest a channel of the held night may read. */
    private static final int NEAR_BLACK = 0x18;

    @Override
    public void runTest(ClientGameTestContext context) {
        ClientTestLog.start(this);
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            ClientWait.until(
                    context, "the client in the world", client -> client.player != null && client.level != null);
            TestServerContext server = singleplayer.getServer();
            server.runCommand("gamerule advance_time false");
            server.runCommand(LOOK_UP);
            for (long sky : SKY_TICKS) {
                server.runCommand("time of deepcharter:sky set " + sky);
                // The gameplay clock is its own: noon there, and the sky stays as dark.
                for (String gameplay : new String[] {"noon", "midnight"}) {
                    server.runCommand("time set " + gameplay);
                    int[] read = skyAbove(context, "sky-" + sky + "-" + gameplay);
                    if (read[0] > NEAR_BLACK || read[1] > NEAR_BLACK || read[2] > NEAR_BLACK) {
                        throw new AssertionError(
                                "The sky at sky tick %d and gameplay %s should be the held night, read (%d, %d, %d)"
                                        .formatted(sky, gameplay, read[0], read[1], read[2]));
                    }
                }
            }
        }
    }

    /** The average colour of a patch at the middle of the screen, looking straight up, once the client caught up. */
    private static int[] skyAbove(ClientGameTestContext context, String shotName) {
        BufferedImage image = read(ClientChecks.skyShot(context, shotName));
        int half = 20;
        long[] sum = new long[3];
        int count = 0;
        for (int y = image.getHeight() / 4 - half; y < image.getHeight() / 4 + half; y++) {
            for (int x = image.getWidth() / 2 - half; x < image.getWidth() / 2 + half; x++) {
                int rgb = image.getRGB(x, y);
                sum[0] += (rgb >> 16) & 0xFF;
                sum[1] += (rgb >> 8) & 0xFF;
                sum[2] += rgb & 0xFF;
                count++;
            }
        }
        return new int[] {(int) (sum[0] / count), (int) (sum[1] / count), (int) (sum[2] / count)};
    }

    private static BufferedImage read(Path shot) {
        try {
            return ImageIO.read(shot.toFile());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
