package io.github.pkeppeler.deepcharter.test.support;

import io.github.pkeppeler.deepcharter.layer.LayerChain;
import io.github.pkeppeler.deepcharter.layer.Zones;
import io.github.pkeppeler.deepcharter.test.support.ClientLight.Box;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import javax.imageio.ImageIO;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import org.joml.Vector3fc;

/**
 * A cavern of layer 1 with lines of sight past the render distance, where the shared overworld type draws its sky (#1161), and the
 * check that what shows there is the zone's fog and no sun, moon, star or dusk glow. {@code CavernSkyClientTest} asserts it and the
 * {@code cavern-sky} scenario shows it, so the two cannot drift.
 *
 * <p>The hall runs west to east, the axis the sun and the moon cross. The client's render distance is cut to {@link #RENDER_DISTANCE}
 * chunks, so the hall reaches past the last drawn chunk at both ends and the sky is what shows there.
 */
public final class CavernSky {
    /** Far from the colony, in the middle of a chunk, so the drawn chunks reach as far both ways. */
    private static final int HALL_X = 5_000;

    private static final int HALL_Z = 5_000;
    /** The render distance, in chunks, while the camera is in the hall: the hall must reach past it. */
    public static final int RENDER_DISTANCE = 2;

    /** From the camera to either end of the hall: past the 2 chunks drawn round the camera's chunk, with a chunk to spare. */
    private static final int HALF_LENGTH = 64;
    /** From the camera to either side wall: clear of every ray of the sampled box within the drawn chunks. */
    private static final int HALF_WIDTH = 18;
    /** The hall's air above its floor: clear of every ray of the sampled box within the drawn chunks. */
    private static final int HEIGHT = 28;
    /** The middle zone of layer 1, whose biome the camera stands in; the floor is this far above the zone's bottom. */
    private static final int ZONE = 1;

    private static final int FLOOR_ABOVE_ZONE_BOTTOM = 8;

    /** The box of the screen that must show the fog alone, either side of the middle: about 16 degrees across and 10 up and down. */
    private static final int HALF_BOX_WIDTH = 100;

    private static final int HALF_BOX_HEIGHT = 60;
    /** A pixel that differs from the box's median by more than this on a channel is a sky pixel that is not fog. */
    private static final int UNIFORM_TOLERANCE = 8;
    /** The box's median may differ from the biome's fog colour by this much on a channel (the render rounds). */
    private static final int FOG_TOLERANCE = 6;

    /**
     * A view of the hall at a sky time. The sky timeline ({@code deepcharter:sky}) stands the dusk sun 10 degrees above the west horizon at
     * sky time 0, and puts the moon 50 degrees up in the east at 144000 with every star out; at 68000 the moon is about 12 degrees above the
     * east horizon and the stars are at more than half their brightness.
     */
    public record View(String name, long skyTicks, float yaw, float pitch) {}

    public static final List<View> VIEWS = List.of(
            new View("dusk-west", 0, 90, -10),
            new View("moonrise-east", 68_000, -90, -12),
            new View("night-west", 144_000, 90, -10),
            new View("night-east", 144_000, -90, -10));

    private CavernSky() {}

    /** Carves the hall in layer 1 and returns its floor under the camera. */
    public static BlockPos carve(TestServerContext server) {
        return server.computeOnServer(s -> {
            ServerLevel level = TestLayers.level(s, 1);
            LayerChain.Band band = TestLayers.band(level, 1);
            Zones.Span zone = Zones.span(band.minY(), band.height(), ZONE);
            BlockPos floor = new BlockPos(HALL_X, zone.low() + FLOOR_ABOVE_ZONE_BOTTOM, HALL_Z);
            RoomCarver.carve(
                    level,
                    floor.offset(-HALF_LENGTH, 0, -HALF_WIDTH),
                    floor.offset(HALF_LENGTH, HEIGHT - 1, HALF_WIDTH),
                    Blocks.AIR.defaultBlockState());
            return floor;
        });
    }

    /**
     * Runs {@code body} with the client's render distance at {@link #RENDER_DISTANCE}, and puts it back in a finally: every client test
     * shares one client.
     */
    public static void withShortRenderDistance(ClientGameTestContext context, Runnable body) {
        int was = context.computeOnClient(
                client -> client.options.renderDistance().get());
        try {
            context.runOnClient(client -> client.options.renderDistance().set(RENDER_DISTANCE));
            body.run();
        } finally {
            context.runOnClient(client -> client.options.renderDistance().set(was));
        }
    }

