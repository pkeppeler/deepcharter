package io.github.pkeppeler.deepcharter.test.support;

import io.github.pkeppeler.deepcharter.layer.CampaignBands;
import io.github.pkeppeler.deepcharter.layer.LayerBlocks;
import io.github.pkeppeler.deepcharter.ore.OreType;
import io.github.pkeppeler.deepcharter.test.support.ClientLight.Box;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;
import javax.imageio.ImageIO;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LightBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.presets.WorldPreset;

/**
 * The scenes that compare the campaign type ({@code campaign_sample}) with today's layer dimension: a stone mass with a shaft or a
 * sealed room in it, a lit room, a biome filled over a scene, and the stand where the camera settles. {@code LayerAtmosphereClientTest}
 * measures them and the {@code m3-campaign-atmosphere} scenario shows them, so the two cannot drift. All of it runs in a world made with
 * {@link #TEST_PRESET}, which adds {@code campaign_sample}.
 */
public final class AtmosphereScenes {
    /** A zone of layer 1 whose biome sets the layer's atmosphere. */
    public static final String ZONE = "deepcharter:stone_benches";
    /** Far from the colony, in X and Z, with the cases of one model this far apart so that no biome blend reaches another. */
    public static final int SITE = 5_000;

    public static final int SPACING = 96;
    /** How far a biome is filled round a scene: the camera's attribute probe blends biomes over some blocks, and a margin short of it lets the next biome in. */
    public static final int MARGIN = 32;

    public static final int MASS = 6;
    public static final int SHAFT_DEPTH = 20;
    public static final int ROOM_HEIGHT = 5;
    /** Where the scenes of layer 1's band sit above the band's floor. */
    public static final int LAYER_1_SCENE_HEIGHT = 96;

    /** The sky clock's values the scenes are read at: the start of the timeline (dusk) and the middle of its night. */
    public static final long SKY_DUSK = 0;

    public static final long SKY_NIGHT = 144_000;

    /** The campaign world's test dimension: its type is the one the game will ship. */
    public static final ResourceKey<Level> CAMPAIGN =
            ResourceKey.create(Registries.DIMENSION, Identifier.fromNamespaceAndPath("deepcharter", "campaign_sample"));

    /** The client's default world has the shipped dimensions only; this preset adds {@code campaign_sample}. */
    public static final ResourceKey<WorldPreset> TEST_PRESET =
            ResourceKey.create(Registries.WORLD_PRESET, Identifier.withDefaultNamespace("flat_all_dimensions"));

    /** The camera waits this many ticks after it is placed: the lightmap and the fog ease toward the new place, and nothing reports when they have. */
    private static final int SETTLE_TICKS = 160;

    private static final int HALF_PATCH = 40;
    private static final int STEADY_SAMPLES = 3;
    private static final int MAX_SAMPLES = 60;
    private static final int SAMPLE_GAP_TICKS = 10;
    private static final double STEADY_TOLERANCE = 0.05;

    private AtmosphereScenes() {}

    /**
     * A mass of layer 1's rock with a 3 x 3 shaft in it, from {@code floor} up, in {@code dimension}: the same fill in every dimension, so
     * that two scenes of one shape differ in what is being compared and not in their rock. {@code open}: the shaft runs to the top of the
     * mass, so the sky reaches its floor (when the mass has open sky over it); otherwise it is a sealed room of the shaft's footprint.
     * {@code biome}, when present, is filled over the mass. Returns {@code floor}, where the camera stands.
     */
    public static BlockPos build(
            TestServerContext server,
            ResourceKey<Level> dimension,
            BlockPos floor,
            Optional<String> biome,
            boolean open) {
        server.runOnServer(s -> {
            ServerLevel level = ClientLight.serverLevel(s, dimension);
            holdLoaded(level, floor);
            RoomCarver.carve(
                    level,
                    floor.offset(-MASS, -1, -MASS),
                    floor.offset(MASS, SHAFT_DEPTH, MASS),
                    LayerBlocks.layerGround(1).defaultBlockState());
            RoomCarver.carve(
                    level,
                    floor.offset(-1, 0, -1),
                    floor.offset(1, open ? SHAFT_DEPTH : ROOM_HEIGHT - 1, 1),
                    Blocks.AIR.defaultBlockState());
        });
        biome.ifPresent(name -> fillBiome(
                server, dimension, floor, name, floor.getY() - MASS - 1 - MARGIN, floor.getY() + SHAFT_DEPTH + MARGIN));
        return floor;
    }

