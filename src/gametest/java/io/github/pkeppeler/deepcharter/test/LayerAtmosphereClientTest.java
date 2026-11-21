package io.github.pkeppeler.deepcharter.test;

import io.github.pkeppeler.deepcharter.layer.CampaignBands;
import io.github.pkeppeler.deepcharter.test.support.ClientChecks;
import io.github.pkeppeler.deepcharter.test.support.ClientLight;
import io.github.pkeppeler.deepcharter.test.support.ClientLight.Box;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.EvidencePins;
import io.github.pkeppeler.deepcharter.test.support.RoomCarver;
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
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState.WorldTypeEntry;
import net.minecraft.client.renderer.Lightmap;
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
import net.minecraft.world.level.levelgen.presets.WorldPreset;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Client GameTest for #744 and #761 (campaign world plan, #214): what a player sees where the layer's look comes from its zone biome and
 * not from its dimension type. It runs in {@code deepcharter:campaign_sample}, the campaign world's test dimension, in a world made with
 * the {@code flat_all_dimensions} preset (which adds it), with its scenes at the Ys of the campaign bands. The bottom of a shaft under
 * open sky is read next to a sealed room of the same shape in layer 1's band, and next to the same shaft in a surface biome. The shaft
 * must read as the room does, not as the surface, while the sky timeline runs, at dusk and at night (the biome layer that ADR 0062 adds
 * after the timelines is what makes it so). Then a lit room is rendered in layer 1's band, and an entity's brightness at light 0 is read
 * there: the campaign type has none of the three things a biome cannot set ({@code ambient_light}, {@code cardinal_light},
 * {@code has_skylight}); the {@code [layer-atmosphere]} lines are what ADR 0059 quotes as the measured look.
 */
public class LayerAtmosphereClientTest implements FabricClientGameTest {
    private static final Logger LOGGER = LoggerFactory.getLogger(LayerAtmosphereClientTest.class);
    /** A zone of layer 1 whose biome sets the layer's atmosphere. */
    private static final String ZONE = "deepcharter:stone_benches";
    /** Far from the colony, in X and Z, with the three cases of one model this far apart so that no biome blend reaches another. */
    private static final int SITE = 5_000;

    /** The campaign world's test dimension: its type is the one the game will ship. */
    private static final ResourceKey<Level> CAMPAIGN =
            ResourceKey.create(Registries.DIMENSION, Identifier.fromNamespaceAndPath("deepcharter", "campaign_sample"));

    /** The client's default world has the shipped dimensions only; this preset adds {@code campaign_sample}. */
    private static final ResourceKey<WorldPreset> TEST_PRESET =
            ResourceKey.create(Registries.WORLD_PRESET, Identifier.withDefaultNamespace("flat_all_dimensions"));

    /** The sky clock's values the test reads at: the start of the timeline (dusk) and the middle of its night. */
    private static final long SKY_DUSK = 0;

    private static final long SKY_NIGHT = 144_000;
    /** Where the scenes of layer 1's band sit above the band's floor. */
    private static final int LAYER_1_SCENE_HEIGHT = 96;
    /** The blend probe reads down to this many blocks under its edge. */
    private static final int BLEND_BELOW = 24;

    private static final float AMBIENT_TOLERANCE = 0.001f;

    private static final int SPACING = 96;
    /** How far a biome is filled round a scene: the camera's attribute probe blends biomes over some blocks, and a margin short of it lets the next biome in. */
    private static final int MARGIN = 32;

