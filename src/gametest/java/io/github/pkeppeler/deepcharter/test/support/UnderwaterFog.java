package io.github.pkeppeler.deepcharter.test.support;

import io.github.pkeppeler.deepcharter.layer.CampaignBands;
import io.github.pkeppeler.deepcharter.layer.LayerChain;
import io.github.pkeppeler.deepcharter.layer.Zones;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.Arrays;
import javax.imageio.ImageIO;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;

/**
 * A flooded hall in the middle zone of each layer, and a camera under its water looking east along it, past the render distance, once
 * the eyes have adjusted (#1187). There the client lifts the water's fog colour to full brightness at its brightest channel unless a
 * channel is exactly 0, so vanilla's water fog shows as a bright-blue wall. {@code UnderwaterFogClientTest} asserts the frame is dark
 * and the {@code underwater-fog} scenario shows it, so the two cannot drift.
 */
public final class UnderwaterFog {
    /** Clear of {@code CavernSky}'s hall and of the colony, in the middle of a chunk; each layer's hall is {@link #LAYER_STEP} further east. */
    private static final int HALL_X = 5_000;

    /** As {@link #HALL_X}. */
    private static final int HALL_Z = 5_000;

    /** Between the halls of two layers, so each has its own chunk columns (layers share one level). */
    private static final int LAYER_STEP = 256;

    /** The render distance, in chunks, under the water: the hall must reach past it. */
    private static final int RENDER_DISTANCE = 2;

    /** The zone of each layer whose biome the camera is in: the middle one. */
    private static final int ZONE = 1;

    /** Water west of the camera's block. */
    private static final int BEHIND = 4;

    /**
     * Water east of the camera's block: past the 2 chunks drawn round the camera's chunk, so a ray of the sampled box leaves the drawn
     * chunks before it meets a wall.
     */
    private static final int AHEAD = 80;

    /** Water each side of the camera: a ray 16 degrees off the middle meets it about 70 blocks out, past the drawn chunks. */
    private static final int HALF_WIDTH = 20;

    /** Water above and below the camera's feet: a ray 10 degrees up or down meets it about 70 blocks out. */
    private static final int HALF_HEIGHT = 13;

    /** Half the width of the sampled box of the screen: about 16 degrees across, as {@code CavernSky}'s. */
    private static final int HALF_BOX_WIDTH = 100;

    /** Half the height of the sampled box: about 10 degrees up and down. */
    private static final int HALF_BOX_HEIGHT = 60;

    /**
     * The brightest a channel of the box's median may be. The art direction's world stays black; vanilla's water fog, lifted as the eyes
     * adjust, reads {@code #1919ff}.
     */
    private static final int DARK = 40;

    /** Looking east along the hall, level; the sky clock does not reach under the water of a layer. */
    private static final CavernSky.View EAST = new CavernSky.View("underwater-east", 0, -90, 0);

    /** What a tour does in each layer, once the camera stands there with its eyes adjusted. */
    @FunctionalInterface
    public interface AtLayer {
        void at(int layer);
    }

    private UnderwaterFog() {}

    /**
     * Floods a hall in each layer, cuts the render distance, and stands the camera, a spectator, under each hall's water facing east until
     * its eyes have adjusted (the client's water vision is full). {@code atLayer} runs at each.
     */
    public static void tour(ClientGameTestContext context, TestServerContext server, AtLayer atLayer) {
        int was = context.computeOnClient(
                client -> client.options.renderDistance().get());
        try {
            context.runOnClient(client -> client.options.renderDistance().set(RENDER_DISTANCE));
            for (int layer = 1; layer <= CampaignBands.LAYERS.size(); layer++) {
                int inLayer = layer;
                BlockPos feet = flood(server, layer);
                CavernSky.stand(context, server, s -> TestLayers.level(s, inLayer), feet, EAST);
                ClientWait.until(
                        context,
                        "the camera under the water of layer " + layer + "'s hall with its eyes adjusted",
                        client -> client.player != null
                                && client.player.isUnderWater()
                                && client.player.getWaterVision() >= 1f);
                atLayer.at(layer);
            }
        } finally {
            context.runOnClient(client -> client.options.renderDistance().set(was));
        }
    }

    /** Fills the hall of {@code layer} with water, and returns the camera's feet in it. */
    private static BlockPos flood(TestServerContext server, int layer) {
        return server.computeOnServer(s -> {
            ServerLevel level = TestLayers.level(s, layer);
            LayerChain.Band band = TestLayers.band(level, layer);
            Zones.Span zone = Zones.span(band.minY(), band.height(), ZONE);
            BlockPos feet = new BlockPos(HALL_X + LAYER_STEP * layer, (zone.low() + zone.high()) / 2, HALL_Z);
            RoomCarver.carve(
                    level,
                    feet.offset(-BEHIND, -HALF_HEIGHT, -HALF_WIDTH),
                    feet.offset(AHEAD, HALF_HEIGHT, HALF_WIDTH),
                    Blocks.WATER.defaultBlockState());
            return feet;
        });
    }

    /**
     * What the box in the middle of a still shows.
     *
     * @param dark whether every channel of the box's median is at most {@link #DARK}
     * @param describe the median and the spread, for the failure and the log
     */
    public record Reading(boolean dark, String describe) {}

    /** Reads the box in the middle of {@code shot}. */
    public static Reading read(Path shot) {
        BufferedImage image;
        try {
            image = ImageIO.read(shot.toFile());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        int cx = image.getWidth() / 2;
        int cy = image.getHeight() / 2;
        int n = (2 * HALF_BOX_WIDTH) * (2 * HALF_BOX_HEIGHT);
        int[][] channels = new int[3][n];
        int i = 0;
        for (int y = cy - HALF_BOX_HEIGHT; y < cy + HALF_BOX_HEIGHT; y++) {
            for (int x = cx - HALF_BOX_WIDTH; x < cx + HALF_BOX_WIDTH; x++) {
                int rgb = image.getRGB(x, y);
                channels[0][i] = (rgb >> 16) & 0xFF;
                channels[1][i] = (rgb >> 8) & 0xFF;
                channels[2][i] = rgb & 0xFF;
                i++;
            }
        }
        int[] median = new int[3];
        int[] max = new int[3];
        for (int c = 0; c < 3; c++) {
            int[] sorted = channels[c].clone();
            Arrays.sort(sorted);
            median[c] = sorted[n / 2];
            max[c] = sorted[n - 1];
        }
        boolean dark = Arrays.stream(median).allMatch(channel -> channel <= DARK);
        return new Reading(
                dark,
                "the box's median is %s, its brightest channels %s (at most %d wanted on each channel of the median)"
                        .formatted(hex(median), hex(max), DARK));
    }

    private static String hex(int[] rgb) {
        return "#%02x%02x%02x".formatted(rgb[0], rgb[1], rgb[2]);
    }
}
