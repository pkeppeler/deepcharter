package io.github.pkeppeler.deepcharter.test.evidence;

import io.github.pkeppeler.deepcharter.layer.LayerBlocks;
import io.github.pkeppeler.deepcharter.layer.LayerTuning;
import io.github.pkeppeler.deepcharter.layer.LightSync;
import io.github.pkeppeler.deepcharter.layer.Zones;
import io.github.pkeppeler.deepcharter.test.evidence.HollowsMockups.Drifter;
import io.github.pkeppeler.deepcharter.test.evidence.HollowsMockups.Growth;
import io.github.pkeppeler.deepcharter.test.evidence.HollowsMockups.Snapshot;
import io.github.pkeppeler.deepcharter.test.evidence.HollowsSpots.Spot;
import io.github.pkeppeler.deepcharter.test.mixin.BlockStateAccess;
import io.github.pkeppeler.deepcharter.test.support.AtmosphereScenes;
import io.github.pkeppeler.deepcharter.test.support.AtmosphereScenes.Holds;
import io.github.pkeppeler.deepcharter.test.support.ClientChecks;
import io.github.pkeppeler.deepcharter.test.support.ClientLight;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.EvidencePins;
import io.github.pkeppeler.deepcharter.test.support.EvidenceWorld;
import io.github.pkeppeler.deepcharter.test.support.TestLayers;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ParticleStatus;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Evidence scenario "hollows-concepts" (#1147): a concept round of layer 3's open look calls, before #972 merges. Every still is taken in
 * a generated cavern of layer 3 in the campaign world (the design tour's seed), in the zone's own place and light, never in a staged room:
 * one camera per zone ({@link HollowsSpots}), and every option of a call shown from it. The default that #972 ships is always one option.
 *
 * <ol>
 *   <li>Fog (all three zones): the default, a near-black short fog and a lighter, longer, bluer one ({@code hc_<zone>_fog_*} biomes of the
 *       test data pack, filled round the camera).
 *   <li>The glowcaps' light (the Garden): real block light at the default level, at a brighter level, and none, the texture drawn at full
 *       brightness ({@link BlockStateAccess} sets it on the glowcap's states for the still).
 *   <li>Ambient colour (all three zones): the default, a lifted cold one and a teal one ({@code hc_<zone>_ambient_*}).
 *   <li>Giant fungus (the Garden): the great stalk and fungal stalks the game has, a parasol and bracket spires ({@link HollowsMockups}).
 *   <li>Spore gas (the Spore Fields): the default dust motes, a denser haze with cold glints ({@code hc_spore_fields_gas_haze}), and a low
 *       layer that hugs the floor (a mock-up).
 *   <li>The Quiet: the bare hall, and a mock-up of the cathedral, the Badger shrine and the Hush's membrane, at eye level and up at the roof.
 *   <li>Drifters (the Spore Fields) and Overgrowth (the Garden): two mock-ups of each, labelled as such.
 * </ol>
 *
 * A still is named {@code <call>-<option>-<zone>}. A default is one still under the name of each call it belongs to. The scenario ends on
 * the title screen.
 */
public class HollowsConceptsScenario extends EvidenceScenario {
    private static final Logger LOGGER = LoggerFactory.getLogger(HollowsConceptsScenario.class);

    /** The design tour's world: the campaign world as the game makes it. */
    private static final String SEED = "deepcharter-design-tour";

    private static final int LAYER = 3;
    /** The middle of the region searched for the cameras, far from the colony; layer 3's three zones are thirds of its height. */
    private static final int REGION_X = 3_000;

    private static final int REGION_Z = 3_000;
    /** A zone with no camera spot in a region is searched in the next, this far east, up to this many regions. */
    private static final int REGION_STEP = 160;

    private static final int REGIONS = 8;
    /** The camera looks this far down at the cavern; up at a giant fungus; and up at the Quiet's roof. */
    private static final float PITCH = 10f;

    private static final float FUNGUS_PITCH = -10f;
    private static final float ROOF_PITCH = -40f;
    /** The concept biomes are filled this far under and over the camera's feet ({@link AtmosphereScenes#MARGIN} round it). */
    private static final int FILL_BELOW = 24;

    private static final int FILL_ABOVE = 24;
    /** Ticks the particles have to fill the view once they are on: the ambient particles of a biome appear a few a tick. */
    private static final int PARTICLE_TICKS = 60;
    /** The brighter option of the glowcaps' real light. */
    private static final int BRIGHTER_GLOW = 10;
    /** The glowcaps that the light options swap: within this many blocks of the camera's feet. */
    private static final int GLOW_REACH = 48;

    private static final int GLOW_RISE = 24;
    /** The boxes the builds are put back from: round the focus, under it and over it. */
    private static final int BUILD_RADIUS = 10;

    private static final int BUILD_BELOW = 10;
    private static final int BUILD_ABOVE = 28;

    private ResourceKey<Level> dimension = Level.OVERWORLD;
    /** The regions already forced, by their middle's X. */
    private final Set<Integer> forced = new HashSet<>();

    @Override
    protected String name() {
        return "hollows-concepts";
    }

    @Override
    protected void run(ClientGameTestContext context) {
        try {
            pinTheClient();
            hollows(context);
        } finally {
            EvidencePins.reset();
        }
        ClientWait.screen(context, TitleScreen.class);
    }

    /** Holds still what moves on client ticks and frames ({@code evidence.md}). */
    private static void pinTheClient() {
        EvidencePins.spritesFrozen = true;
        EvidencePins.vignetteHidden = true;
        EvidencePins.itemNameHidden = true;
        EvidencePins.lightFlickerOff = true;
        EvidencePins.handsHidden = true;
        EvidencePins.entitiesPosed = true;
        EvidencePins.figureSpawningOff = true;
        EvidencePins.figureAnimationAge = 0f;
    }

    private void hollows(ClientGameTestContext context) {
        try (TestSingleplayerContext singleplayer = context.worldBuilder()
                .setUseConsistentSettings(false)
                .adjustSettings(state -> state.setSeed(SEED))
                .create()) {
            awaitWorld(context);
            EvidenceWorld.pin(context, singleplayer);
            TestServerContext server = singleplayer.getServer();
            server.runOnServer(s -> s.getPlayerList().getPlayers().getFirst().setGameMode(GameType.SPECTATOR));
            dimension = server.computeOnServer(s -> TestLayers.dimension(s, LAYER));
            List<Spot> spots = new ArrayList<>();
            for (int zone = 0; zone < Zones.COUNT; zone++) {
                spots.add(findSpot(context, server, zone));
            }
            spots.forEach(spot -> LOGGER.info(
                    "[hollows-concepts] {}: feet {} yaw {} floor {} focus {} tall {} clear {} score {}",
                    spot.zone(),
                    spot.feet().toShortString(),
                    spot.yaw(),
                    spot.floorY(),
                    spot.focus().toShortString(),
                    spot.tall().toShortString(),
                    spot.clear(),
                    spot.score()));
            try (Holds holds = Holds.of(server)) {
                ClientChecks.withHudHidden(context, () -> {
                    garden(context, server, holds, spots.get(0));
                    sporeFields(context, server, holds, spots.get(1));
                    quiet(context, server, holds, spots.get(2));
                });
            }
        }
    }

    /**
     * The camera of zone {@code zone}: the first region, from {@link #REGION_X} on in steps of {@link #REGION_STEP}, in which {@link
     * HollowsSpots} finds one. Fails after {@link #REGIONS} regions.
     */
    private Spot findSpot(ClientGameTestContext context, TestServerContext server, int zone) {
        for (int region = 0; region < REGIONS; region++) {
            int x = REGION_X + region * REGION_STEP;
            if (forced.add(x)) {
                forceRegion(server, x);
                awaitLight(context, server);
            }
            Optional<Spot> spot = server.computeOnServer(s -> {
                ServerLevel level = TestLayers.level(s, LAYER);
                return HollowsSpots.find(level, TestLayers.band(level, LAYER), zone, x, REGION_Z);
            });
            if (spot.isPresent()) {
                return spot.get();
            }
        }
        throw new AssertionError("no camera spot in zone " + zone + " of layer 3 in " + REGIONS + " regions from "
                + REGION_X + ", " + REGION_Z + "; the log names what each region had");
    }

    /**
     * Forces, and so generates, the chunks the cameras can stand in and see round ({@code x}, {@link #REGION_Z}): {@link
     * HollowsSpots#SEARCH_RADIUS} plus the reach of the search's rays, in every zone at once.
     */
    private static void forceRegion(TestServerContext server, int x) {
        int reach = HollowsSpots.SEARCH_RADIUS + HollowsSpots.RAY_REACH;
        server.runOnServer(s -> {
            ServerLevel level = TestLayers.level(s, LAYER);
            for (int cx = (x - reach) >> 4; cx <= (x + reach) >> 4; cx++) {
                for (int cz = (REGION_Z - reach) >> 4; cz <= (REGION_Z + reach) >> 4; cz++) {
                    // force-ok: an evidence scenario has no GameTestHelper to release through; the world is discarded
                    // when the scenario ends
                    level.setChunkForced(cx, cz, true);
                    level.getChunk(cx, cz);
                }
            }
        });
    }

    // ------------------------------------------------------------------------------------------------- the zones

    private void garden(ClientGameTestContext context, TestServerContext server, Holds holds, Spot spot) {
        int glow = LayerTuning.DEFAULT.glowcapLight();
        shoot(
                context,
                server,
                spot,
                PITCH,
                "fog-default-garden",
                "ambient-default-garden",
                "light-" + glow + "-garden");
        atmospheres(context, server, holds, spot);
        glowcapLight(context, server, spot, BRIGHTER_GLOW, false, "light-" + BRIGHTER_GLOW + "-garden");
        glowcapLight(context, server, spot, 0, true, "light-emissive-garden");
        built(context, server, spot, spot.tall(), FUNGUS_PITCH, "fungus-stalks-garden", HollowsMockups::stalks);
        built(context, server, spot, spot.tall(), FUNGUS_PITCH, "fungus-parasol-garden", HollowsMockups::parasol);
        built(context, server, spot, spot.tall(), FUNGUS_PITCH, "fungus-spires-garden", HollowsMockups::spires);
        for (Growth growth : Growth.values()) {
            built(
                    context,
                    server,
                    spot,
                    spot.focus(),
                    PITCH,
                    "overgrowth-" + growth.name().toLowerCase(Locale.ROOT) + "-garden",
                    (level, s) -> {
                        HollowsMockups.overgrowth(level, s, growth);
                        return growth.name();
                    });
        }
    }

    private void sporeFields(ClientGameTestContext context, TestServerContext server, Holds holds, Spot spot) {
        shoot(
                context,
                server,
                spot,
                PITCH,
                "fog-default-spore_fields",
                "ambient-default-spore_fields",
                "gas-motes-spore_fields");
        atmospheres(context, server, holds, spot);
        atmosphere(context, server, holds, spot, "gas_haze", "gas-haze-spore_fields");
        restoreBiome(server, holds, spot);
        built(
                context,
                server,
                spot,
                spot.focus(),
                PITCH,
                "gas-ground-spore_fields",
                (level, s) -> HollowsMockups.groundGas(level, s) + " tiles");
        for (Drifter look : Drifter.values()) {
            built(
                    context,
                    server,
                    spot,
                    spot.focus(),
                    PITCH,
                    "drifters-" + look.name().toLowerCase(Locale.ROOT) + "-spore_fields",
                    (level, s) -> HollowsMockups.drifters(level, s, look) + " drifters");
        }
    }

    private void quiet(ClientGameTestContext context, TestServerContext server, Holds holds, Spot spot) {
        shoot(context, server, spot, PITCH, "fog-default-quiet", "ambient-default-quiet", "quiet-bare-quiet");
        shoot(context, server, spot, ROOF_PITCH, "quiet-bare_roof-quiet");
        atmospheres(context, server, holds, spot);
        int height = server.computeOnServer(s -> {
            ServerLevel level = TestLayers.level(s, LAYER);
            int ribs = HollowsMockups.quiet(level, spot);
            resendLight(level, spot.tall());
            return ribs;
        });
        LOGGER.info("[hollows-concepts] the Quiet's ribs are {} tall", height);
        awaitLight(context, server);
        shoot(context, server, spot, PITCH, "quiet-cathedral-quiet");
        shoot(context, server, spot, ROOF_PITCH, "quiet-cathedral_roof-quiet");
        server.runOnServer(s -> HollowsMockups.clearEntities(TestLayers.level(s, LAYER)));
    }

    // ------------------------------------------------------------------------------------------- fog and ambient

    /** The fog and ambient options of the zone, each filled round the camera in turn, then the zone's own biome back. */
    private void atmospheres(ClientGameTestContext context, TestServerContext server, Holds holds, Spot spot) {
        for (String option : List.of("fog_dark", "fog_light", "ambient_lifted", "ambient_teal")) {
            atmosphere(context, server, holds, spot, option, option.replace('_', '-') + "-" + spot.zone());
        }
        restoreBiome(server, holds, spot);
    }

    /** Fills the test pack's {@code hc_<zone>_<option>} biome round the camera and takes the still {@code name}. */
    private void atmosphere(
            ClientGameTestContext context,
            TestServerContext server,
            Holds holds,
            Spot spot,
            String option,
            String name) {
        fill(server, holds, spot, "deepcharter-test:hc_" + spot.zone() + "_" + option);
        shoot(context, server, spot, PITCH, name);
    }

    private void restoreBiome(TestServerContext server, Holds holds, Spot spot) {
        fill(server, holds, spot, "deepcharter:" + spot.zone());
    }

    private void fill(TestServerContext server, Holds holds, Spot spot, String biome) {
        BlockPos feet = spot.feet();
        AtmosphereScenes.fillBiome(
                server, holds, dimension, feet, biome, feet.getY() - FILL_BELOW, feet.getY() + FILL_ABOVE);
    }

    // ------------------------------------------------------------------------------------------- glowcap light

    /**
     * The glowcaps round the camera with {@code light} as their block light and, if {@code emissive}, drawn at full brightness: each is
     * taken out, its states changed and put back, so the light engine works the new light out; then the still, and the glowcaps put back
     * with the game's own light the same way.
     */
    private void glowcapLight(
            ClientGameTestContext context,
            TestServerContext server,
            Spot spot,
            int light,
            boolean emissive,
            String name) {
        List<BlockPos> glowcaps = server.computeOnServer(s -> {
            ServerLevel level = TestLayers.level(s, LAYER);
            List<BlockPos> found = new ArrayList<>();
            for (BlockPos pos : BlockPos.betweenClosed(
                    spot.feet().offset(-GLOW_REACH, -GLOW_RISE, -GLOW_REACH),
                    spot.feet().offset(GLOW_REACH, GLOW_RISE, GLOW_REACH))) {
                if (level.getBlockState(pos).is(LayerBlocks.GLOWCAP)) {
                    found.add(pos.immutable());
                }
            }
            return found;
        });
        LOGGER.info(
                "[hollows-concepts] {}: {} glowcaps at light {}, emissive {}", name, glowcaps.size(), light, emissive);
        ClientChecks.require(!glowcaps.isEmpty(), "no glowcap within " + GLOW_REACH + " blocks of the Garden's camera");
        Predicate<BlockState> own =
                access(LayerBlocks.GLOWCAP.defaultBlockState()).deepcharter$emissiveRendering();
        int ownLight = LayerTuning.DEFAULT.glowcapLight();
        try {
            relight(context, server, glowcaps, light, emissive ? state -> true : own);
            shoot(context, server, spot, PITCH, name);
        } finally {
            relight(context, server, glowcaps, ownLight, own);
        }
    }

    private void relight(
            ClientGameTestContext context,
            TestServerContext server,
            List<BlockPos> glowcaps,
            int light,
            Predicate<BlockState> emissive) {
        server.runOnServer(s -> {
            ServerLevel level = TestLayers.level(s, LAYER);
            glowcaps.forEach(pos -> level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS));
            resendLight(level, glowcaps);
        });
        awaitLight(context, server);
        server.runOnServer(s -> {
            for (BlockState state : LayerBlocks.GLOWCAP.getStateDefinition().getPossibleStates()) {
                access(state).deepcharter$setLightEmission(light);
                access(state).deepcharter$setEmissiveRendering(emissive);
            }
            ServerLevel level = TestLayers.level(s, LAYER);
            glowcaps.forEach(pos -> level.setBlock(
                    pos, LayerBlocks.GLOWCAP.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE));
            resendLight(level, glowcaps);
        });
        awaitLight(context, server);
    }

    /**
     * Holds the chunks round {@code changed} until their light is worked out and then sends it to the player, who already holds them:
     * vanilla drops a light change for a chunk that does not tick, and a client that keeps the old light shows another option.
     */
    private static void resendLight(ServerLevel level, List<BlockPos> changed) {
        changed.forEach(pos -> LightSync.holdSendUntilLit(level, pos, pos));
    }

    /** {@link #resendLight(ServerLevel, List)} for every chunk within {@link #BUILD_RADIUS} of {@code around}. */
    private static void resendLight(ServerLevel level, BlockPos around) {
        LightSync.holdSendUntilLit(
                level, around.offset(-BUILD_RADIUS, 0, -BUILD_RADIUS), around.offset(BUILD_RADIUS, 0, BUILD_RADIUS));
    }

    private static BlockStateAccess access(BlockState state) {
        return (BlockStateAccess) (Object) state;
    }

    // ------------------------------------------------------------------------------------------------ mock-ups

    /** A build of {@link HollowsMockups}: makes it round the spot, returns what it made, for the log. */
    private interface Build {
        Object make(ServerLevel level, Spot spot);
    }

    /**
     * Builds {@code build} round the spot, takes the still {@code name} from the camera at {@code pitch}, then takes the mock-up's entities
     * away and puts the blocks round {@code around} back as they were generated.
     */
    private void built(
            ClientGameTestContext context,
            TestServerContext server,
            Spot spot,
            BlockPos around,
            float pitch,
            String name,
            Build build) {
        Snapshot snapshot = server.computeOnServer(
                s -> Snapshot.around(TestLayers.level(s, LAYER), around, BUILD_RADIUS, BUILD_BELOW, BUILD_ABOVE));
        try {
            Object made = server.computeOnServer(s -> {
                ServerLevel level = TestLayers.level(s, LAYER);
                Object result = build.make(level, spot);
                resendLight(level, around);
                return result;
            });
            LOGGER.info("[hollows-concepts] {}: {}", name, made);
            awaitLight(context, server);
            shoot(context, server, spot, pitch, name);
        } finally {
            server.runOnServer(s -> {
                ServerLevel level = TestLayers.level(s, LAYER);
                HollowsMockups.clearEntities(level);
                snapshot.restore(level);
                resendLight(level, around);
            });
            awaitLight(context, server);
        }
    }

    // --------------------------------------------------------------------------------------------------- stills

    /**
     * Stands the camera at the spot facing its heading at {@code pitch} and lets the light and fog settle (particles at their minimum, so the
     * steady-picture check can tell), then turns the particles on, gives them {@value #PARTICLE_TICKS} ticks to fill the view, and takes
     * the still under each of {@code names}, and a frame.
     */
    private void shoot(
            ClientGameTestContext context, TestServerContext server, Spot spot, float pitch, String... names) {
        AtmosphereScenes.stand(context, server, dimension, spot.feet(), spot.yaw(), pitch, names[0]);
        ClientChecks.withHudHidden(context, ParticleStatus.ALL, () -> {
            // tick-wait: the ambient particles appear a few a tick round the camera; nothing reports that the view is
            // full
            context.waitTicks(PARTICLE_TICKS);
            for (String name : names) {
                screenshot(context, name);
            }
        });
        ClientLight.logStillLight(context, server, dimension, names[0], spot.feet());
        frame(context);
    }

    private static void awaitLight(ClientGameTestContext context, TestServerContext server) {
        ClientWait.until(
                context,
                "the server's light engine to be idle",
                () -> ClientLight.serverLightIdle(context, server),
                () -> "light work pending");
    }
}