    private static final int MASS = 6;
    private static final int SHAFT_DEPTH = 20;
    private static final int ROOM_HEIGHT = 5;
    /** Layer brightness is the sealed room's; a shaft that reads this close to it is at layer brightness. */
    private static final double SAME_LUMA = 3.0;
    /**
     * The same shaft in a surface biome reads at least this many times brighter, so the test can tell the two apart. It was 4.0 (102.8
     * against 21.8) in a type with no sky timeline; at dusk the campaign type's timeline sets a sky light factor of 0.62 and the surface
     * shaft reads 64.2 (2.9x), so the bar is 2.0.
     */
    private static final double CONTROL_RATIO = 2.0;

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
        try (TestSingleplayerContext singleplayer = context.worldBuilder()
                .adjustSettings(state -> state.setWorldType(new WorldTypeEntry(state.getSettings()
                        .worldgenLoadContext()
                        .lookupOrThrow(Registries.WORLD_PRESET)
                        .getOrThrow(TEST_PRESET))))
                .create()) {
            ClientWait.until(
                    context, "the player in the world", client -> client.player != null && client.level != null);
            TestServerContext server = singleplayer.getServer();
            server.runCommand("gamerule advance_time false");
            server.runCommand("time set noon");
            server.runOnServer(s -> player(s).setGameMode(GameType.SPECTATOR));

            theShaftReadsAsTheSealedRoomWhileTheSkyTimelineRuns(context, server);
            theBlendAtABiomeEdge(context, server);
            theLitRoomOfTheCampaignType(context, server);
        } finally {
            EvidencePins.lightFlickerOff = flickerWas;
        }
        LOGGER.info("[layer-atmosphere] results:\n  " + String.join("\n  ", table));
    }

    // ---------------------------------------------------------------------------------------------- shaft

    /**
     * The acceptance case of #744 and #761: skylight falls down an open shaft, and the layer biome's sky light factor of 0 keeps it dark,
     * while the sky timeline of the campaign type runs. The sealed room is in layer 1's band; the shaft and its control stand on the
     * surface's ground. The timeline's {@code override} tracks win over a biome unless the biome layer of ADR 0062 is applied after
     * them, so the shaft reads as the room only with that layer. The shaft models the bottom of a layer shaft, not the
     * surface-to-layer transition, which {@link #theBlendAtABiomeEdge} covers.
     */
    private void theShaftReadsAsTheSealedRoomWhileTheSkyTimelineRuns(
            ClientGameTestContext context, TestServerContext server) {
        int x = SITE;
        BlockPos shaftFloor = build(server, ground(server, x), Optional.of(ZONE), true);
        BlockPos roomFloor = build(server, layer1Floor(x + SPACING), Optional.of(ZONE), false);
        BlockPos controlFloor = build(server, ground(server, x + 2 * SPACING), Optional.empty(), true);
        int shaftSky = serverSky(server, shaftFloor);
        int roomSky = serverSky(server, roomFloor);
        ClientChecks.require(roomSky == 0, "the sealed room has no sky light on the server, read " + roomSky);
        ClientChecks.require(
                shaftSky > 0,
                "the shaft under open sky has sky light on the server (the biome only dims what the client draws), read "
                        + shaftSky);
        record SkyTime(String name, long ticks) {}
        for (SkyTime sky : List.of(new SkyTime("dusk", SKY_DUSK), new SkyTime("night", SKY_NIGHT))) {
            server.runCommand("time of deepcharter:sky set " + sky.ticks());
            Reading shaft = look(context, server, shaftFloor, 180, 0, "shaft-layer-biome-" + sky.name());
            float shaftFactor = skyLightFactor(context, shaftFloor);
            Reading room = look(context, server, roomFloor, 180, 0, "room-layer-biome-" + sky.name());
            Reading control = look(context, server, controlFloor, 180, 0, "shaft-surface-biome-" + sky.name());
            float controlFactor = skyLightFactor(context, controlFloor);
            table.add("campaign type, sky timeline at %s, sky light factor at the camera: shaft %.3f, control %.3f"
                    .formatted(sky.name(), shaftFactor, controlFactor));
            // The luma rows are logs at night, where the surface is as dark as the room: only the factor tells the
            // layer biome from the timeline there (without the biome layer the shaft's factor is the timeline's).
            ClientChecks.require(
                    shaftFactor == 0f,
                    "the layer biome's sky light factor of 0 wins over the sky timeline at the bottom of the shaft at "
                            + sky.name() + ", read " + shaftFactor);
            ClientChecks.require(
                    controlFactor > 0f,
                    "the surface biome leaves the sky timeline's sky light factor at the control shaft at " + sky.name()
                            + ", read " + controlFactor);
            table.add("campaign type, sky timeline at %s, open shaft, layer biome: %s, server sky light %d"
                    .formatted(sky.name(), shaft, shaftSky));
            table.add("campaign type, sky timeline at %s, sealed room, layer biome: %s, server sky light %d"
                    .formatted(sky.name(), room, roomSky));
            table.add(
                    "campaign type, sky timeline at %s, open shaft, surface biome: %s".formatted(sky.name(), control));
            // Dusk only: at night the surface is dark by its own timeline, so the room's luma is within SAME_LUMA of
            // the surface's and the control ratio holds for nothing.
            if (sky.ticks() == SKY_DUSK) {
                ClientChecks.require(
                        Math.abs(shaft.luma() - room.luma()) <= SAME_LUMA,
                        "the bottom of a layer shaft under open sky renders at layer brightness (the sealed room's "
                                + room + ") while the sky timeline runs at " + sky.name() + ", read " + shaft);
                ClientChecks.require(
                        control.luma() >= CONTROL_RATIO * Math.max(room.luma(), 1.0),
                        "the same shaft in a surface biome is much brighter than the layer's (" + room + "), read "
                                + control);
            }
        }
    }

    /**
     * The camera's attribute probe blends the biomes round it, so the sky light factor of a layer biome is not 0 within some blocks of a
     * biome that sets none: the surface floor of ADR 0011 is open to layer 1, so a player in the top of layer 1 sees the blend. The layer
     * biome fills everything at or below an edge; the probe is read at distances from the edge, up to the edge and above it.
     */
    private void theBlendAtABiomeEdge(ClientGameTestContext context, TestServerContext server) {
        BlockPos floor = ground(server, SITE + 5 * SPACING);
        int edge = floor.getY() - 40;
        fillBiome(server, floor, ZONE, edge - BLEND_BELOW - MARGIN, edge);
        StringBuilder row = new StringBuilder(
                "blend at an edge (layer biome at and below it), sky light factor by blocks below the edge:");
        for (int below : new int[] {-8, -4, 0, 2, 4, 6, 8, 12, 16, 24}) {
            BlockPos at = new BlockPos(floor.getX(), edge - below, floor.getZ());
            server.runOnServer(s -> player(s)
                    .teleportTo(
                            ClientLight.serverLevel(s, CAMPAIGN),
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

    /** The sky light factor the client's attribute system reads at the camera standing on {@code floor}. */
    private static float skyLightFactor(ClientGameTestContext context, BlockPos floor) {
        return context.computeOnClient(client -> ClientLight.clientLevel(client)
                .environmentAttributes()
                .getValue(
                        EnvironmentAttributes.SKY_LIGHT_FACTOR,
                        Vec3.atBottomCenterOf(floor).add(0, 1.6, 0)));
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

    /**
     * A lit room in layer 1's band is rendered in the campaign type, whose own ambient light is 0 ({@code ambient_light}, {@code cardinal_light}
     * and {@code has_skylight} are what a biome cannot set, ADR 0059), and an entity's brightness at light 0 is read there: nothing lights it.
     * The {@code [layer-atmosphere]} lines are what ADR 0059 quotes as the measured look.
     */
    private void theLitRoomOfTheCampaignType(ClientGameTestContext context, TestServerContext server) {
        int x = SITE;
        BlockPos campaign = buildLitRoom(server, layer1Floor(x + 3 * SPACING), Optional.of(ZONE));
        String biome = server.computeOnServer(s -> ClientLight.serverLevel(s, CAMPAIGN)
                .getBiome(campaign)
                .unwrapKey()
                .orElseThrow()
                .identifier()
                .toString());
        ClientChecks.require(biome.equals(ZONE), "the layer 1 room is in " + ZONE + ", not " + biome);
        record View(String name, float yaw, float pitch) {}
        for (View view : List.of(new View("wall", 180, 0), new View("floor", 180, 90), new View("ceiling", 180, -90))) {
            Reading campaignWorld =
                    look(context, server, campaign, view.yaw(), view.pitch(), "lit-room-campaign-" + view.name());
            table.add("lit room, %s: campaign %s".formatted(view.name(), campaignWorld));
        }
        // An unlit room shows the ambient floor alone.
        server.runOnServer(s -> ClientLight.serverLevel(s, CAMPAIGN)
                .setBlock(campaign.above(ROOM_HEIGHT - 2), Blocks.AIR.defaultBlockState(), 3));
        Reading darkCampaign = look(context, server, campaign, 180, 0, "dark-room-campaign-wall");
        float entityCampaign = entityBrightnessAtLightZero(context);
        table.add("unlit room, wall: campaign " + darkCampaign);
        table.add("entity at light 0 (Lightmap.getBrightness, what an entity is lit by): campaign %.3f"
                .formatted(entityCampaign));
        ClientChecks.require(
                Math.abs(entityCampaign) <= AMBIENT_TOLERANCE,
                "an entity at light 0 in the campaign type is not lit at all, read " + entityCampaign);
    }

    /** The brightness the client gives an entity at block and sky light 0 in the level the camera is in. */
    private static float entityBrightnessAtLightZero(ClientGameTestContext context) {
        return context.computeOnClient(
                client -> Lightmap.getBrightness(ClientLight.clientLevel(client).dimensionType(), 0));
    }

    // ---------------------------------------------------------------------------------------------- scenes

    /**
     * A stone mass with a 3 x 3 shaft in it, from {@code floor} up. {@code open}: the shaft runs to the top of the mass, so the sky reaches
     * its floor (when the mass has open sky over it); otherwise it is a sealed room of the shaft's footprint. {@code biome}, when present,
     * is filled over the mass. Returns {@code floor}, where the camera stands.
     */
    private BlockPos build(TestServerContext server, BlockPos floor, Optional<String> biome, boolean open) {
        server.runOnServer(s -> {
            ServerLevel level = ClientLight.serverLevel(s, CAMPAIGN);
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
        biome.ifPresent(name ->
                fillBiome(server, floor, name, floor.getY() - MASS - 1 - MARGIN, floor.getY() + SHAFT_DEPTH + MARGIN));
        return floor;
    }

    /** The floor of a scene in layer 1's band at {@code x}: the band's floor and {@link #LAYER_1_SCENE_HEIGHT}. */
    private static BlockPos layer1Floor(int x) {
        return new BlockPos(x, CampaignBands.layer(1).minY() + LAYER_1_SCENE_HEIGHT, SITE);
    }

    /** A 7 x 7 room cut in layer 1's own rock, lit by a light block of level 8 under the ceiling. */
    private BlockPos buildLitRoom(TestServerContext server, BlockPos floor, Optional<String> biome) {
        server.runOnServer(s -> {
            ServerLevel level = ClientLight.serverLevel(s, CAMPAIGN);
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
        biome.ifPresent(name ->
                fillBiome(server, floor, name, floor.getY() - MASS - 1 - MARGIN, floor.getY() + SHAFT_DEPTH + MARGIN));
        return floor;
    }

    /**
     * Fills {@code biome} over {@link #MARGIN} blocks round the scene, from {@code bottomY} to {@code topY}, in slabs (a command fills at
     * most 32,768 blocks, counted in whole 4-block cells). The fill is bounded, not the world's whole height: the campaign world is 2,352 tall.
     */
    private static void fillBiome(TestServerContext server, BlockPos floor, String biome, int bottomY, int topY) {
        for (int y = bottomY; y <= topY; y += 4) {
            server.runCommand("execute in %s run fillbiome %d %d %d %d %d %d %s"
                    .formatted(
                            CAMPAIGN.identifier(),
                            floor.getX() - MARGIN,
                            y,
                            floor.getZ() - MARGIN,
                            floor.getX() + MARGIN,
                            Math.min(y + 3, topY),
                            floor.getZ() + MARGIN,
                            biome));
        }
    }

    /** The free block over the ground at {@code x}, {@link #SITE}, with the chunks round it loaded. */
    private static BlockPos ground(TestServerContext server, int x) {
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

    // ---------------------------------------------------------------------------------------------- reading

    /** Stands the spectator on {@code floor}, waits for the light and the lightmap to settle, and reads the middle of the screen. */
    private static Reading look(
            ClientGameTestContext context,
            TestServerContext server,
            BlockPos floor,
            float yaw,
            float pitch,
            String shot) {
        server.runOnServer(s -> player(s)
                .teleportTo(
                        ClientLight.serverLevel(s, CAMPAIGN),
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
                "the client in " + CAMPAIGN.identifier() + " holding the chunks at " + floor.toShortString(),
                client -> ClientLight.clientLevel(client).dimension().equals(CAMPAIGN)
                        && ClientLight.chunksPresent(ClientLight.clientLevel(client), around),
                client -> "no chunks at " + floor.toShortString());
        ClientChecks.requireNone(
                "the light at " + shot, ClientLight.settledMismatch(context, server, CAMPAIGN, around));
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
    private static int serverSky(TestServerContext server, BlockPos floor) {
        return server.computeOnServer(
                s -> ClientLight.serverLevel(s, CAMPAIGN).getBrightness(LightLayer.SKY, floor.above()));
    }

    private static ServerPlayer player(MinecraftServer server) {
        return server.getPlayerList().getPlayers().getFirst();
    }
}
