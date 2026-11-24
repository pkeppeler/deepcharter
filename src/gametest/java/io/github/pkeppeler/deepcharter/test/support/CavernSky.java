package io.github.pkeppeler.deepcharter.test.support;

import io.github.pkeppeler.deepcharter.client.layer.BreachVoidCover;
import io.github.pkeppeler.deepcharter.layer.LayerChain;
import io.github.pkeppeler.deepcharter.layer.Zones;
import io.github.pkeppeler.deepcharter.test.support.ClientLight.Box;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.minecraft.client.Camera;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.FogType;
import org.joml.Vector3fc;

/**
 * A cavern of layer 1 with lines of sight past the render distance, where the shared overworld type draws its sky (#1161), and the
 * check that what shows there is the zone's fog and no sun, moon, star or dusk glow. {@code CavernSkyClientTest} asserts it and the
 * {@code cavern-sky} scenario shows it, so the two cannot drift.
 *
 * <p>The hall runs west to east, the axis the sun and the moon cross. The client's render distance is cut to {@link #RENDER_DISTANCE}
 * chunks, so the hall reaches past the last drawn chunk at both ends and the sky is what shows there. Under its east end a pit drops past
 * the render distance too, so a view pitched down sees the sky below the horizon: the dark disc the client draws over the sun and the
 * moon at 180 degrees, which {@code sky_fog_end_distance} 0 turns into fog. The pit is then flooded and the camera looks straight down
 * from under the water, past the drawn sections, where the client draws no dark disc and {@code LayerSky} hides the sun and the moon.
 *
 * <p>In play {@code BreachVoidCover} also covers the nadir past the drawn sections (#1188), the views down into the pit and from under
 * the water included. {@code LayerSky} and the sky fog are the guard that holds without it, so the tour turns the cover off and those
 * views show the sky.
 */
public final class CavernSky {
    /** Far from the colony, in the middle of a chunk, so the drawn chunks reach as far both ways. */
    static final int HALL_X = 5_000;

    /** As {@link #HALL_X}. */
    static final int HALL_Z = 5_000;

    /** The render distance, in chunks, while the camera is in the hall: the hall must reach past it. */
    static final int RENDER_DISTANCE = 2;

    /** From the camera to either end of the hall: past the 2 chunks drawn round the camera's chunk, with a chunk to spare. */
    private static final int HALF_LENGTH = 64;
    /** From the camera to either side wall: clear of every ray of the sampled box within the drawn chunks. */
    private static final int HALF_WIDTH = 18;
    /** The hall's air above its floor: clear of every ray of the sampled box within the drawn chunks. */
    private static final int HEIGHT = 28;
    /** The middle zone of layer 1, whose biome the camera stands in. */
    private static final int ZONE = 1;

    /** How far the hall's floor is above the bottom of {@link #ZONE}: every camera, under the pit's water too, stays in the zone. */
    private static final int FLOOR_ABOVE_ZONE_BOTTOM = 30;

    /**
     * How far east of the camera's block the floor drops into the pit, which runs to the hall's east end: the edge is half a block from
     * the camera's eye, so every ray of a box pitched down passes over it.
     */
    private static final int PIT_START = 1;

    /**
     * How far the pit's floor is below the hall's. The client draws the sections up to {@link #RENDER_DISTANCE} above and below the
     * camera's, so from under the flooded pit's water a view straight down looks past the last drawn section, with a section to spare.
     */
    private static final int PIT_DEPTH = 64;

    /** How far into the flooded pit, east of its start, the camera stands for the underwater views: the box is over the pit's floor. */
    private static final int UNDERWATER_EAST = 16;

    /** How far under the hall's floor the camera's feet are for the underwater views: its eye is under the water. */
    private static final int UNDERWATER_BELOW = 4;

    /** A pixel that differs from the box's median by more than this on a channel is a sky pixel that is not fog. */
    private static final int UNIFORM_TOLERANCE = 8;
    /** The box's median may differ from the biome's fog colour by this much on a channel (the render rounds). */
    private static final int FOG_TOLERANCE = 6;

