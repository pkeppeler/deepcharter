package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.AtmosphereScenes.CAMPAIGN;
import static io.github.pkeppeler.deepcharter.test.support.AtmosphereScenes.MARGIN;
import static io.github.pkeppeler.deepcharter.test.support.AtmosphereScenes.SITE;
import static io.github.pkeppeler.deepcharter.test.support.AtmosphereScenes.SKY_DUSK;
import static io.github.pkeppeler.deepcharter.test.support.AtmosphereScenes.SKY_NIGHT;
import static io.github.pkeppeler.deepcharter.test.support.AtmosphereScenes.SPACING;
import static io.github.pkeppeler.deepcharter.test.support.AtmosphereScenes.TEST_PRESET;
import static io.github.pkeppeler.deepcharter.test.support.AtmosphereScenes.TODAY;
import static io.github.pkeppeler.deepcharter.test.support.AtmosphereScenes.ZONE;
import static io.github.pkeppeler.deepcharter.test.support.AtmosphereScenes.build;
import static io.github.pkeppeler.deepcharter.test.support.AtmosphereScenes.buildRoom;
import static io.github.pkeppeler.deepcharter.test.support.AtmosphereScenes.face;
import static io.github.pkeppeler.deepcharter.test.support.AtmosphereScenes.fillBiome;
import static io.github.pkeppeler.deepcharter.test.support.AtmosphereScenes.ground;
import static io.github.pkeppeler.deepcharter.test.support.AtmosphereScenes.layer1Floor;
import static io.github.pkeppeler.deepcharter.test.support.AtmosphereScenes.putOutTheLight;
import static io.github.pkeppeler.deepcharter.test.support.AtmosphereScenes.requireZone;
import static io.github.pkeppeler.deepcharter.test.support.AtmosphereScenes.standWithoutSettle;
import static io.github.pkeppeler.deepcharter.test.support.AtmosphereScenes.todayFloor;

import io.github.pkeppeler.deepcharter.test.support.AtmosphereScenes.Holds;
import io.github.pkeppeler.deepcharter.test.support.ClientChecks;
import io.github.pkeppeler.deepcharter.test.support.ClientLight;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.EvidencePins;
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
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Client GameTest for #744 and #761 (campaign world plan, #214): what a player sees where the layer's look comes from its zone biome and
 * not from its dimension type. It runs in {@code deepcharter:campaign_sample}, the campaign world's test dimension, in a world made with
 * the {@code flat_all_dimensions} preset (which adds it), with its scenes at the Ys of the campaign bands. The bottom of a shaft under
 * open sky is read next to a sealed room of the same shape in layer 1's band, and next to the same shaft in a surface biome. The shaft
 * must read as the room does, not as the surface, while the sky timeline runs, at dusk and at night (the biome layer that ADR 0062 adds
 * after the timelines is what makes it so). Then a lit room is rendered in the campaign type and in today's layer dimension, which has
 * the three things a biome cannot set ({@code ambient_light}, {@code cardinal_light: nether}, {@code has_skylight: false}), and an
 * entity's brightness at light 0 is read in both; the {@code [layer-atmosphere]} lines are what ADR 0059 quotes as the measured loss.
 */
public class LayerAtmosphereClientTest implements FabricClientGameTest {
    private static final Logger LOGGER = LoggerFactory.getLogger(LayerAtmosphereClientTest.class);
    /** The blend probe reads down to this many blocks under its edge. */
    private static final int BLEND_BELOW = 24;
    /** The ambient light of layer 1's type, which the campaign type lacks: an entity at light 0 is lit by it alone. */
    private static final float LAYER_1_AMBIENT = 0.06f;

    private static final float AMBIENT_TOLERANCE = 0.001f;

    /** Layer brightness is the sealed room's; a shaft that reads this close to it is at layer brightness. */
    private static final double SAME_LUMA = 3.0;

    private record View(String name, float yaw, float pitch) {}

    private static final List<View> VIEWS =
            List.of(new View("wall", 180, 0), new View("floor", 180, 90), new View("ceiling", 180, -90));

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

