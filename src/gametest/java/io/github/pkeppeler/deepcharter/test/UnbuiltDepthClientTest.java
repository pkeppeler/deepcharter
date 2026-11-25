package io.github.pkeppeler.deepcharter.test;

import io.github.pkeppeler.deepcharter.test.support.ClientChecks;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.ScreenProjection;
import io.github.pkeppeler.deepcharter.test.support.UnbuiltDepthViews;
import io.github.pkeppeler.deepcharter.test.support.UnbuiltDepthViews.Look;
import io.github.pkeppeler.deepcharter.test.support.UnbuiltDepthViews.Place;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import javax.imageio.ImageIO;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Client GameTest for #1192: under the floor of the last layer, in the unbuilt depth, a view straight down a shaft past the render
 * distance and a view level along a hall past the fog read as darkness, with and without night vision. Before #1192 the unbuilt depth
 * had no fog of its own and took the surface's sky timeline, orange at dusk, so both views faded into an orange haze. Each frame must
 * also show a lamp near the camera where the camera maths puts it, so a black frame (a camera in rock, a frame not drawn) cannot pass.
 * From under the water of the flooded shaft, where the client draws no dark disc over the sun and the moon, the view straight down must
 * show neither ({@code LayerSky}).
 */
public class UnbuiltDepthClientTest implements FabricClientGameTest {
    private static final Logger LOGGER = LoggerFactory.getLogger(UnbuiltDepthClientTest.class);

    /**
     * The median of the brightest channel of the box in the middle of a view in the unbuilt depth is at or under this: darkness. The
     * dusk fog of the surface's timeline, which the unbuilt depth read before #1192, is far over it on its red channel.
     */
    private static final int DARK = 0x10;

    /** A lamp near the camera shows a pixel brighter than this in some channel. */
    private static final int LIT = 0x40;

    /** How far from its predicted pixel a lamp may show: the maths is a pinhole's, and a block face has size. */
    private static final int WINDOW = 30;

    /** Half the width of the box read in the middle of a view: about 16 degrees across. */
    private static final int HALF_BOX_WIDTH = 100;

    /** Half the height of the box read in the middle of a view: about 10 degrees up and down. */
    private static final int HALF_BOX_HEIGHT = 60;

    /**
     * Under water a pixel of the box brighter than its median by more than {@link #UNIFORM_TOLERANCE} is a sun, a moon or one of the
     * water's own drifting particles. Past this many it is a sun or a moon: a body at the nadir covers thousands of the box's pixels, and
     * the particles covered at most 22 in the views measured.
     */
    private static final int SPECKS = 200;

    /** A pixel within this of the box's median, on every channel, is the box's own colour. */
    private static final int UNIFORM_TOLERANCE = 8;

