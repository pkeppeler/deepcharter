package io.github.pkeppeler.deepcharter.test.support;

import io.github.pkeppeler.deepcharter.layer.CampaignBands;
import io.github.pkeppeler.deepcharter.layer.LayerChain;
import io.github.pkeppeler.deepcharter.layer.Zones;
import java.nio.file.Path;
import java.util.function.IntConsumer;
import java.util.stream.IntStream;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;

/**
 * A flooded hall in the middle zone of each layer, and a camera under its water looking east along it, past the render
 * distance, once the eyes have adjusted (#1187). {@code UnderwaterFogClientTest} asserts the frame is dark and the
 * {@code underwater-fog} scenario shows it, so the two cannot drift.
 */
public final class UnderwaterFog {
    /**
     * Between the halls of two layers, east of {@code CavernSky}'s, so each has its own chunk columns (layers share
     * one level).
     */
    private static final int LAYER_STEP = 256;

    /** The zone of each layer whose biome the camera is in: the middle one. */
    private static final int ZONE = 1;

    /** Water west of the camera's block. */
    private static final int BEHIND = 4;

    /**
     * Water east of the camera's block: past the 2 chunks drawn round the camera's chunk, so a ray of the sampled box
     * leaves the drawn chunks before it meets a wall.
     */
    private static final int AHEAD = 80;

    /**
     * Water each side of the camera: a ray 16 degrees off the middle meets it about 70 blocks out, past the drawn
     * chunks.
     */
    private static final int HALF_WIDTH = 20;

    /** Water above and below the camera's feet: a ray 10 degrees up or down meets it about 70 blocks out. */
    private static final int HALF_HEIGHT = 13;

    /**
     * The brightest a channel of the box's median may be. The art direction's world stays black; vanilla's water fog,
     * lifted as the eyes adjust, reads {@code #1919ff}.
     */
    private static final int DARK = 40;

    /** Looking east along the hall, level; the sky clock does not reach under the water of a layer. */
    private static final CavernSky.View EAST = new CavernSky.View("underwater-east", 0, -90, 0);

    private UnderwaterFog() {}

    /** Stands the camera under each layer's flooded hall until its eyes have adjusted, then runs {@code atLayer}. */
    public static void tour(ClientGameTestContext context, TestServerContext server, IntConsumer atLayer) {
        ClientChecks.withRenderDistance(
                context,
                CavernSky.RENDER_DISTANCE,
                () -> IntStream.rangeClosed(1, CampaignBands.LAYERS.size()).forEach(layer -> {
                    BlockPos feet = flood(server, layer);
                    CavernSky.stand(context, server, s -> TestLayers.level(s, layer), feet, EAST);
                    ClientWait.until(
                            context,
                            "the camera under the water of layer " + layer + "'s hall with its eyes adjusted",
                            client -> client.player != null
                                    && client.player.isUnderWater()
                                    && client.player.getWaterVision() >= 1f);
                    atLayer.accept(layer);
                }));
    }

    /** Fills the hall of {@code layer} with water, and returns the camera's feet in it. */
    private static BlockPos flood(TestServerContext server, int layer) {
        return server.computeOnServer(s -> {
            ServerLevel level = TestLayers.level(s, layer);
            LayerChain.Band band = TestLayers.band(level, layer);
            Zones.Span zone = Zones.span(band.minY(), band.height(), ZONE);
            BlockPos feet = new BlockPos(
                    CavernSky.HALL_X + LAYER_STEP * layer, (zone.low() + zone.high()) / 2, CavernSky.HALL_Z);
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
        ScreenBox box = ScreenBox.boxMedian(shot);
        boolean dark = IntStream.range(0, 3).allMatch(c -> box.median(c) <= DARK);
        return new Reading(
                dark,
                "the box's median is %s, its brightest channels %s (at most %d wanted on each channel of the median)"
                        .formatted(box.medianHex(), box.maxHex(), DARK));
    }
}
