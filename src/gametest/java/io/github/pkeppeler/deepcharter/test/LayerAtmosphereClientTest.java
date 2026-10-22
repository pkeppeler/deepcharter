package io.github.pkeppeler.deepcharter.test;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.layer.LayerBlocks;
import io.github.pkeppeler.deepcharter.test.support.ClientChecks;
import io.github.pkeppeler.deepcharter.test.support.ClientLight;
import io.github.pkeppeler.deepcharter.test.support.ClientLight.Box;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.EvidencePins;
import io.github.pkeppeler.deepcharter.test.support.RoomCarver;
import io.github.pkeppeler.deepcharter.test.support.TestLayers;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import javax.imageio.ImageIO;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LightBlock;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Client GameTest for #744 (campaign world plan, #214): what a player sees where the layer's look comes from its zone biome and not from
 * its dimension type. The campaign world will be one dimension with skylight, the default cardinal light and no ambient light, so a layer
 * biome is set into {@code deepcharter:open_sky}, a test type of that kind (no sky timeline), and rendered three ways: the bottom of a
 * shaft under open sky, a sealed room of the same shape, and the same shaft in a surface biome. The shaft must read as the room does,
 * not as the surface. The same shaft is read once more in the surface's own type, which runs the sky timeline: it does not read as the
 * room, because a timeline's {@code override} track is applied after the biomes (the logged lines say by how much). Then a lit room is
 * rendered in the open-sky type and in today's layer dimension, which has the three things a biome cannot set ({@code ambient_light},
 * {@code cardinal_light: nether}, {@code has_skylight: false}); the {@code [layer-atmosphere]} lines are what ADR 0059 quotes as the
 * measured loss.
 */
public class LayerAtmosphereClientTest implements FabricClientGameTest {
    private static final Logger LOGGER = LoggerFactory.getLogger(LayerAtmosphereClientTest.class);
    /** A zone of layer 1 whose biome sets the layer's atmosphere. */
    private static final String ZONE = "deepcharter:stone_benches";
    /** Far from the colony, in X and Z, with the three cases of one model this far apart so that no biome blend reaches another. */
    private static final int SITE = 5_000;

