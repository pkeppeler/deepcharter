package io.github.pkeppeler.deepcharter.test;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.layer.LayerChain;
import io.github.pkeppeler.deepcharter.test.support.ClientChecks;
import io.github.pkeppeler.deepcharter.test.support.ClientLight;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.FarChunks;
import io.github.pkeppeler.deepcharter.test.support.RoomCarver;
import io.github.pkeppeler.deepcharter.test.support.ScreenProjection;
import io.github.pkeppeler.deepcharter.test.support.ShaftViews;
import io.github.pkeppeler.deepcharter.test.support.TestLayers;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.imageio.ImageIO;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.commands.FillBiomeCommand;
import net.minecraft.server.level.ParticleStatus;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Client GameTest for #333: looking through a hole at the bottom of a layer shows rock and darkness, never the void behind it. The
 * void is drawn in the fog colour (and a night-vision potion lifts that colour to full brightness), and on the surface in the
 * sky's. Each of the surface, layer 1 and layer 2 gets a lit room with a 9 x 9 hole through its floor (the crust broken in a layer, a
 * bored shaft on the surface), seen from far above, straight down, from high up at the side, and from the floor level at the side,
 * with and without night vision. The pixels of the hole must be dark, and in the same frame a lit block must show where the
 * camera maths says it is, so a black or blank frame, a camera in rock, or a patch that missed the hole cannot pass. A pod that is
 * under the bottom of the world, as a crossing pod is for its last ticks, must still show over the hole.
 *
 * <p>Then, for #1188, the camera looks straight down a wide shaft deeper than the render distance, to the floor of layer 1, to the floor
 * of layer 2, and to the bottom of the world, with and without night vision. The client draws nothing past the render distance, and the
 * shaft's walls fade into the fog before they end there, so the open middle of the picture must match the walls' fogged ends in the
 * same frame: no square of black, and no sky, with an edge against them.
 *
 * <p>The bottom of the world is the unbuilt depth, whose fog is black since #1192, so the void would read dark there with no cover at
 * all, and the breach shaft's box would match its walls' black ends trivially. The #333 room and column, and the air round the breach
 * shaft's eye, are given a layer's biome, whose fog is not black and which night vision lifts. Without {@code BreachVoidCover} the
 * far-above and night-vision #333 views fail; the breach shaft's view checks the lifted cover's edge against that fog, not whether
 * the cover is there.
 */
public class BreachVoidClientTest implements FabricClientGameTest {
    private static final Logger LOGGER = LoggerFactory.getLogger(BreachVoidClientTest.class);
    private static final int X = 2000;
    private static final int Z = 2000;
    /** The hole is a 9 x 9 shaft three blocks deep (the crust) under a room whose floor is that many blocks above the bottom of the world. */
    private static final int SHAFT_DEPTH = 3;