    /**
     * Holds the chunks of a scene loaded for the rest of the run. A room that is built and has no player in it is unloaded and saved at
     * once, and one saved before the light engine has used its light block is read back dark, on the server too: the same lit room read
     * 14.0 in one run and 9.7, the unlit room's value, in the next.
     */
    private static void holdLoaded(ServerLevel level, BlockPos floor) {
        for (int cx = (floor.getX() - MASS) >> 4; cx <= (floor.getX() + MASS) >> 4; cx++) {
            for (int cz = (floor.getZ() - MASS) >> 4; cz <= (floor.getZ() + MASS) >> 4; cz++) {
                // force-ok: a client test and an evidence scenario have no GameTestHelper to release through; the world
                // is discarded when
                // the run ends
                level.setChunkForced(cx, cz, true);
            }
        }
    }

    /** The floor of a scene in layer 1's band of the campaign type at {@code x}: the band's floor and {@link #LAYER_1_SCENE_HEIGHT}. */
    public static BlockPos layer1Floor(int x) {
        return new BlockPos(x, CampaignBands.layer(1).minY() + LAYER_1_SCENE_HEIGHT, SITE);
    }

    /** The floor of a scene in today's layer 1 dimension at {@code x}, as high above its floor as {@link #layer1Floor}'s. */
    public static BlockPos todayFloor(int x) {
        return TestLayers.pos(1, x, LAYER_1_SCENE_HEIGHT, SITE);
    }

    /**
     * A 7 x 7 room, lit by a light block of level 8 under the ceiling, in a mass of layer 1's rock, the same in every dimension. One ore
     * block is set in the ceiling, one in the north wall and one in the floor, at the same places in every room, so that a still shows
     * whether ore stays readable.
     */
    public static BlockPos buildLitRoom(
            TestServerContext server, ResourceKey<Level> dimension, BlockPos floor, Optional<String> biome) {
        return buildRoom(server, dimension, floor, biome, true);
    }

    /**
     * As {@link #buildLitRoom}, with the light block only if {@code lit}. A room that is to be dark is built dark: a light block that is
     * taken out of a room whose chunk stays loaded leaves the client reading its light.
     */
    public static BlockPos buildRoom(
            TestServerContext server,
            ResourceKey<Level> dimension,
            BlockPos floor,
            Optional<String> biome,
            boolean lit) {
        server.runOnServer(s -> {
            ServerLevel level = ClientLight.serverLevel(s, dimension);
            holdLoaded(level, floor);
            RoomCarver.carve(
                    level,
                    floor.offset(-MASS, -1, -MASS),
                    floor.offset(MASS, ROOM_HEIGHT, MASS),
                    LayerBlocks.layerGround(1).defaultBlockState());
            RoomCarver.carve(
                    level,
                    floor.offset(-3, 0, -3),
                    floor.offset(3, ROOM_HEIGHT - 1, 3),
                    Blocks.AIR.defaultBlockState());
            if (lit) {
                level.setBlock(
                        floor.above(ROOM_HEIGHT - 2),
                        Blocks.LIGHT.defaultBlockState().setValue(LightBlock.LEVEL, 8),
                        3);
            }
            BlockState ore = OreBlocks.of(OreType.IRONIUM).defaultBlockState();
            level.setBlock(floor.offset(1, ROOM_HEIGHT, 0), ore, 3);
            level.setBlock(floor.offset(1, 1, -4), ore, 3);
            level.setBlock(floor.offset(0, -1, -1), ore, 3);
        });
        biome.ifPresent(name -> fillBiome(
                server, dimension, floor, name, floor.getY() - MASS - 1 - MARGIN, floor.getY() + SHAFT_DEPTH + MARGIN));
        return floor;
    }

    /** The id of the biome the server holds at {@code pos} in {@code dimension}. */
    public static String biomeAt(TestServerContext server, ResourceKey<Level> dimension, BlockPos pos) {
        return server.computeOnServer(s -> ClientLight.serverLevel(s, dimension)
                .getBiome(pos)
                .unwrapKey()
                .orElseThrow()
                .identifier()
                .toString());
    }

    /** Puts out the light of a room that {@link #buildLitRoom} built at {@code floor}: an unlit room shows the ambient floor alone. */
    public static void putOutTheLight(TestServerContext server, ResourceKey<Level> dimension, BlockPos floor) {
        server.runOnServer(s -> ClientLight.serverLevel(s, dimension)
                .setBlock(floor.above(ROOM_HEIGHT - 2), Blocks.AIR.defaultBlockState(), 3));
    }

    /**
     * Fills {@code biome} over {@link #MARGIN} blocks round the scene, from {@code bottomY} to {@code topY}, in slabs (a command fills at
     * most 32,768 blocks, counted in whole 4-block cells). The fill is bounded, not the world's whole height: the campaign world is 2,352 tall.
     */
    public static void fillBiome(TestServerContext server, BlockPos floor, String biome, int bottomY, int topY) {
        fillBiome(server, CAMPAIGN, floor, biome, bottomY, topY);
    }

