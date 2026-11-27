package io.github.pkeppeler.deepcharter.test.support;

import io.github.pkeppeler.deepcharter.layer.CampaignBands;
import io.github.pkeppeler.deepcharter.layer.LayerBlocks;
import io.github.pkeppeler.deepcharter.ore.OreType;
import io.github.pkeppeler.deepcharter.test.support.ClientLight.Box;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import javax.imageio.ImageIO;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LightBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
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

    /**
     * Today's layer 1 dimension, which the scenes compare the campaign type with. {@code TestLayers} never resolves to a layer
     * dimension, so this names it. The layer dimensions stay as the base of the dimension seam (ADR 0085).
     */
    public static final ResourceKey<Level> TODAY =
            ResourceKey.create(Registries.DIMENSION, Identifier.fromNamespaceAndPath("deepcharter", "layer_1"));

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
     * {@code biome}, when present, is filled over the mass, its chunks held in {@code holds}. Returns {@code floor}, where the camera stands.
     */
    public static BlockPos build(
            TestServerContext server,
            Holds holds,
            ResourceKey<Level> dimension,
            BlockPos floor,
            Optional<String> biome,
            boolean open) {
        server.runOnServer(s -> {
            ServerLevel level = ClientLight.serverLevel(s, dimension);
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
        biome.ifPresent(name -> fillBiomeAround(server, holds, dimension, floor, name));
        return floor;
    }

    /** The floor of a scene in layer 1's band of the campaign type at {@code x}: the band's floor and {@link #LAYER_1_SCENE_HEIGHT}. */
    public static BlockPos layer1Floor(int x) {
        return new BlockPos(x, CampaignBands.layer(1).minY() + LAYER_1_SCENE_HEIGHT, SITE);
    }

    /** The floor of a scene in today's layer 1 dimension at {@code x}, as high above its floor as {@link #layer1Floor}'s. */
    public static BlockPos todayFloor(int x) {
        // The layer dimension's own Ys start at 0, so the height over its floor is the Y.
        return new BlockPos(x, LAYER_1_SCENE_HEIGHT, SITE);
    }

    /**
     * A 7 x 7 room in a mass of layer 1's rock, the same in every dimension, with a light block of level 8 under the ceiling only if
     * {@code lit}: a room that is to be dark is built dark, because a light block taken out of a room whose chunk stays loaded leaves the
     * client reading its light. One ore block is set in the ceiling, one in the north wall and one in the floor, at the same places in every
     * room, so that a still shows whether ore stays readable.
     */
    public static BlockPos buildRoom(
            TestServerContext server,
            Holds holds,
            ResourceKey<Level> dimension,
            BlockPos floor,
            Optional<String> biome,
            boolean lit) {
        server.runOnServer(s -> {
            ServerLevel level = ClientLight.serverLevel(s, dimension);
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
        biome.ifPresent(name -> fillBiomeAround(server, holds, dimension, floor, name));
        return floor;
    }

    /** Both scenes of a pair are in {@link #ZONE}, so that the biome is not what the pair compares. */
    public static void requireZone(TestServerContext server, ResourceKey<Level> dimension, BlockPos at) {
        String biome = biomeAt(server, dimension, at);
        ClientChecks.require(
                biome.equals(ZONE),
                "the scene at " + at.toShortString() + " in " + dimension.identifier() + " is in " + ZONE + ", not "
                        + biome);
    }

    private static String biomeAt(TestServerContext server, ResourceKey<Level> dimension, BlockPos pos) {
        return server.computeOnServer(
                s -> biomeKey(ClientLight.serverLevel(s, dimension).getBiome(pos)));
    }

    /** Puts out the light of a lit room that {@link #buildRoom} built at {@code floor}: an unlit room shows the ambient floor alone. */
    public static void putOutTheLight(TestServerContext server, ResourceKey<Level> dimension, BlockPos floor) {
        server.runOnServer(s -> ClientLight.serverLevel(s, dimension)
                .setBlock(floor.above(ROOM_HEIGHT - 2), Blocks.AIR.defaultBlockState(), 3));
    }

    private static void fillBiomeAround(
            TestServerContext server, Holds holds, ResourceKey<Level> dimension, BlockPos floor, String biome) {
        fillBiome(
                server,
                holds,
                dimension,
                floor,
                biome,
                floor.getY() - MASS - 1 - MARGIN,
                floor.getY() + SHAFT_DEPTH + MARGIN);
    }

    /**
     * The chunks the fills of one scene forced and nothing else held, kept until the scene is done so that they do not unload and regenerate
     * before the camera arrives. Close it at the end of the scene, also when it throws.
     */
    public static final class Holds implements AutoCloseable {
        private record Held(ResourceKey<Level> dimension, ChunkPos chunk) {}

        private final TestServerContext server;
        private final List<Held> held = new ArrayList<>();

        private Holds(TestServerContext server) {
            this.server = server;
        }

        public static Holds of(TestServerContext server) {
            return new Holds(server);
        }

        @Override
        public void close() {
            List<Held> release = List.copyOf(held);
            held.clear();
            server.runOnServer(s -> {
                for (Held h : release) {
                    // force-ok: releases what fillBiome forced
                    ClientLight.serverLevel(s, h.dimension())
                            .setChunkForced(h.chunk().x(), h.chunk().z(), false);
                }
            });
        }
    }

    /**
     * Fills {@code biome} over {@link #MARGIN} blocks round the scene, from {@code bottomY} to {@code topY}, in slabs (a command fills at
     * most 32,768 blocks, counted in whole 4-block cells). The fill is bounded, not the world's whole height: the campaign world is 2,352 tall.
     * {@code fillbiome} reports a chunk that is not loaded only in the log, so the box's chunks are forced into {@code holds} first and every
     * cell is read back after.
     */
    public static void fillBiome(
            TestServerContext server,
            Holds holds,
            ResourceKey<Level> dimension,
            BlockPos floor,
            String biome,
            int bottomY,
            int topY) {
        int minX = floor.getX() - MARGIN;
        int maxX = floor.getX() + MARGIN;
        int minZ = floor.getZ() - MARGIN;
        int maxZ = floor.getZ() + MARGIN;
        server.runOnServer(s -> {
            ServerLevel level = ClientLight.serverLevel(s, dimension);
            for (int cx = minX >> 4; cx <= maxX >> 4; cx++) {
                for (int cz = minZ >> 4; cz <= maxZ >> 4; cz++) {
                    ChunkPos chunk = new ChunkPos(cx, cz);
                    if (!level.getForceLoadedChunks().contains(chunk.pack())) {
                        holds.held.add(new Holds.Held(dimension, chunk));
                    }
                    // force-ok: a client test and an evidence scenario have no GameTestHelper to release through;
                    // Holds releases what this forces
                    level.setChunkForced(cx, cz, true);
                    level.getChunk(cx, cz);
                }
            }
        });
        for (int y = bottomY; y <= topY; y += 4) {
            server.runCommand("execute in %s run fillbiome %d %d %d %d %d %d %s"
                    .formatted(dimension.identifier(), minX, y, minZ, maxX, Math.min(y + 3, topY), maxZ, biome));
        }
        List<String> missed = server.computeOnServer(s ->
                unfilledColumns(ClientLight.serverLevel(s, dimension), biome, minX, maxX, minZ, maxZ, bottomY, topY));
        ClientChecks.require(
                missed.isEmpty(),
                "fillbiome did not put every cell round " + floor.toShortString() + " in " + dimension.identifier()
                        + " (Y " + bottomY + " to " + topY + ") in " + biome + "; " + missed.size()
                        + " columns have a cell that is not, from "
                        + missed.stream().limit(8).toList());
    }

    private static String biomeKey(Holder<Biome> biome) {
        return biome.unwrapKey().orElseThrow().identifier().toString();
    }

    /**
     * The columns, as {@code x,z} of their cell's corner, with a biome cell between {@code bottomY} and {@code topY} that is not
     * {@code biome}. It reads the chunk's stored cells and not {@code Level.getBiome}, which blurs, and it does not load a chunk.
     */
    private static List<String> unfilledColumns(
            ServerLevel level, String biome, int minX, int maxX, int minZ, int maxZ, int bottomY, int topY) {
        List<String> missed = new ArrayList<>();
        for (int x = minX & ~3; x <= maxX; x += 4) {
            for (int z = minZ & ~3; z <= maxZ; z += 4) {
                ChunkAccess chunk = level.getChunk(x >> 4, z >> 4, ChunkStatus.FULL, false);
                if (chunk == null) {
                    missed.add(x + "," + z);
                    continue;
                }
                boolean complete = true;
                for (int y = bottomY & ~3; complete && y <= topY; y += 4) {
                    complete = biomeKey(chunk.getNoiseBiome(x >> 2, y >> 2, z >> 2))
                            .equals(biome);
                }
                if (!complete) {
                    missed.add(x + "," + z);
                }
            }
        }
        return missed;
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
        // tick-wait: the lightmap and the fog ease toward the new place over a few ticks, and nothing reports when
        // they have
        context.waitTicks(SETTLE_TICKS);
        awaitSteadyPicture(context, shot);
    }

    /**
     * Like {@link #stand} without the fixed settle, for a reading of the middle of the screen: waits for the client to be there with
     * the chunks meshed and the light matching the server's, then for the picture to stop changing. The steady-picture check reads only
     * the middle of the screen, so it cannot tell that sections are unmeshed.
     */
    public static void standWithoutSettle(
            ClientGameTestContext context,
            TestServerContext server,
            ResourceKey<Level> dimension,
            BlockPos floor,
            float yaw,
            float pitch,
            String shot) {
        teleport(server, dimension, floor, yaw, pitch);
        Box around = Box.around(floor, MASS, 1, SHAFT_DEPTH);
        ClientWait.until(
                context,
                "the client in " + dimension.identifier() + " holding and meshing the chunks at "
                        + floor.toShortString(),
                client -> ClientLight.clientLevel(client).dimension().equals(dimension)
                        && client.player != null
                        && client.player.blockPosition().equals(floor)
                        && ClientLight.chunksPresent(ClientLight.clientLevel(client), around)
                        && client.levelRenderer.hasRenderedAllSections(),
                client -> "no chunks or unmeshed sections at " + floor.toShortString());
        ClientChecks.requireNone(
                "the light at " + shot, ClientLight.settledMismatch(context, server, dimension, around));
        awaitSteadyPicture(context, shot);
    }

    private static void teleport(
            TestServerContext server, ResourceKey<Level> dimension, BlockPos floor, float yaw, float pitch) {
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
    }

    /**
     * Turns the spectator, already standing on {@code floor} in {@code dimension}, to {@code yaw} and {@code pitch}, and waits for the
     * picture to stop changing. It does not wait for meshing, because the player does not move; the steady-picture check is its only guard.
     */
    public static void face(
            ClientGameTestContext context,
            TestServerContext server,
            ResourceKey<Level> dimension,
            BlockPos floor,
            float yaw,
            float pitch,
            String shot) {
        teleport(server, dimension, floor, yaw, pitch);
        ClientWait.until(
                context,
                "the client facing " + yaw + ", " + pitch + " at " + shot,
                client -> client.player != null
                        && Math.abs(client.player.getXRot() - pitch) < 0.5f
                        && Math.abs(Mth.wrapDegrees(client.player.getYRot() - yaw)) < 0.5f,
                client -> "the player faces "
                        + (client.player == null
                                ? "nowhere"
                                : client.player.getYRot() + ", " + client.player.getXRot()));
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