    private static final int HOLE_RADIUS = 4;
    private static final int ROOM_RADIUS = 10;
    private static final int ROOM_HEIGHT = 6;
    /** A column over the hole, tall enough to look down it from more than 32 blocks (where the vanilla fade to black of the void ends). */
    private static final int COLUMN_HEIGHT = 50;
    /** Each channel of a pixel of the hole is at or under this: darkness, not a fog or sky colour. */
    private static final int DARK = 0x10;
    /** A lit block shows a pixel brighter than this in some channel. */
    private static final int LIT = 0x40;
    /**
     * The biome given round the #333 room and the breach shaft: a zone of layer 1, whose fog ({@code #1e1610}) is over {@link #DARK} on its
     * red channel, and which night vision lifts to full brightness. Without the cover the void reads in it.
     */
    private static final ResourceKey<Biome> NOT_BLACK_FOG =
            ResourceKey.create(Registries.BIOME, Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "deep_claim"));
    /** The biome box reaches this far past the blocks a view holds: past the camera's blend of the biomes round it. */
    private static final int BIOME_MARGIN = 16;
    /** The biome box is filled this many rows at a time, under {@code fillbiome}'s block limit. */
    private static final int BIOME_SLAB = 8;
    /** The patch of pixels read at the hole, either side of its middle. */
    private static final int PATCH = 6;
    /** A lamp (glowstone) stands on the room floor this far out from the middle, at the four compass points. */
    private static final int LAMP_OFFSET = 6;

    /** How far from its predicted pixel a lit landmark may show. */
    private static final int WINDOW = 30;

    private record View(String name, Vec3 eye, boolean bothLightings) {}

    /** The deep shafts of {@link ShaftViews} start this far south of the hole, out of its drawn chunks. */
    private static final int DEEP_SHAFTS_SOUTH = 200;
    /**
     * The box at the middle of a deep view reaches this share of the way out to where the walls' drawn ends show (about 12 degrees off the
     * nadir), so every ray in it passes them.
     */
    private static final double BOX_SHARE = 0.95;
    /**
     * The band the box is compared with starts this share past where the walls' drawn ends show, and stops this share short of where the
     * walls first take the whole render-distance fog: the walls' fogged ends.
     */
    private static final double BAND_MARGIN = 0.05;
    /**
     * A pixel of the box may differ from the band's median colour by this much on a channel: the worst measured was 1, in every view of
     * five green runs (Linux, software GL), plus 1 to spare. The old black cover differed by 37 to 255.
     */
    private static final int FOG_TOLERANCE = 2;

    @Override
    public void runTest(ClientGameTestContext context) {
        ClientTestLog.start(this);
        List<String> failures = new ArrayList<>();
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            ClientWait.until(
                    context, "the client in the world", client -> client.player != null && client.level != null);
            // No crosshair over the middle of the picture, where the hole is. MINIMAL drops the sky's ambient dust
            // motes, so none lights the patch.
            // The client is shared with the next test, so the helper puts the HUD and the particles back.
            ClientChecks.withHudHidden(context, ParticleStatus.MINIMAL, () -> {
                singleplayer.getServer().runOnServer(server -> {
                    ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
                    player.setGameMode(GameType.CREATIVE);
                    player.setNoGravity(true);
                    player.getAbilities().flying = true;
                    player.onUpdateAbilities();
                });
                // The void is under the bottom of the whole world: in the campaign level a hole through the floor of a
                // band shows the next
                // band's rock. TODO(#819): the pod 1 block under the bottom.
                for (int layer : new int[] {LayerChain.SURFACE}) {
                    int minY = singleplayer
                            .getServer()
                            .computeOnServer(server -> openHole(TestLayers.level(server, layer)));
                    ResourceKey<Level> level = singleplayer
                            .getServer()
                            .computeOnServer(
                                    server -> TestLayers.level(server, layer).dimension());
                    Vec3 hole = new Vec3(X + 0.5, minY + 0.5, Z + 0.5);
                    ClientLight.Box room = ClientLight.Box.around(
                            BlockPos.containing(hole), ROOM_RADIUS, 0, SHAFT_DEPTH + COLUMN_HEIGHT);
                    singleplayer
                            .getServer()
                            .runOnServer(server -> giveAFogThatIsNotBlack(TestLayers.level(server, layer), room));
                    View[] views = {
                        new View(
                                "far-above", new Vec3(X + 0.5, minY + SHAFT_DEPTH + COLUMN_HEIGHT - 4, Z + 0.5), false),
                        new View("down", new Vec3(X + 0.5, minY + SHAFT_DEPTH + 8, Z + 0.5), true),
                        new View("aslant", new Vec3(X - 2.5, minY + SHAFT_DEPTH + 5.5, Z + 0.5), true),
                        new View("shallow", new Vec3(X - 6.5, minY + SHAFT_DEPTH + 1.7, Z + 0.5), true)
                    };
                    for (boolean nightVision : new boolean[] {false, true}) {
                        ShaftViews.nightVision(singleplayer.getServer(), nightVision);
                        for (View view : views) {
                            if (nightVision && !view.bothLightings()) {
                                continue;
                            }
                            String name =
                                    "breach-void-world-bottom-" + view.name() + (nightVision ? "-night-vision" : "");
                            Path shot = lookUntilLit(
                                    context, singleplayer, level, view.eye(), hole, room, landmarks(minY), false, name);
                            holeProblem(read(shot), name).ifPresent(failures::add);
                        }
                    }
                }
                List<ShaftViews.Shaft> shafts = singleplayer.getServer().computeOnServer(server -> {
                    ServerLevel campaign = TestLayers.level(server, 1);
                    List<ShaftViews.Shaft> opened = ShaftViews.open(campaign, X, Z + DEEP_SHAFTS_SOUTH);
                    for (ShaftViews.Shaft shaft : opened) {
                        // level-height: the breach shaft is the one at the bottom of the world
                        if (shaft.floorY() == campaign.getMinY()) {
                            giveAFogThatIsNotBlack(campaign, shaft.held());
                        }
                    }
                    return opened;
                });
                ClientChecks.withRenderDistance(context, ShaftViews.RENDER_DISTANCE, () -> {
                    for (boolean nightVision : new boolean[] {false, true}) {
                        ShaftViews.nightVision(singleplayer.getServer(), nightVision);
                        for (ShaftViews.Shaft shaft : shafts) {
                            String name = "breach-void-deep-" + shaft.name() + (nightVision ? "-night-vision" : "");
                            Path shot = lookUntilLit(
                                    context,
                                    singleplayer,
                                    shaft.level(),
                                    shaft.eye(),
                                    shaft.below(),
                                    shaft.held(),
                                    shaft.landmarks(),
                                    true,
                                    name);
                            fogProblem(context, read(shot), shaft, name).ifPresent(failures::add);
                        }
                    }
                });
            });
        }
        // Every view is taken first, so one run names all the views that show the void.
        if (!failures.isEmpty()) {
            throw new AssertionError(String.join("\n", failures));
        }
    }

    /**
     * Carves a lit room, and a column over its middle, with a 9 x 9 hole through the floor to the void. Four glowstone lamps stand on the
     * floor round it. Returns the bottom Y.
     */
    private static int openHole(ServerLevel level) {
        int minY = level.getMinY(); // level-height: the void is under the bottom of the whole world, not of a layer
        RoomCarver.carve(
                level,
                new BlockPos(X - ROOM_RADIUS, minY + SHAFT_DEPTH, Z - ROOM_RADIUS),
                new BlockPos(X + ROOM_RADIUS, minY + SHAFT_DEPTH + ROOM_HEIGHT, Z + ROOM_RADIUS),
                Blocks.AIR.defaultBlockState());
        RoomCarver.carve(
                level,
                new BlockPos(X - HOLE_RADIUS, minY + SHAFT_DEPTH, Z - HOLE_RADIUS),
                new BlockPos(X + HOLE_RADIUS, minY + SHAFT_DEPTH + COLUMN_HEIGHT, Z + HOLE_RADIUS),
                Blocks.AIR.defaultBlockState());
        BlockPos low = new BlockPos(X - HOLE_RADIUS, minY, Z - HOLE_RADIUS);
        BlockPos high = new BlockPos(X + HOLE_RADIUS, minY + SHAFT_DEPTH - 1, Z + HOLE_RADIUS);
        // The bottom of the world is unbuilt rock with no crust: the hole is a shaft bored to the bottom.
        RoomCarver.carve(level, low, high, Blocks.AIR.defaultBlockState());
        for (BlockPos lamp : lamps(minY)) {
            level.setBlock(lamp, Blocks.GLOWSTONE.defaultBlockState(), 3);
        }
        level.setBlock(holeLamp(minY), Blocks.GLOWSTONE.defaultBlockState(), 3);
        level.setBlock(wallLamp(minY), Blocks.GLOWSTONE.defaultBlockState(), 3);
        return minY;
    }

    /**
     * Gives the blocks of {@code held}, and {@link #BIOME_MARGIN} round them, {@link #NOT_BLACK_FOG}'s biome. The bottom of the world is
     * the unbuilt depth, whose fog and sky are black (#1192), and a void behind a hole reads in the fog colour, so there it would read dark
     * with the cover gone too. A fog that is not black, and that night vision lifts, is what the cover hides. The camera blends the biomes
     * round it, hence the margin. Each chunk is loaded first ({@code fillbiome} refuses an unloaded one), and the fill goes in slabs under
     * the command's block limit.
     */
    private static void giveAFogThatIsNotBlack(ServerLevel level, ClientLight.Box held) {
        Holder<Biome> biome =
                level.registryAccess().lookupOrThrow(Registries.BIOME).getOrThrow(NOT_BLACK_FOG);
        BlockPos low = held.min().offset(-BIOME_MARGIN, -BIOME_MARGIN, -BIOME_MARGIN);
        BlockPos high = held.max().offset(BIOME_MARGIN, BIOME_MARGIN, BIOME_MARGIN);
        for (int cx = SectionPos.blockToSectionCoord(low.getX());
                cx <= SectionPos.blockToSectionCoord(high.getX());
                cx++) {
            for (int cz = SectionPos.blockToSectionCoord(low.getZ());
                    cz <= SectionPos.blockToSectionCoord(high.getZ());
                    cz++) {
                level.getChunk(cx, cz);
            }
        }
        // level-height: the box may reach under the bottom of the whole world, where there is no biome to fill
        for (int y = Math.max(low.getY(), level.getMinY()); y <= high.getY(); y += BIOME_SLAB) {
            FillBiomeCommand.fill(
                            level,
                            new BlockPos(low.getX(), y, low.getZ()),
                            new BlockPos(high.getX(), Math.min(high.getY(), y + BIOME_SLAB - 1), high.getZ()),
                            biome)
                    .right()
                    .ifPresent(e -> {
                        throw new AssertionError("could not fill the biome round the view: " + e.getMessage(), e);
                    });
        }
    }

    private static List<BlockPos> lamps(int minY) {
        int y = minY + SHAFT_DEPTH;
        return List.of(
                new BlockPos(X + LAMP_OFFSET, y, Z),
                new BlockPos(X - LAMP_OFFSET, y, Z + 3),
                new BlockPos(X, y, Z + LAMP_OFFSET),
                new BlockPos(X, y, Z - LAMP_OFFSET));
    }

    /** A lamp in the east wall of the hole, for a camera that looks steeply down and sees no floor lamp. */
    private static BlockPos holeLamp(int minY) {
        return new BlockPos(X + HOLE_RADIUS + 1, minY + 1, Z);
    }

    /** A lamp in the wall of the column, for a camera high in it, where the floor lamps are out of sight under the room's roof. */
    private static BlockPos wallLamp(int minY) {
        return new BlockPos(X + HOLE_RADIUS + 1, minY + SHAFT_DEPTH + COLUMN_HEIGHT - 12, Z);
    }

    /** Points that are lit when seen: the tops of the floor lamps, and the faces of the two wall lamps that look into the hole and the column. */
    private static List<Vec3> landmarks(int minY) {
        List<Vec3> points = new ArrayList<>();
        for (BlockPos lamp : lamps(minY)) {
            points.add(new Vec3(lamp.getX() + 0.5, lamp.getY() + 1, lamp.getZ() + 0.5));
        }
        for (BlockPos lamp : List.of(holeLamp(minY), wallLamp(minY))) {
            points.add(new Vec3(lamp.getX(), lamp.getY() + 0.5, lamp.getZ() + 0.5));
        }
        return points;
    }

    /** Stands the camera at {@code eye} looking at {@code target} until the view has rendered, and takes the screenshot. */
    private static Path look(
            ClientGameTestContext context,
            TestSingleplayerContext singleplayer,
            ResourceKey<Level> level,
            Vec3 eye,
            Vec3 target,
            ClientLight.Box held,
            String name) {
        ShaftViews.stand(context, singleplayer.getServer(), level, eye, target, held, name);
        // tick-wait: the fog colour is computed per frame, and a few frames must pass after the last chunk section
        // is built
        context.waitTicks(10);
        return context.takeScreenshot(name);
    }

    /**
     * Takes the view until the frame is a lit picture of the room. The client reports its sections rendered and the light settled
     * before the pictures of a freshly carved room are true (the first frames can be black, from stale meshes or light), so a frame
     * with no lit landmark where the camera maths puts one is taken again, up to the wall-clock deadline of {@link ClientWait}. The
     * hole is judged only on a frame that passed. With {@code againstFog}, a landmark shows when it stands out from the frame's fog
     * colour rather than from black (a frame of fog alone, with no walls drawn, is brighter than {@link #LIT} too), and every landmark on
     * the screen must show.
     */
    private static Path lookUntilLit(
            ClientGameTestContext context,
            TestSingleplayerContext singleplayer,
            ResourceKey<Level> level,
            Vec3 eye,
            Vec3 target,
            ClientLight.Box held,
            List<Vec3> landmarks,
            boolean againstFog,
            String name) {
        FarChunks.Deadline deadline = FarChunks.deadline();
        while (true) {
            Path shot = look(context, singleplayer, level, eye, target, held, name);
            Optional<int[]> fog = againstFog ? Optional.of(fog(context)) : Optional.empty();
            Optional<String> problem = litProblem(context, read(shot), eye, target, landmarks, fog, name);
            if (problem.isEmpty()) {
                return shot;
            }
            if (deadline.expired()) {
                throw new AssertionError("Timed out after " + FarChunks.WAIT_SECONDS + " s: " + problem.get());
            }
            // tick-wait: a few more frames for the client's meshes and light to catch up with the carved room
            context.waitTicks(10);
        }
    }

    /**
     * Why the frame is not a lit picture of the room, or empty. A landmark (a lamp, or a lit wall block) that the camera maths puts on
     * the screen must show a lit pixel within {@link #WINDOW} pixels of where it should be, so a black or blank frame or a camera in
     * rock cannot pass. The window is wide because the maths is a pinhole's, and a block face has size.
     */
    private static Optional<String> litProblem(
            ClientGameTestContext context,
            BufferedImage image,
            Vec3 eye,
            Vec3 target,
            List<Vec3> landmarks,
            Optional<int[]> fog,
            String name) {
        float[] angles = ShaftViews.anglesTo(eye, target);
        double fov =
                context.computeOnClient(client -> (double) client.options.fov().get());
        boolean onScreen = false;
        boolean anyLit = false;
        boolean allLit = true;
        for (Vec3 landmark : landmarks) {
            Optional<int[]> pixel = ScreenProjection.project(image, eye, angles[0], angles[1], fov, landmark);
            if (pixel.isPresent()) {
                onScreen = true;
                boolean lit = litNear(image, pixel.get()[0], pixel.get()[1], fog);
                anyLit |= lit;
                allLit &= lit;
            }
        }
        // Against the fog every landmark must show, one in each side wall, so a frame with a wall not yet drawn is
        // taken again.
        if (fog.isPresent() ? onScreen && allLit : anyLit) {
            return Optional.empty();
        }
        return Optional.of(
                onScreen
                        ? name
                                + ": the frame is dark where a lamp should show: a black or blank frame, or a camera in rock"
                        : name
                                + ": no lamp is on the screen by the camera maths, so the frame has nothing to prove it is a picture of the room");
    }

    /** Whether a pixel near ({@code px}, {@code py}) is brighter than {@link #LIT}, or stands out from {@code fog} by more than it. */
    private static boolean litNear(BufferedImage image, int px, int py, Optional<int[]> fog) {
        for (int y = Math.max(0, py - WINDOW); y <= Math.min(image.getHeight() - 1, py + WINDOW); y++) {
            for (int x = Math.max(0, px - WINDOW); x <= Math.min(image.getWidth() - 1, px + WINDOW); x++) {
                int rgb = image.getRGB(x, y);
                if (fog.isPresent() ? delta(rgb, fog.get()) > LIT : brightest(rgb) > LIT) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * The camera looks at the middle of the hole, so the patch of pixels at the middle of the screen holds only darkness:
     * no pixel brighter than {@link #DARK} in any channel.
     */
    private static Optional<String> holeProblem(BufferedImage image, String name) {
        List<Integer> patch = centrePatch(image);
        int bright = (int) patch.stream().filter(channel -> channel > DARK).count();
        int brightest = patch.stream().max(Integer::compare).orElseThrow();
        if (bright > 0) {
            return Optional.of(
                    "%s: the hole shows the void, not darkness: %d pixels of its middle are brighter than %d in a channel, up to %d"
                            .formatted(name, bright, DARK, brightest));
        }
        return Optional.empty();
    }

    /**
     * The box at the middle of a deep view must match the walls' fogged ends in the same frame. The camera looks straight down, so the walls'
     * drawn ends show as a square round the middle, {@link ShaftViews#RADIUS} over their drop out, and the walls take the whole
     * render-distance fog from where they are that fog's end under the eye. The band between is the walls' fogged ends: its median colour is
     * the reference. Every pixel of the box, all inside the drawn ends, must be within {@link #FOG_TOLERANCE} of it on each channel. A black
     * square there, or a sky that is not the fog, is an edge.
     */
    private static Optional<String> fogProblem(
            ClientGameTestContext context, BufferedImage image, ShaftViews.Shaft shaft, String name) {
        float[] frame = context.computeOnClient(client -> {
            var camera = client.gameRenderer.gameRenderState().levelRenderState.cameraRenderState;
            return new float[] {Math.abs(camera.projectionMatrix.m11()), camera.fogData.renderDistanceEnd};
        });
        // Pixels from the middle per unit of the tangent off the nadir, on either axis.
        double scale = image.getHeight() / 2.0 * frame[0];
        double drawnEnd = scale * ShaftViews.RADIUS / shaft.drawnEndDrop();
        double fogged = scale * ShaftViews.RADIUS / frame[1];
        int box = (int) Math.floor(drawnEnd * BOX_SHARE);
        int bandIn = (int) Math.ceil(drawnEnd * (1 + BAND_MARGIN));
        int bandOut = (int) Math.floor(fogged * (1 - BAND_MARGIN));
        if (bandOut <= bandIn) {
            throw new AssertionError("%s: no band of fogged wall ends between %d and %d px from the middle"
                    .formatted(name, bandIn, bandOut));
        }
        int cx = image.getWidth() / 2;
        int cy = image.getHeight() / 2;
        List<int[]> band = new ArrayList<>();
        for (int y = cy - bandOut; y <= cy + bandOut; y++) {
            for (int x = cx - bandOut; x <= cx + bandOut; x++) {
                if (Math.max(Math.abs(x - cx), Math.abs(y - cy)) >= bandIn) {
                    int rgb = image.getRGB(x, y);
                    band.add(new int[] {(rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF});
                }
            }
        }
        int[] reference = {median(band, 0), median(band, 1), median(band, 2)};
        int off = 0;
        int worst = 0;
        int worstRgb = 0;
        int total = 0;
        for (int y = cy - box; y <= cy + box; y++) {
            for (int x = cx - box; x <= cx + box; x++) {
                int rgb = image.getRGB(x, y);
                int delta = delta(rgb, reference);
                total++;
                if (delta > FOG_TOLERANCE) {
                    off++;
                }
                if (delta > worst) {
                    worst = delta;
                    worstRgb = rgb;
                }
            }
        }
        LOGGER.info(
                "{}: box {} px each way, band {} to {} px, walls' fogged ends #{}, worst {} (#{})",
                name,
                box,
                bandIn,
                bandOut,
                hex(reference),
                worst,
                "%06x".formatted(worstRgb & 0xFFFFFF));
        if (off > 0) {
            return Optional.of(
                    "%s: the bottom of the shaft has an edge: %d of %d pixels of the middle differ from the walls' fogged ends #%s by more than %d, up to %d (#%06x)"
                            .formatted(name, off, total, hex(reference), FOG_TOLERANCE, worst, worstRgb & 0xFFFFFF));
        }
        return Optional.empty();
    }

    private static int median(List<int[]> pixels, int channel) {
        return pixels.stream().mapToInt(p -> p[channel]).sorted().toArray()[pixels.size() / 2];
    }

    private static int delta(int rgb, int[] reference) {
        return Math.max(
                Math.abs(((rgb >> 16) & 0xFF) - reference[0]),
                Math.max(Math.abs(((rgb >> 8) & 0xFF) - reference[1]), Math.abs((rgb & 0xFF) - reference[2])));
    }

    private static String hex(int[] rgb) {
        return "%02x%02x%02x".formatted(rgb[0], rgb[1], rgb[2]);
    }

    /** The fog colour of the client's last frame: what terrain fades into, and what the shader mixes into it. */
    private static int[] fog(ClientGameTestContext context) {
        return context.computeOnClient(client -> {
            var colour = client.gameRenderer.gameRenderState().levelRenderState.cameraRenderState.fogData.color;
            return new int[] {channel(colour.x()), channel(colour.y()), channel(colour.z())};
        });
    }

    private static int channel(float value) {
        return Math.round(Mth.clamp(value, 0f, 1f) * 255);
    }

    /** The brightest channel of each pixel of the {@link #PATCH} square at the middle of the picture. */
    private static List<Integer> centrePatch(BufferedImage image) {
        List<Integer> channels = new ArrayList<>();
        for (int y = image.getHeight() / 2 - PATCH; y <= image.getHeight() / 2 + PATCH; y++) {
            for (int x = image.getWidth() / 2 - PATCH; x <= image.getWidth() / 2 + PATCH; x++) {
                channels.add(brightest(image.getRGB(x, y)));
            }
        }
        return channels;
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