    /** The test type of the campaign world's kind: skylight, default cardinal light, no ambient light and no sky timeline. */
    private static final ResourceKey<Level> OPEN_SKY = ResourceKey.create(
            Registries.DIMENSION, Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID + "-test", "open_sky"));

    private static final int SPACING = 96;
    /** How far a biome is filled round a scene: the camera's attribute probe blends biomes over some blocks, and a margin short of it lets the next biome in. */
    private static final int MARGIN = 32;

    private static final int MASS = 6;
    private static final int SHAFT_DEPTH = 20;
    private static final int ROOM_HEIGHT = 5;
    /** Layer brightness is the sealed room's; a shaft that reads this close to it is at layer brightness. */
    private static final double SAME_LUMA = 3.0;
    /** The same shaft in a surface biome reads at least this many times brighter, so the test can tell the two apart. */
    private static final double CONTROL_RATIO = 4.0;

    private final List<String> table = new ArrayList<>();

    /** One reading: the mean luminance (0 to 255) and colour of the middle of the screen. */
    private record Reading(double luma, double red, double green, double blue) {
        @Override
        public String toString() {
            return "luma %.1f (r %.1f g %.1f b %.1f)".formatted(luma, red, green, blue);
        }
    }

    @Override
    public void runTest(ClientGameTestContext context) {
        ClientTestLog.start(this);
        boolean flickerWas = EvidencePins.lightFlickerOff;
        EvidencePins.lightFlickerOff = true;
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            ClientWait.until(
                    context, "the player in the world", client -> client.player != null && client.level != null);
            TestServerContext server = singleplayer.getServer();
            server.runCommand("gamerule advance_time false");
            server.runCommand("time set noon");
            server.runCommand("time of deepcharter:sky set 0");
            server.runOnServer(s -> player(s).setGameMode(GameType.SPECTATOR));

            bottomOfAShaftUnderOpenSky(context, server);
            theSkyTimelineOverridesTheBiome(context, server);
            theBlendAtABiomeEdge(context, server);
            whatTheCampaignWorldLoses(context, server);
        } finally {
            EvidencePins.lightFlickerOff = flickerWas;
        }
        LOGGER.info("[layer-atmosphere] results:\n  " + String.join("\n  ", table));
    }

    // ---------------------------------------------------------------------------------------------- shaft

    /** The acceptance case of #744: skylight falls down an open shaft, and the layer biome's sky light factor of 0 keeps it dark. */
    private void bottomOfAShaftUnderOpenSky(ClientGameTestContext context, TestServerContext server) {
        int x = SITE;
        BlockPos shaftFloor = build(server, OPEN_SKY, x, Optional.of(ZONE), true);
        BlockPos roomFloor = build(server, OPEN_SKY, x + SPACING, Optional.of(ZONE), false);
        BlockPos controlFloor = build(server, OPEN_SKY, x + 2 * SPACING, Optional.empty(), true);

        Reading shaft = look(context, server, OPEN_SKY, shaftFloor, 180, 0, "shaft-layer-biome");
        Reading room = look(context, server, OPEN_SKY, roomFloor, 180, 0, "room-layer-biome");
        Reading control = look(context, server, OPEN_SKY, controlFloor, 180, 0, "shaft-surface-biome");
        int shaftSky = serverSky(server, OPEN_SKY, shaftFloor);
        int roomSky = serverSky(server, OPEN_SKY, roomFloor);
        table.add("open-sky type, open shaft, layer biome: " + shaft + ", server sky light " + shaftSky);
        table.add("open-sky type, sealed room, layer biome: " + room + ", server sky light " + roomSky);
        table.add("open-sky type, open shaft, surface biome: " + control);
        ClientChecks.require(roomSky == 0, "the sealed room has no sky light on the server, read " + roomSky);
        ClientChecks.require(
                shaftSky > 0,
                "the shaft under open sky has sky light on the server (the biome only dims what the client draws), read "
                        + shaftSky);
        ClientChecks.require(
                Math.abs(shaft.luma() - room.luma()) <= SAME_LUMA,
                "the bottom of a layer shaft under open sky renders at layer brightness (the sealed room's " + room
                        + "), read " + shaft);
        ClientChecks.require(
                control.luma() >= CONTROL_RATIO * Math.max(room.luma(), 1.0),
                "the same shaft in a surface biome is much brighter than the layer's (" + room + "), read " + control);
    }

    /**
     * The surface's own type runs the sky timeline, whose {@code override} tracks (fog colour, fog distances, sky light colour and factor)
     * are applied after the biome layer and so replace the layer biome's values. A measurement, not a guard: the lines are quoted in
     * ADR 0059, and the cutover (#214) must change this.
     */
    private void theSkyTimelineOverridesTheBiome(ClientGameTestContext context, TestServerContext server) {
        BlockPos shaftFloor = build(server, Level.OVERWORLD, SITE, Optional.of(ZONE), true);
        BlockPos roomFloor = build(server, Level.OVERWORLD, SITE + SPACING, Optional.of(ZONE), false);
        Reading shaft = look(context, server, Level.OVERWORLD, shaftFloor, 180, 0, "timeline-shaft-layer-biome");
        Reading room = look(context, server, Level.OVERWORLD, roomFloor, 180, 0, "timeline-room-layer-biome");
        table.add("surface type (sky timeline), open shaft, layer biome: " + shaft + "; attributes at the camera: "
                + attributes(context, shaftFloor));
        table.add("surface type (sky timeline), sealed room, layer biome: " + room + "; attributes at the camera: "
                + attributes(context, roomFloor));
    }

    /**
     * The camera's attribute probe blends the biomes round it, so the sky light factor of a layer biome is not 0 within some blocks of a
     * biome that sets none: the surface floor of ADR 0011 is open to layer 1, so a player in the top of layer 1 sees the blend. The layer
     * biome fills everything at or below an edge; the probe is read at distances from the edge, up to the edge and above it.
     */
    private void theBlendAtABiomeEdge(ClientGameTestContext context, TestServerContext server) {
        BlockPos floor = ground(server, OPEN_SKY, SITE + 5 * SPACING);
        int edge = floor.getY() - 40;
        fillBiome(server, OPEN_SKY, floor, ZONE, edge);
        StringBuilder row = new StringBuilder(
                "blend at an edge (layer biome at and below it), sky light factor by blocks below the edge:");
        for (int below : new int[] {-8, -4, 0, 2, 4, 6, 8, 12, 16, 24}) {
            BlockPos at = new BlockPos(floor.getX(), edge - below, floor.getZ());
            server.runOnServer(s -> player(s)
                    .teleportTo(
                            ClientLight.serverLevel(s, OPEN_SKY),
                            at.getX() + 0.5,
                            at.getY() + 0.5,
                            at.getZ() + 0.5,
                            Set.of(),
                            0,
                            0,
                            true));
            ClientWait.until(
                    context,
                    "the client at " + at.toShortString(),
                    client -> client.player != null
                            && client.player.blockPosition().equals(at),
                    client -> "the player is at "
                            + (client.player == null
                                    ? "nowhere"
                                    : client.player.blockPosition().toShortString()));
            // tick-wait: the probe takes the camera's new place on the next frame; nothing reports it
            context.waitTicks(10);
            float factor = context.computeOnClient(client -> client.gameRenderer
                    .mainCamera()
                    .attributeProbe()
                    .getValue(EnvironmentAttributes.SKY_LIGHT_FACTOR, 1f));
            row.append(" %d: %.3f;".formatted(below, factor));
        }
        table.add(row.toString());
    }

    /** What the client's attribute system reads at the camera: the layer biome wants fog 8 to 64, a sky light factor of 0. */
    private static String attributes(ClientGameTestContext context, BlockPos floor) {
        return context.computeOnClient(client -> {
            Vec3 at = Vec3.atBottomCenterOf(floor).add(0, 1.6, 0);
            var read = ClientLight.clientLevel(client).environmentAttributes();
            var lit = ClientLight.clientLevel(client);
            var probe = client.gameRenderer.mainCamera().attributeProbe();
            return "block light tint %s; probe factor %.3f ambient %s; client light sky %d block %d; fog %.1f to %.1f, sky light factor %.3f, sky light colour %s, ambient %s, fog colour %s"
                    .formatted(
                            probe.getValue(EnvironmentAttributes.BLOCK_LIGHT_TINT, 1f),
                            probe.getValue(EnvironmentAttributes.SKY_LIGHT_FACTOR, 1f),
                            probe.getValue(EnvironmentAttributes.AMBIENT_LIGHT_COLOR, 1f),
                            lit.getBrightness(LightLayer.SKY, floor.above()),
                            lit.getBrightness(LightLayer.BLOCK, floor.above()),
                            read.getValue(EnvironmentAttributes.FOG_START_DISTANCE, at),
                            read.getValue(EnvironmentAttributes.FOG_END_DISTANCE, at),
                            read.getValue(EnvironmentAttributes.SKY_LIGHT_FACTOR, at),
                            read.getValue(EnvironmentAttributes.SKY_LIGHT_COLOR, at),
                            read.getValue(EnvironmentAttributes.AMBIENT_LIGHT_COLOR, at),
                            read.getValue(EnvironmentAttributes.FOG_COLOR, at));
        });
    }

    // ---------------------------------------------------------------------------------------------- loss

    /** The same lit room as the campaign world will render a layer (a biome in the tall type) and as today's layer dimension does. */
    private void whatTheCampaignWorldLoses(ClientGameTestContext context, TestServerContext server) {
        int x = SITE;
        BlockPos campaign = buildLitRoom(server, OPEN_SKY, x + 3 * SPACING, Optional.of(ZONE));
        BlockPos today = buildLitRoom(server, TestLayers.dimension(1), x + 4 * SPACING, Optional.empty());
        String biome = server.computeOnServer(s -> TestLayers.level(s, 1)
                .getBiome(today)
                .unwrapKey()
                .orElseThrow()
                .identifier()
                .toString());
        ClientChecks.require(
                biome.equals(ZONE), "the layer 1 room is in " + ZONE + " so both models use one biome, not " + biome);
        record View(String name, float yaw, float pitch) {}
        for (View view : List.of(new View("wall", 180, 0), new View("floor", 180, 90), new View("ceiling", 180, -90))) {
            Reading now = look(
                    context,
                    server,
                    TestLayers.dimension(1),
                    today,
                    view.yaw(),
                    view.pitch(),
                    "lit-room-today-" + view.name());
            Reading campaignWorld = look(
                    context, server, OPEN_SKY, campaign, view.yaw(), view.pitch(), "lit-room-campaign-" + view.name());
            table.add("lit room, %s: today %s; campaign %s; campaign/today %.3f"
                    .formatted(view.name(), now, campaignWorld, campaignWorld.luma() / Math.max(now.luma(), 0.01)));
        }
        // An unlit room shows the ambient floor alone.
        server.runOnServer(s -> {
            ClientLight.serverLevel(s, OPEN_SKY)
                    .setBlock(campaign.above(ROOM_HEIGHT - 2), Blocks.AIR.defaultBlockState(), 3);
            TestLayers.level(s, 1).setBlock(today.above(ROOM_HEIGHT - 2), Blocks.AIR.defaultBlockState(), 3);
        });
        Reading dark = look(context, server, TestLayers.dimension(1), today, 180, 0, "dark-room-today-wall");
        Reading darkCampaign = look(context, server, OPEN_SKY, campaign, 180, 0, "dark-room-campaign-wall");
        table.add("unlit room, wall: today " + dark + "; campaign " + darkCampaign);
    }

    // ---------------------------------------------------------------------------------------------- scenes

    /**
     * A stone mass with a 3 x 3 shaft in it, from the ground up. {@code open}: the shaft runs to the top of the mass, so the sky reaches
     * its floor; otherwise it is a sealed room of the shaft's footprint. {@code biome}, when present, is filled over the mass. Returns the
     * floor of the shaft, where the camera stands.
     */
    private BlockPos build(
            TestServerContext server, ResourceKey<Level> dimension, int x, Optional<String> biome, boolean open) {
        BlockPos floor = ground(server, dimension, x);
        server.runOnServer(s -> {
            ServerLevel level = ClientLight.serverLevel(s, dimension);
            RoomCarver.carve(
                    level,
                    floor.offset(-MASS, -1, -MASS),
                    floor.offset(MASS, SHAFT_DEPTH, MASS),
                    Blocks.STONE.defaultBlockState());
            RoomCarver.carve(
                    level,
                    floor.offset(-1, 0, -1),
                    floor.offset(1, open ? SHAFT_DEPTH : ROOM_HEIGHT - 1, 1),
                    Blocks.AIR.defaultBlockState());
        });
        biome.ifPresent(name -> fillBiome(server, dimension, floor, name));
        return floor;
    }

    /** A 7 x 7 room, lit by a light block of level 8 under the ceiling, in a mass of layer 1's own rock or cut in the layer's rock, so that the two walls have one texture. */
    private BlockPos buildLitRoom(
            TestServerContext server, ResourceKey<Level> dimension, int x, Optional<String> biome) {
        boolean layer = dimension.equals(TestLayers.dimension(1));
        BlockPos floor = layer ? TestLayers.pos(1, x, 96, SITE) : ground(server, dimension, x);
        server.runOnServer(s -> {
            ServerLevel level = ClientLight.serverLevel(s, dimension);
            if (!layer) {
                RoomCarver.carve(
                        level,
                        floor.offset(-MASS, -1, -MASS),
                        floor.offset(MASS, ROOM_HEIGHT, MASS),
                        LayerBlocks.groundOf(TestLayers.level(s, 1)).defaultBlockState());
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
        biome.ifPresent(name -> fillBiome(server, dimension, floor, name));
        return floor;
    }

    /** Fills {@code biome} over {@link #MARGIN} blocks round the scene, from the world floor to well above its top, in slabs (a command fills at most 32,768 blocks, counted in whole 4-block cells). */
    private static void fillBiome(
            TestServerContext server, ResourceKey<Level> dimension, BlockPos floor, String biome) {
        fillBiome(server, dimension, floor, biome, Integer.MAX_VALUE);
    }

    /** As above, up to the world's height {@code topY} (the scene's top plus the margin when it is the maximum). */
    private static void fillBiome(
            TestServerContext server, ResourceKey<Level> dimension, BlockPos floor, String biome, int topY) {
        int minY = server.computeOnServer(
                s -> ClientLight.serverLevel(s, dimension).getMinY());
        int top = Math.min(topY, floor.getY() + SHAFT_DEPTH + MARGIN);
        for (int y = minY; y <= top; y += 4) {
            server.runCommand("execute in %s run fillbiome %d %d %d %d %d %d %s"
                    .formatted(
                            dimension.identifier(),
                            floor.getX() - MARGIN,
                            y,
                            floor.getZ() - MARGIN,
                            floor.getX() + MARGIN,
                            Math.min(y + 3, top),
                            floor.getZ() + MARGIN,
                            biome));
        }
    }

    /** The free block over the ground at {@code x}, {@link #SITE}, with the chunks round it loaded. */
    private static BlockPos ground(TestServerContext server, ResourceKey<Level> dimension, int x) {
        return server.computeOnServer(s -> {
            ServerLevel level = ClientLight.serverLevel(s, dimension);
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

    // ---------------------------------------------------------------------------------------------- reading

    /** Stands the spectator on {@code floor}, waits for the light and the lightmap to settle, and reads the middle of the screen. */
    private static Reading look(
            ClientGameTestContext context,
            TestServerContext server,
            ResourceKey<Level> dimension,
            BlockPos floor,
            float yaw,
            float pitch,
            String shot) {
        server.runOnServer(s -> player(s)
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
        context.waitTicks(160);
        Reading[] reading = new Reading[1];
        ClientChecks.withHudHidden(context, () -> reading[0] = average(context.takeScreenshot(shot)));
        LOGGER.info(
                "[layer-atmosphere] {}: {}; attributes at the camera: {}",
                shot,
                reading[0],
                attributes(context, floor));
        return reading[0];
    }

    private static Reading average(Path file) {
        BufferedImage image;
        try {
            image = ImageIO.read(file.toFile());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        int half = 40;
        double red = 0;
        double green = 0;
        double blue = 0;
        int count = 0;
        for (int y = image.getHeight() / 2 - half; y < image.getHeight() / 2 + half; y++) {
            for (int x = image.getWidth() / 2 - half; x < image.getWidth() / 2 + half; x++) {
                int rgb = image.getRGB(x, y);
                red += (rgb >> 16) & 0xFF;
                green += (rgb >> 8) & 0xFF;
                blue += rgb & 0xFF;
                count++;
            }
        }
        red /= count;
        green /= count;
        blue /= count;
        return new Reading(0.299 * red + 0.587 * green + 0.114 * blue, red, green, blue);
    }

    /** The sky light the server holds at the camera's block. */
    private static int serverSky(TestServerContext server, ResourceKey<Level> dimension, BlockPos floor) {
        return server.computeOnServer(
                s -> ClientLight.serverLevel(s, dimension).getBrightness(LightLayer.SKY, floor.above()));
    }

    private static ServerPlayer player(MinecraftServer server) {
        return server.getPlayerList().getPlayers().getFirst();
    }
}