    public static void fillBiome(
            TestServerContext server,
            ResourceKey<Level> dimension,
            BlockPos floor,
            String biome,
            int bottomY,
            int topY) {
        for (int y = bottomY; y <= topY; y += 4) {
            server.runCommand("execute in %s run fillbiome %d %d %d %d %d %d %s"
                    .formatted(
                            dimension.identifier(),
                            floor.getX() - MARGIN,
                            y,
                            floor.getZ() - MARGIN,
                            floor.getX() + MARGIN,
                            Math.min(y + 3, topY),
                            floor.getZ() + MARGIN,
                            biome));
        }
    }

    /** The free block over the ground of the campaign type at {@code x}, {@link #SITE}, with the chunks round it loaded. */
    public static BlockPos ground(TestServerContext server, int x) {
        return server.computeOnServer(s -> {
            ServerLevel level = ClientLight.serverLevel(s, CAMPAIGN);
            for (int cx = (x - MARGIN) >> 4; cx <= (x + MARGIN) >> 4; cx++) {
                for (int cz = (SITE - MARGIN) >> 4; cz <= (SITE + MARGIN) >> 4; cz++) {
                    // force-ok: a client test has no GameTestHelper to release through; the world is discarded when the
                    // test ends
                    level.setChunkForced(cx, cz, true);
                    level.getChunk(cx, cz);
                }
            }
            return new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, SITE), SITE);
        });
    }

    /**
     * Stands the first player, a spectator, on {@code floor} in {@code dimension} facing {@code yaw} and {@code pitch}, waits for the
     * client to hold the chunks round the scene and for its light to match the server's, and lets the lightmap settle. {@code shot} names
     * the view in a failure.
     */
    public static void stand(
            ClientGameTestContext context,
            TestServerContext server,
            ResourceKey<Level> dimension,
            BlockPos floor,
            float yaw,
            float pitch,
            String shot) {
        server.runOnServer(s -> s.getPlayerList()
                .getPlayers()
                .getFirst()
                .teleportTo(
                        ClientLight.serverLevel(s, dimension),
                        floor.getX() + 0.5,
                        floor.getY(),
                        floor.getZ() + 0.5,
                        Set.of(),
                        yaw,
                        pitch,
                        true));
        Box around = Box.around(floor, MASS, 1, SHAFT_DEPTH);
        ClientWait.until(
                context,
                "the client in " + dimension.identifier() + " holding the chunks at " + floor.toShortString(),
                client -> ClientLight.clientLevel(client).dimension().equals(dimension)
                        && ClientLight.chunksPresent(ClientLight.clientLevel(client), around),
                client -> "no chunks at " + floor.toShortString());
        ClientChecks.requireNone(
                "the light at " + shot, ClientLight.settledMismatch(context, server, dimension, around));
        // tick-wait: the lightmap and the fog ease toward the new place over a few ticks, and nothing reports when they
        // have
        context.waitTicks(SETTLE_TICKS);
        awaitSteadyPicture(context, shot);
    }

    /**
     * Waits until the picture stops changing: ticks run faster than frames on a slow renderer, and the lightmap eases frame by frame, so a
     * fixed wait read the same room at two brightnesses in two runs (11.2 and 24.9 at the ceiling). The picture is steady when the
     * middle of the screen reads the same mean luminance in {@link #STEADY_SAMPLES} samples in a row.
     */
    private static void awaitSteadyPicture(ClientGameTestContext context, String shot) {
        double last = -1;
        int steady = 0;
        for (int sample = 0; sample < MAX_SAMPLES && steady < STEADY_SAMPLES; sample++) {
            double now = centreLuma(context.takeScreenshot("settle"));
            steady = Math.abs(now - last) <= STEADY_TOLERANCE ? steady + 1 : 0;
            last = now;
            // tick-wait: the next sample is a few frames later; nothing reports that the lightmap has stopped easing
            context.waitTicks(SAMPLE_GAP_TICKS);
        }
        ClientChecks.require(
                steady >= STEADY_SAMPLES,
                "the picture at " + shot + " was still changing after " + MAX_SAMPLES + " samples, last " + last);
    }

    /** The mean luminance (0 to 255) of the 80 x 80 pixels in the middle of the picture in {@code file}. */
    private static double centreLuma(Path file) {
        BufferedImage image;
        try {
            image = ImageIO.read(file.toFile());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        double sum = 0;
        int count = 0;
        for (int y = image.getHeight() / 2 - HALF_PATCH; y < image.getHeight() / 2 + HALF_PATCH; y++) {
            for (int x = image.getWidth() / 2 - HALF_PATCH; x < image.getWidth() / 2 + HALF_PATCH; x++) {
                int rgb = image.getRGB(x, y);
                sum += 0.299 * ((rgb >> 16) & 0xFF) + 0.587 * ((rgb >> 8) & 0xFF) + 0.114 * (rgb & 0xFF);
                count++;
            }
        }
        return sum / count;
    }
}