            try (Holds holds = Holds.of(server)) {
                theShaftReadsAsTheSealedRoomWhileTheSkyTimelineRuns(context, server, holds);
            }
            try (Holds holds = Holds.of(server)) {
                theBlendAtABiomeEdge(context, server, holds);
            }
            try (Holds holds = Holds.of(server)) {
                whatTheCampaignWorldLoses(context, server, holds);
            }
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
            ClientGameTestContext context, TestServerContext server, Holds holds) {
        int x = SITE;
        BlockPos shaftFloor = build(server, holds, CAMPAIGN, ground(server, x), Optional.of(ZONE), true);
        BlockPos roomFloor = build(server, holds, CAMPAIGN, layer1Floor(x + SPACING), Optional.of(ZONE), false);
        BlockPos controlFloor = build(server, holds, CAMPAIGN, ground(server, x + 2 * SPACING), Optional.empty(), true);
        requireZone(server, CAMPAIGN, shaftFloor);
        requireZone(server, CAMPAIGN, roomFloor);
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
            // The surface is held at the deep night (#1606), as dark as the room, so the surface control's luma no
            // longer
            // tells the two apart: the factors above do. The shaft must still render at the room's brightness.
            ClientChecks.require(
                    Math.abs(shaft.luma() - room.luma()) <= SAME_LUMA,
                    "the bottom of a layer shaft under open sky renders at layer brightness (the sealed room's " + room
                            + ") while the sky timeline runs at " + sky.name() + ", read " + shaft);
        }
    }

    /**
     * The camera's attribute probe blends the biomes round it, so the sky light factor of a layer biome is not 0 within some blocks of a
     * biome that sets none: the surface floor of ADR 0011 is open to layer 1, so a player in the top of layer 1 sees the blend. The layer
     * biome fills everything at or below an edge; the probe is read at distances from the edge, up to the edge and above it.
     */
    private void theBlendAtABiomeEdge(ClientGameTestContext context, TestServerContext server, Holds holds) {
        BlockPos floor = ground(server, SITE + 5 * SPACING);
        int edge = floor.getY() - 40;
        fillBiome(server, holds, CAMPAIGN, floor, ZONE, edge - BLEND_BELOW - MARGIN, edge);
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

    /** The same lit room as the campaign world will render a layer (a biome in the campaign type) and as today's layer dimension does. */
    private void whatTheCampaignWorldLoses(ClientGameTestContext context, TestServerContext server, Holds holds) {
        int x = SITE;
        BlockPos campaign = buildRoom(server, holds, CAMPAIGN, layer1Floor(x + 3 * SPACING), Optional.of(ZONE), true);
        BlockPos today = buildRoom(server, holds, TODAY, todayFloor(x + 4 * SPACING), Optional.empty(), true);
        requireZone(server, CAMPAIGN, campaign);
        requireZone(server, TODAY, today);
        List<Reading> now = lookAround(context, server, TODAY, today, "lit-room-today-");
        List<Reading> campaignWorld = lookAround(context, server, CAMPAIGN, campaign, "lit-room-campaign-");
        for (int i = 0; i < VIEWS.size(); i++) {
            table.add("lit room, %s: today %s; campaign %s; campaign/today %.3f"
                    .formatted(
                            VIEWS.get(i).name(),
                            now.get(i),
                            campaignWorld.get(i),
                            campaignWorld.get(i).luma() / Math.max(now.get(i).luma(), 0.01)));
        }
        // An unlit room shows the ambient floor alone.
        putOutTheLight(server, CAMPAIGN, campaign);
        putOutTheLight(server, TODAY, today);
        Reading dark = look(context, server, TODAY, today, 180, 0, "dark-room-today-wall");
        float entityToday = entityBrightnessAtLightZero(context);
        Reading darkCampaign = look(context, server, CAMPAIGN, campaign, 180, 0, "dark-room-campaign-wall");
        float entityCampaign = entityBrightnessAtLightZero(context);
        table.add("unlit room, wall: today " + dark + "; campaign " + darkCampaign);
        table.add("entity at light 0 (Lightmap.getBrightness, what an entity is lit by): today %.3f; campaign %.3f"
                .formatted(entityToday, entityCampaign));
        ClientChecks.require(
                Math.abs(entityToday - LAYER_1_AMBIENT) <= AMBIENT_TOLERANCE,
                "an entity at light 0 in layer 1 is lit by the type's ambient light of " + LAYER_1_AMBIENT + ", read "
                        + entityToday);
        ClientChecks.require(
                Math.abs(entityCampaign) <= AMBIENT_TOLERANCE,
                "an entity at light 0 in the campaign type is not lit at all, read " + entityCampaign);
    }

    /** The brightness the client gives an entity at block and sky light 0 in the level the camera is in. */
    private static float entityBrightnessAtLightZero(ClientGameTestContext context) {
        return context.computeOnClient(
                client -> Lightmap.getBrightness(ClientLight.clientLevel(client).dimensionType(), 0));
    }

    // ---------------------------------------------------------------------------------------------- reading

    /** Stands the spectator on {@code floor} and reads the middle of the screen. */
    private static Reading look(
            ClientGameTestContext context,
            TestServerContext server,
            BlockPos floor,
            float yaw,
            float pitch,
            String shot) {
        return look(context, server, CAMPAIGN, floor, yaw, pitch, shot);
    }

    private static Reading look(
            ClientGameTestContext context,
            TestServerContext server,
            ResourceKey<Level> dimension,
            BlockPos floor,
            float yaw,
            float pitch,
            String shot) {
        standWithoutSettle(context, server, dimension, floor, yaw, pitch, shot);
        return read(context, floor, shot);
    }

    private static Reading read(ClientGameTestContext context, BlockPos floor, String shot) {
        Reading[] reading = new Reading[1];
        ClientChecks.withHudHidden(context, () -> reading[0] = average(context.takeScreenshot(shot)));
        LOGGER.info(
                "[layer-atmosphere] {}: {}; attributes at the camera: {}",
                shot,
                reading[0],
                attributes(context, floor));
        return reading[0];
    }

    /** Reads each of {@link #VIEWS} of one room: the first view stands, the others turn in place. */
    private static List<Reading> lookAround(
            ClientGameTestContext context,
            TestServerContext server,
            ResourceKey<Level> dimension,
            BlockPos floor,
            String shotPrefix) {
        List<Reading> readings = new ArrayList<>();
        for (View view : VIEWS) {
            String shot = shotPrefix + view.name();
            if (readings.isEmpty()) {
                readings.add(look(context, server, dimension, floor, view.yaw(), view.pitch(), shot));
            } else {
                face(context, server, dimension, floor, view.yaw(), view.pitch(), shot);
                readings.add(read(context, floor, shot));
            }
        }
        return readings;
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