    @Override
    public void runTest(ClientGameTestContext context) {
        ClientTestLog.start(this);
        List<String> failures = new ArrayList<>();
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            ClientWait.until(
                    context, "the player in the world", client -> client.player != null && client.level != null);
            TestServerContext server = singleplayer.getServer();
            server.runCommand("gamerule advance_time false");
            server.runCommand("weather clear");
            UnbuiltDepthViews.tour(
                    context,
                    server,
                    EnumSet.of(Place.UNBUILT, Place.UNDERWATER),
                    look -> {
                        BufferedImage image = read(ClientChecks.skyShot(context, "unbuilt-depth-" + look.name()));
                        if (look.place() == Place.UNBUILT) {
                            darkness(context, server, image, look).ifPresent(failures::add);
                        } else {
                            noSunOrMoon(image, look.name()).ifPresent(failures::add);
                        }
                    },
                    () -> {});
        }
        ClientChecks.require(failures.isEmpty(), String.join("\n", failures));
    }

    /**
     * Why the frame does not read as darkness with a lamp in it, or empty. The lamp must show within {@link #WINDOW} pixels of where the
     * camera maths puts one, so a wall lifted by night vision elsewhere in the frame cannot stand in for it.
     */
    private static Optional<String> darkness(
            ClientGameTestContext context, TestServerContext server, BufferedImage image, Look look) {
        String name = look.name();
        int[] box = box(image);
        int median = median(box);
        Vec3 eye = server.computeOnServer(s -> UnbuiltDepthViews.eye(UnbuiltDepthViews.level(s), look.place()));
        List<Vec3> lamps = server.computeOnServer(s -> UnbuiltDepthViews.lamps(UnbuiltDepthViews.level(s)));
        double fov =
                context.computeOnClient(client -> (double) client.options.fov().get());
        int lit = 0;
        int onScreen = 0;
        for (Vec3 lamp : lamps) {
            Optional<int[]> pixel = ScreenProjection.project(
                    image, eye, look.view().yaw(), look.view().pitch(), fov, lamp);
            if (pixel.isPresent()) {
                onScreen++;
                lit = Math.max(lit, brightestNear(image, pixel.get()[0], pixel.get()[1]));
            }
        }
        LOGGER.info(
                "[unbuilt-depth] {}: the box's median brightest channel is {}; {} lamps on the screen, the brightest near one {}",
                name,
                median,
                onScreen,
                lit);
        if (onScreen == 0) {
            return Optional.of(
                    "%s: no lamp is on the screen by the camera maths, so nothing proves the frame is a picture of the shaft"
                            .formatted(name));
        }
        if (lit <= LIT) {
            return Optional.of(
                    "%s: no pixel near a lamp is brighter than %d: a black or blank frame, or a camera in rock"
                            .formatted(name, LIT));
        }
        if (median > DARK) {
            return Optional.of(
                    "%s: the view does not read as darkness: the median of its middle's brightest channel is %d, over %d"
                            .formatted(name, median, DARK));
        }
        return Optional.empty();
    }

    /** Why the box shows a sun or a moon brighter than the water's fog round it, or empty. */
    private static Optional<String> noSunOrMoon(BufferedImage image, String name) {
        int[] box = box(image);
        int median = median(box);
        int brighter = 0;
        for (int channel : box) {
            brighter += channel - median > UNIFORM_TOLERANCE ? 1 : 0;
        }
        LOGGER.info(
                "[unbuilt-depth] {}: the box's median brightest channel is {}; {} of its {} pixels are brighter by more than {}",
                name,
                median,
                brighter,
                box.length,
                UNIFORM_TOLERANCE);
        if (brighter > SPECKS) {
            return Optional.of(
                    "%s: the sun or the moon shows: %d of the box's pixels are brighter than its median %d by more than %d"
                            .formatted(name, brighter, median, UNIFORM_TOLERANCE));
        }
        return Optional.empty();
    }

    /** The brightest channel of each pixel of the box in the middle of {@code image}. */
    private static int[] box(BufferedImage image) {
        int cx = image.getWidth() / 2;
        int cy = image.getHeight() / 2;
        int[] box = new int[(2 * HALF_BOX_WIDTH) * (2 * HALF_BOX_HEIGHT)];
        int n = 0;
        for (int y = cy - HALF_BOX_HEIGHT; y < cy + HALF_BOX_HEIGHT; y++) {
            for (int x = cx - HALF_BOX_WIDTH; x < cx + HALF_BOX_WIDTH; x++) {
                box[n++] = brightest(image.getRGB(x, y));
            }
        }
        return box;
    }

    /** The brightest channel of any pixel within {@link #WINDOW} of ({@code px}, {@code py}). */
    private static int brightestNear(BufferedImage image, int px, int py) {
        int brightest = 0;
        for (int y = Math.max(0, py - WINDOW); y <= Math.min(image.getHeight() - 1, py + WINDOW); y++) {
            for (int x = Math.max(0, px - WINDOW); x <= Math.min(image.getWidth() - 1, px + WINDOW); x++) {
                brightest = Math.max(brightest, brightest(image.getRGB(x, y)));
            }
        }
        return brightest;
    }

    private static int median(int[] values) {
        int[] sorted = values.clone();
        Arrays.sort(sorted);
        return sorted[sorted.length / 2];
    }

    private static int brightest(int rgb) {
        return Math.max((rgb >> 16) & 0xFF, Math.max((rgb >> 8) & 0xFF, rgb & 0xFF));
    }

    private static BufferedImage read(Path shot) {
        try {
            return ImageIO.read(shot.toFile());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