    /**
     * A view of the hall at a sky time. The sky timeline ({@code deepcharter:sky}) stands the dusk sun 10 degrees above the west horizon at
     * sky time 0, and puts the moon 50 degrees up in the east at 144000 with every star out; at 68000 the moon is about 12 degrees above the
     * east horizon and the stars are at more than half their brightness. A positive pitch looks down.
     */
    public record View(String name, long skyTicks, float yaw, float pitch) {}

    /** The views from the hall's floor: up past either end, and down into the pit past its east end. */
    private static final List<View> VIEWS = List.of(
            new View("dusk-west", 0, 90, -10),
            new View("moonrise-east", 68_000, -90, -12),
            new View("night-west", 144_000, 90, -10),
            new View("night-east", 144_000, -90, -10),
            new View("dusk-pit-east", 0, -90, 45),
            new View("night-pit-east", 144_000, -90, 45));

    /** The views from under the water of the flooded pit, straight down, where the sun and the moon stand at 180 degrees. */
    private static final List<View> UNDERWATER_VIEWS =
            List.of(new View("underwater-dusk-down", 0, -90, 90), new View("underwater-night-down", 144_000, -90, 90));

    /** What a tour does at each view, once the camera stands there. */
    @FunctionalInterface
    public interface AtView {
        /** {@code underwater} is whether the camera is in the flooded pit. */
        void at(View view, boolean underwater);
    }

    private CavernSky() {}

    /**
     * Carves the hall and its pit, turns the cover off, cuts the render distance, and stands the camera at each view in turn: first
     * every view from the hall's floor, then, with the pit flooded, every view from under its water. {@code atView} runs at each.
     */
    public static void tour(ClientGameTestContext context, TestServerContext server, AtView atView) {
        BlockPos floor = carve(server);
        boolean coverWas = BreachVoidCover.setDrawn(false);
        try {
            ClientChecks.withRenderDistance(context, RENDER_DISTANCE, () -> {
                for (View view : VIEWS) {
                    stand(context, server, CavernSky::layer, floor, view);
                    atView.at(view, false);
                }
                flood(server, floor);
                BlockPos inWater = floor.offset(PIT_START + UNDERWATER_EAST, -UNDERWATER_BELOW, 0);
                for (View view : UNDERWATER_VIEWS) {
                    stand(context, server, CavernSky::layer, inWater, view);
                    ClientWait.until(
                            context,
                            "the camera under the water of the flooded pit",
                            client -> client.player != null && client.player.isUnderWater());
                    atView.at(view, true);
                }
            });
        } finally {
            BreachVoidCover.setDrawn(coverWas);
        }
    }

    private static ServerLevel layer(MinecraftServer server) {
        return TestLayers.level(server, 1);
    }

    /** Carves the hall in layer 1 and the pit under its east end, and returns the hall's floor under the camera. */
    private static BlockPos carve(TestServerContext server) {
        return server.computeOnServer(s -> {
            ServerLevel level = layer(s);
            LayerChain.Band band = TestLayers.band(level, 1);
            Zones.Span zone = Zones.span(band.minY(), band.height(), ZONE);
            BlockPos floor = new BlockPos(HALL_X, zone.low() + FLOOR_ABOVE_ZONE_BOTTOM, HALL_Z);
            RoomCarver.carve(
                    level,
                    floor.offset(-HALF_LENGTH, 0, -HALF_WIDTH),
                    floor.offset(HALF_LENGTH, HEIGHT - 1, HALF_WIDTH),
                    Blocks.AIR.defaultBlockState());
            fillPit(level, floor, Blocks.AIR);
            return floor;
        });
    }

    /** Fills the pit under the hall's east end with water. */
    private static void flood(TestServerContext server, BlockPos floor) {
        server.runOnServer(s -> fillPit(layer(s), floor, Blocks.WATER));
    }

    private static void fillPit(ServerLevel level, BlockPos floor, Block fill) {
        RoomCarver.carve(
                level,
                floor.offset(PIT_START, -PIT_DEPTH, -HALF_WIDTH),
                floor.offset(HALF_LENGTH, -1, HALF_WIDTH),
                fill.defaultBlockState());
    }