    /**
     * Sets the sky clock to the view's time, and stands the first player, a spectator, on {@code floor} facing the view, until the client
     * holds and has drawn the chunks round it.
     */
    public static void stand(ClientGameTestContext context, TestServerContext server, BlockPos floor, View view) {
        server.runCommand("time of deepcharter:sky set " + view.skyTicks());
        server.runOnServer(s -> {
            var player = s.getPlayerList().getPlayers().getFirst();
            player.setGameMode(GameType.SPECTATOR);
            player.teleportTo(
                    TestLayers.level(s, 1),
                    floor.getX() + 0.5,
                    floor.getY(),
                    floor.getZ() + 0.5,
                    Set.of(),
                    view.yaw(),
                    view.pitch(),
                    true);
        });
        Box around = Box.around(floor, RENDER_DISTANCE * 16, 1, HEIGHT);
        ClientWait.until(
                context,
                "the client on the hall's floor at " + floor.toShortString() + " with the chunks round it drawn",
                client -> client.player != null
                        && client.player.blockPosition().equals(floor)
                        && Math.abs(client.player.getYRot() - view.yaw()) < 0.5f
                        && ClientLight.chunksPresent(ClientLight.clientLevel(client), around)
                        && client.levelRenderer.hasRenderedAllSections(),
                client -> "the player is at "
                        + (client.player == null
                                ? "nowhere"
                                : client.player.blockPosition().toShortString()));
    }

    /** The fog colour the client's camera reads: what every pixel of the sampled box must be. */
    public static Vector3fc fogAtCamera(ClientGameTestContext context) {
        return context.computeOnClient(client ->
                client.gameRenderer.mainCamera().attributeProbe().getValue(EnvironmentAttributes.FOG_COLOR, 1f));
    }

    /**
     * What the box in the middle of a still shows.
     *
     * @param fogOnly whether it is the fog colour throughout: a sun, moon, star or dusk glow is a pixel off the box's median, and a sky of
     *     its own colour is a median off the fog
     * @param describe the median, the fog and the pixels off the median, for the failure and the log
     */
    public record Reading(boolean fogOnly, String describe) {}

    /** Reads the box in the middle of {@code shot} against the fog colour {@code fog}. */
    public static Reading read(Path shot, Vector3fc fog) {
        BufferedImage image = image(shot);
        int cx = image.getWidth() / 2;
        int cy = image.getHeight() / 2;
        int[][] channels = new int[3][(2 * HALF_BOX_WIDTH) * (2 * HALF_BOX_HEIGHT)];
        int n = 0;
        for (int y = cy - HALF_BOX_HEIGHT; y < cy + HALF_BOX_HEIGHT; y++) {
            for (int x = cx - HALF_BOX_WIDTH; x < cx + HALF_BOX_WIDTH; x++) {
                int rgb = image.getRGB(x, y);
                channels[0][n] = (rgb >> 16) & 0xFF;
                channels[1][n] = (rgb >> 8) & 0xFF;
                channels[2][n] = rgb & 0xFF;
                n++;
            }
        }
        int[] median = new int[3];
        for (int c = 0; c < 3; c++) {
            int[] sorted = channels[c].clone();
            Arrays.sort(sorted);
            median[c] = sorted[sorted.length / 2];
        }
        int off = 0;
        int worst = 0;
        for (int i = 0; i < n; i++) {
            int delta = 0;
            for (int c = 0; c < 3; c++) {
                delta = Math.max(delta, Math.abs(channels[c][i] - median[c]));
            }
            worst = Math.max(worst, delta);
            off += delta > UNIFORM_TOLERANCE ? 1 : 0;
        }
        int[] wanted = {channel(fog.x()), channel(fog.y()), channel(fog.z())};
        int fogDelta = 0;
        for (int c = 0; c < 3; c++) {
            fogDelta = Math.max(fogDelta, Math.abs(median[c] - wanted[c]));
        }
        String read =
                "the box's median is %s and the fog %s; %d of %d pixels differ from the median by more than %d (worst %d)"
                        .formatted(hex(median), hex(wanted), off, n, UNIFORM_TOLERANCE, worst);
        return new Reading(off == 0 && fogDelta <= FOG_TOLERANCE, read);
    }

    private static int channel(float value) {
        return Math.round(value * 255);
    }

    private static String hex(int[] rgb) {
        return "#%02x%02x%02x".formatted(rgb[0], rgb[1], rgb[2]);
    }

    private static BufferedImage image(Path shot) {
        try {
            return ImageIO.read(shot.toFile());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
