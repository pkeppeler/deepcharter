package io.github.pkeppeler.deepcharter.test.support;

import io.github.pkeppeler.deepcharter.layer.CampaignBands;
import io.github.pkeppeler.deepcharter.layer.LayerBlocks;
import io.github.pkeppeler.deepcharter.test.support.ClientLight.Box;
import java.util.Optional;
import java.util.Set;
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

    private AtmosphereScenes() {}

    /**
     * A stone mass with a 3 x 3 shaft in it, from {@code floor} up. {@code open}: the shaft runs to the top of the mass, so the sky reaches
     * its floor (when the mass has open sky over it); otherwise it is a sealed room of the shaft's footprint. {@code biome}, when present,
     * is filled over the mass. In layer 1, whose rock is its own, only the shaft is cut and {@code biome} must be empty. Returns {@code
     * floor}, where the camera stands.
     */
    public static BlockPos build(
            TestServerContext server,
            ResourceKey<Level> dimension,
            BlockPos floor,
            Optional<String> biome,
            boolean open) {
        boolean layer = dimension.equals(TestLayers.dimension(1));
        server.runOnServer(s -> {
            ServerLevel level = ClientLight.serverLevel(s, dimension);
            if (!layer) {
                RoomCarver.carve(
                        level,
                        floor.offset(-MASS, -1, -MASS),
                        floor.offset(MASS, SHAFT_DEPTH, MASS),
                        Blocks.STONE.defaultBlockState());
            }
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

    /** The floor of a scene in layer 1's band of the campaign type at {@code x}: the band's floor and {@link #LAYER_1_SCENE_HEIGHT}. */
    public static BlockPos layer1Floor(int x) {
        return new BlockPos(x, CampaignBands.layer(1).minY() + LAYER_1_SCENE_HEIGHT, SITE);
    }

    /** The floor of a scene in today's layer 1 dimension at {@code x}, as high above its floor as {@link #layer1Floor}'s. */
    public static BlockPos todayFloor(int x) {
        return TestLayers.pos(1, x, LAYER_1_SCENE_HEIGHT, SITE);
    }

    /** A 7 x 7 room, lit by a light block of level 8 under the ceiling, in a mass of layer 1's own rock or cut in the layer's rock, so that the two walls have one texture. */
    public static BlockPos buildLitRoom(
            TestServerContext server, ResourceKey<Level> dimension, BlockPos floor, Optional<String> biome) {
        boolean layer = dimension.equals(TestLayers.dimension(1));
        server.runOnServer(s -> {
            ServerLevel level = ClientLight.serverLevel(s, dimension);
            if (!layer) {
                RoomCarver.carve(
                        level,
                        floor.offset(-MASS, -1, -MASS),
                        floor.offset(MASS, ROOM_HEIGHT, MASS),
                        LayerBlocks.groundOf(TestLayers.level(s, 1), floor.getY())
                                .defaultBlockState());
            }
            RoomCarver.carve(
                    level,
                    floor.offset(-3, 0, -3),
                    floor.offset(3, ROOM_HEIGHT - 1, 3),
                    Blocks.AIR.defaultBlockState());
            level.setBlock(
                    floor.above(ROOM_HEIGHT - 2),
                    Blocks.LIGHT.defaultBlockState().setValue(LightBlock.LEVEL, 8),
                    3);
        });
        biome.ifPresent(name -> fillBiome(
                server, dimension, floor, name, floor.getY() - MASS - 1 - MARGIN, floor.getY() + SHAFT_DEPTH + MARGIN));
        return floor;
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
    }
}
