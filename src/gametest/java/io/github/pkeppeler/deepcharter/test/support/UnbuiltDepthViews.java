package io.github.pkeppeler.deepcharter.test.support;

import io.github.pkeppeler.deepcharter.layer.CampaignBands;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * A breach through the floor of the last layer into the unbuilt depth below it (#1192), and the views of it: from the last layer down through
 * the broken crust, from under the crust straight down a shaft past the render distance and level along a hall past the fog, with and
 * without night vision, and from under the water of the flooded shaft straight down. {@code UnbuiltDepthClientTest} asserts them and the
 * {@code unbuilt-depth} scenario shows them, so the two cannot drift.
 *
 * <p>The camera reads the biome it stands in, so the views in the unbuilt depth stand far enough under the crust that the camera's blend
 * of biomes is the unbuilt depth's alone. The client's render distance is cut to two chunks ({@link ShaftViews#RENDER_DISTANCE},
 * {@link ClientChecks#withRenderDistance}), so the shaft drops past the last drawn section and the sky the client draws below the
 * horizon shows at its bottom. The sky clock is at dusk, where the surface's fog is brightest.
 */
public final class UnbuiltDepthViews {
    /** Far from the colony and the other client tests' sites, in the middle of a chunk. */
    private static final int X = 6_008;

    /** As {@link #X}. */
    private static final int Z = 6_008;

    /**
     * The shaft runs from the room in the last layer to its bottom in the unbuilt depth, this many blocks either side of its middle: wide enough
     * that past the last drawn section it fills the sampled box of a view straight down, so the box shows what the client draws there and
     * no wall.
     */
    private static final int SHAFT_RADIUS = 20;

    /** The room in the last layer, on the crust, reaches this far either side of the shaft's middle. */
    private static final int ROOM_RADIUS = 24;

    private static final int ROOM_HEIGHT = 8;

    /**
     * How far under the top of the unbuilt depth the camera's feet stand for the views there: past the 12 blocks over which the camera
     * blends the biome above (art-direction section 6, {@code CampaignAtmosphereTest}).
     */
    private static final int UNDER_THE_CRUST = 24;

    /**
     * How far the shaft drops under the camera's feet: the client draws two sections under the camera's, so the view straight down looks
     * past the last of them, with a section to spare, and so does the view from under the water.
     */
    private static final int SHAFT_DROP = 96;

    /** The hall runs east from the shaft's east wall this far, past the fog's end. */
    private static final int HALL_LENGTH = 64;

    /** The hall reaches this far north and south of the shaft's middle: at the edge of the sampled box it is past the fog's end. */
    private static final int HALL_HALF_WIDTH = 8;

    private static final int HALL_HEIGHT = 9;

    /**
     * The lamps hang in the shaft this far north and south of the camera, inside the fog's end and off the sampled box of each view: a pair
     * this far under the camera's feet for the view down, and a pair this far east of it, a block over its feet, for the view along the hall.
     */
    private static final int LAMP_OFFSET = 8;

    /** The water in the flooded shaft comes up to this far under the camera's dry feet, under the lamps. */
    private static final int WATER_DROP = 10;

    /** The camera's feet stand this far under the water's surface for the view from under it. */
    private static final int UNDER_THE_WATER = 6;

    /** A standing spectator's eye above its feet. */
    private static final double EYE = 1.62;

    private static final long DUSK = 0;
    private static final float EAST = -90f;
    private static final float DOWN = 90f;
    private static final float LEVEL = 0f;

    /** Where a view stands: in the last layer over the breach, in the unbuilt depth, or under the water of the flooded shaft. */
    public enum Place {
        BREACH,
        UNBUILT,
        UNDERWATER
    }

    /** A view: where it stands, its still's name, the sky time and the facing, and whether the camera has night vision. */
    public record Look(Place place, CavernSky.View view, boolean nightVision) {
        public String name() {
            return view.name();
        }
    }

    /** The views in the order they are taken: every dry one first, then the one from under the water of the shaft once it is flooded. */
    private static final List<Look> LOOKS = List.of(
            look("breach-down", Place.BREACH, DOWN, false),
            look("unbuilt-down", Place.UNBUILT, DOWN, false),
            look("unbuilt-level", Place.UNBUILT, LEVEL, false),
            look("unbuilt-down-night-vision", Place.UNBUILT, DOWN, true),
            look("unbuilt-level-night-vision", Place.UNBUILT, LEVEL, true),
            look("underwater-down", Place.UNDERWATER, DOWN, false));

    /** What a tour does at each look, once the camera stands there. */
    @FunctionalInterface
    public interface AtLook {
        void at(Look look);
    }

    private UnbuiltDepthViews() {}

    private static Look look(String name, Place place, float pitch, boolean nightVision) {
        return new Look(place, new CavernSky.View(name, DUSK, EAST, pitch), nightVision);
    }

    /**
     * Carves the breach, cuts the render distance, and stands the camera in turn at each of {@link #LOOKS} that stands at one of
     * {@code places}, flooding the shaft before the first from under the water. {@code beforeFlood} runs once the dry looks are done,
     * without night vision (a fall down the dry shaft, for the scenario), and the camera is left without night vision.
     */
    public static void tour(
            ClientGameTestContext context,
            TestServerContext server,
            Set<Place> places,
            AtLook atLook,
            Runnable beforeFlood) {
        server.runOnServer(UnbuiltDepthViews::carve);
        ClientChecks.withRenderDistance(context, ShaftViews.RENDER_DISTANCE, () -> {
            boolean flooded = false;
            try {
                for (Look look : LOOKS) {
                    if (!places.contains(look.place())) {
                        continue;
                    }
                    if (look.place() == Place.UNDERWATER && !flooded) {
                        ShaftViews.nightVision(server, false);
                        beforeFlood.run();
                        server.runOnServer(UnbuiltDepthViews::flood);
                        flooded = true;
                    }
                    ShaftViews.nightVision(server, look.nightVision());
                    BlockPos feet = server.computeOnServer(s -> feet(level(s), look.place()));
                    CavernSky.stand(context, server, UnbuiltDepthViews::level, feet, look.view());
                    if (look.place() == Place.UNDERWATER) {
                        ClientWait.until(
                                context,
                                "the camera under the water of the flooded shaft",
                                client -> client.player != null && client.player.isUnderWater());
                    }
                    atLook.at(look);
                }
            } finally {
                ShaftViews.nightVision(server, false);
            }
        });
    }

    /** The campaign level, which holds the last layer and the unbuilt depth under it. */
    public static ServerLevel level(MinecraftServer server) {
        return TestLayers.level(server, CampaignBands.lastLayer());
    }

    /** Where the camera's feet stand for a view at {@code place}. */
    public static BlockPos feet(ServerLevel level, Place place) {
        BlockPos unbuilt = new BlockPos(X, top(level) - UNDER_THE_CRUST, Z);
        return switch (place) {
            case BREACH -> new BlockPos(X, roomFloor(level) + ROOM_HEIGHT / 2, Z);
            case UNBUILT -> unbuilt;
            case UNDERWATER -> unbuilt.below(WATER_DROP + UNDER_THE_WATER);
        };
    }

    /** Where the camera's eye is for a view at {@code place}: a standing spectator's, over the middle of the block. */
    public static Vec3 eye(ServerLevel level, Place place) {
        return Vec3.atBottomCenterOf(feet(level, place)).add(0, EYE, 0);
    }

    /** The middles of the lamps in the shaft, which every view in the unbuilt depth must show where the camera maths puts them. */
    public static List<Vec3> lamps(ServerLevel level) {
        BlockPos camera = feet(level, Place.UNBUILT);
        List<Vec3> lamps = new ArrayList<>();
        for (int side : new int[] {-LAMP_OFFSET, LAMP_OFFSET}) {
            lamps.add(Vec3.atCenterOf(camera.offset(0, -LAMP_OFFSET, side)));
            lamps.add(Vec3.atCenterOf(camera.offset(LAMP_OFFSET, 1, side)));
        }
        return lamps;
    }

    /** The highest row of the unbuilt depth, right under the crust of the last layer. */
    private static int top(ServerLevel level) {
        return TestLayers.band(level, CampaignBands.lastLayer()).minY() - 1;
    }

    /** The row of the room in the last layer that stands on the crust. */
    private static int roomFloor(ServerLevel level) {
        return TestLayers.band(level, CampaignBands.lastLayer()).minY() + CampaignBands.crustRows();
    }

    private static void carve(MinecraftServer server) {
        ServerLevel level = level(server);
        int roomFloor = roomFloor(level);
        BlockPos camera = feet(level, Place.UNBUILT);
        int bottom = camera.getY() - SHAFT_DROP;
        RoomCarver.carve(
                level,
                new BlockPos(X - ROOM_RADIUS, roomFloor, Z - ROOM_RADIUS),
                new BlockPos(X + ROOM_RADIUS, roomFloor + ROOM_HEIGHT - 1, Z + ROOM_RADIUS),
                Blocks.AIR.defaultBlockState());
        // The breach: the crust broken under the room, and the shaft down from it.
        RoomCarver.carve(
                level,
                new BlockPos(X - SHAFT_RADIUS, bottom, Z - SHAFT_RADIUS),
                new BlockPos(X + SHAFT_RADIUS, roomFloor - 1, Z + SHAFT_RADIUS),
                Blocks.AIR.defaultBlockState());
        RoomCarver.carve(
                level,
                new BlockPos(X + SHAFT_RADIUS + 1, camera.getY(), Z - HALL_HALF_WIDTH),
                new BlockPos(X + SHAFT_RADIUS + HALL_LENGTH, camera.getY() + HALL_HEIGHT - 1, Z + HALL_HALF_WIDTH),
                Blocks.AIR.defaultBlockState());
        for (Vec3 lamp : lamps(level)) {
            level.setBlock(BlockPos.containing(lamp), Blocks.GLOWSTONE.defaultBlockState(), 3);
        }
    }

    /** Fills the shaft with water from its bottom to under its lamps. */
    private static void flood(MinecraftServer server) {
        ServerLevel level = level(server);
        BlockPos camera = feet(level, Place.UNBUILT);
        RoomCarver.carve(
                level,
                new BlockPos(X - SHAFT_RADIUS, camera.getY() - SHAFT_DROP, Z - SHAFT_RADIUS),
                new BlockPos(X + SHAFT_RADIUS, camera.getY() - WATER_DROP, Z + SHAFT_RADIUS),
                Blocks.WATER.defaultBlockState());
    }
}