    /**
     * Sets the sky clock to the view's time, and stands the first player, a spectator, at {@code feet} in {@code level} facing the view,
     * until the client holds and has drawn the chunks round it.
     */
    public static void stand(
            ClientGameTestContext context,
            TestServerContext server,
            Function<MinecraftServer, ServerLevel> level,
            BlockPos feet,
            View view) {
        server.runCommand("time of deepcharter:sky set " + view.skyTicks());
        server.runOnServer(s -> {
            var player = s.getPlayerList().getPlayers().getFirst();
            player.setGameMode(GameType.SPECTATOR);
            player.teleportTo(
                    level.apply(s),
                    feet.getX() + 0.5,
                    feet.getY(),
                    feet.getZ() + 0.5,
                    Set.of(),
                    view.yaw(),
                    view.pitch(),
                    true);
        });
        Box around = Box.around(feet, RENDER_DISTANCE * 16, 1, HEIGHT);
        ClientWait.until(
                context,
                "the client at " + feet.toShortString() + " facing " + view.name() + " with the chunks round it drawn",
                client -> client.player != null
                        && client.player.blockPosition().equals(feet)
                        && Math.abs(client.player.getYRot() - view.yaw()) < 0.5f
                        && ClientLight.chunksPresent(ClientLight.clientLevel(client), around)
                        && client.levelRenderer.hasRenderedAllSections(),
                client -> "the player is at "
                        + (client.player == null
                                ? "nowhere"
                                : client.player.blockPosition().toShortString()));
    }

    /**
     * The fog colour the client's camera reads, the water's under water: what every pixel of the sampled box must be. Under water the
     * client brightens it as the eyes adjust, so there it is for the log only.
     */
    public static Vector3fc fogAtCamera(ClientGameTestContext context) {
        return context.computeOnClient(client -> {
            Camera camera = client.gameRenderer.mainCamera();
            return camera.attributeProbe()
                    .getValue(
                            camera.getFluidInCamera() == FogType.WATER
                                    ? EnvironmentAttributes.WATER_FOG_COLOR
                                    : EnvironmentAttributes.FOG_COLOR,
                            1f);
        });
    }

    /**
     * What the box in the middle of a still shows.
     *
     * @param fogOnly whether it is the fog colour throughout: a sun, moon, star or dusk glow is a pixel off the box's median, and a sky of
     *     its own colour (or a dark disc not turned to fog) is a median off the fog
     * @param noneBrighter whether no pixel is brighter than the median: a sun or a moon is drawn brighter than the sky behind it
     * @param describe the median, the fog and the pixels off the median, for the failure and the log
     */
    public record Reading(boolean fogOnly, boolean noneBrighter, String describe) {}

    /** Reads the box in the middle of {@code shot} against the fog colour {@code fog}. */
    public static Reading read(Path shot, Vector3fc fog) {
        ScreenBox box = ScreenBox.boxMedian(shot);
        int n = box.count();
        int off = 0;
        int brighter = 0;
        int worst = 0;
        for (int i = 0; i < n; i++) {
            int delta = 0;
            int above = 0;
            for (int c = 0; c < 3; c++) {
                delta = Math.max(delta, Math.abs(box.channel(c, i) - box.median(c)));
                above = Math.max(above, box.channel(c, i) - box.median(c));
            }
            worst = Math.max(worst, delta);
            off += delta > UNIFORM_TOLERANCE ? 1 : 0;
            brighter += above > UNIFORM_TOLERANCE ? 1 : 0;
        }
        int[] wanted = {channel(fog.x()), channel(fog.y()), channel(fog.z())};
        int fogDelta = 0;
        for (int c = 0; c < 3; c++) {
            fogDelta = Math.max(fogDelta, Math.abs(box.median(c) - wanted[c]));
        }
        String read =
                ("the box's median is %s and the fog %s; %d of %d pixels differ from the median by more than %d (worst %d), %d of"
                                + " them brighter")
                        .formatted(box.medianHex(), ScreenBox.hex(wanted), off, n, UNIFORM_TOLERANCE, worst, brighter);
        return new Reading(off == 0 && fogDelta <= FOG_TOLERANCE, brighter == 0, read);
    }

    private static int channel(float value) {
        return Math.round(value * 255);
    }
}
