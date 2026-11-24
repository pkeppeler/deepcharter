package io.github.pkeppeler.deepcharter.test.evidence;

import io.github.pkeppeler.deepcharter.layer.LayerBlocks;
import io.github.pkeppeler.deepcharter.layer.LayerTuning;
import io.github.pkeppeler.deepcharter.layer.LightSync;
import io.github.pkeppeler.deepcharter.layer.Zones;
import io.github.pkeppeler.deepcharter.test.evidence.HollowsMockups.Drifter;
import io.github.pkeppeler.deepcharter.test.evidence.HollowsMockups.Growth;
import io.github.pkeppeler.deepcharter.test.evidence.HollowsMockups.Snapshot;
import io.github.pkeppeler.deepcharter.test.evidence.HollowsSpots.Face;
import io.github.pkeppeler.deepcharter.test.evidence.HollowsSpots.Pocket;
import io.github.pkeppeler.deepcharter.test.evidence.HollowsSpots.Spot;
import io.github.pkeppeler.deepcharter.test.mixin.BlockStateAccess;
import io.github.pkeppeler.deepcharter.test.support.AtmosphereScenes;
import io.github.pkeppeler.deepcharter.test.support.AtmosphereScenes.Holds;
import io.github.pkeppeler.deepcharter.test.support.ClientChecks;
import io.github.pkeppeler.deepcharter.test.support.ClientLight;
import io.github.pkeppeler.deepcharter.test.support.ClientPacks;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.EvidencePins;
import io.github.pkeppeler.deepcharter.test.support.EvidenceWorld;
import io.github.pkeppeler.deepcharter.test.support.TestLayers;
import io.github.pkeppeler.deepcharter.test.support.TestPacks;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ParticleStatus;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
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
 *   <li>Basalt tint and lava (the Garden, one zone so the run stays inside the record job's limit): the shipped rock up close on a wall of
 *       columns ({@link HollowsSpots#wall}) and a dug pocket that opens sealed lava ({@link HollowsSpots#lavaPocket}); the same wall in a
 *       warmer basalt (the {@code basalt_warm} test pack, texgen's shipped recipes in a warmer ramp); and round 1's lava joints on it, a
 *       mock-up.
 * </ol>
 *
 * A still is named {@code <call>-<option>-<zone>}. A default is one still under the name of each call it belongs to. The overworld sky
 * shows behind long caverns (#1161), so every still is taken with it hidden ({@link EvidencePins#skyHidden}) and says so in a corner. The
 * scenario ends on the title screen.
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
    /** The camera looks this far down at the cavern, and this far down at the gas on the floor. */
    private static final float PITCH = 10f;

    private static final float GAS_PITCH = 20f;
    /** The camera aims this high up a giant fungus, and this far ahead at the Drifters, this high over the eye. */
    private static final double FUNGUS_AIM = 9;

    private static final double DRIFTER_AIM = 14;
    private static final double DRIFTER_RISE = 1.5;
    /**
     * The client's render distance for the stills, in chunks, put back after: Fabric's test client uses 5, and past what the client has
     * drawn it shows the sky, so a long cavern showed the sky and the moon where rock is. 6 reaches past the longest fog of the options
     * (72 blocks); more made each still slower than the record job's limit allows.
     */
    private static final int RENDER_DISTANCE = 6;
    /** The concept biomes are filled this far under and over the camera's feet ({@link AtmosphereScenes#MARGIN} round it). */
    private static final int FILL_BELOW = 24;

    private static final int FILL_ABOVE = 24;
    /** Ticks the particles have to fill the view once they are on: the ambient particles of a biome appear a few a tick. */
    private static final int PARTICLE_TICKS = 40;
    /** The ground gas: particles spawned a tick, how high over the floor, and the seed of where. */
    private static final int GAS_PER_TICK = 60;

    private static final double GAS_RISE = 0.8;
    private static final long GAS_SEED = 1147;
    /** The brighter option of the glowcaps' real light. */
    private static final int BRIGHTER_GLOW = 10;
    /** The glowcaps that the light options swap: within this many blocks of the camera's feet. */
    private static final int GLOW_REACH = 48;

    private static final int GLOW_RISE = 24;
    /** The boxes the builds are put back from: round the focus, under it and over it. */
    private static final int BUILD_RADIUS = 14;

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
        EvidencePins.skyHidden = true;
    }

    private void hollows(ClientGameTestContext context) {
        try (TestSingleplayerContext singleplayer = context.worldBuilder()
                .setUseConsistentSettings(false)
                .adjustSettings(state -> state.setSeed(SEED))
                .create()) {
            awaitWorld(context);
            EvidenceWorld.pin(context, singleplayer);
            // The sky at its darkest, though it is hidden: the clock also sets the fog colour where a cavern runs past
            // what
            // the client has drawn, and at the pinned dusk that is salmon.
            EvidenceWorld.skyPhase(context, singleplayer, EvidenceWorld.SKY_DARKEST);
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
            int renderDistance = context.computeOnClient(
                    client -> client.options.renderDistance().get());
            try (Holds holds = Holds.of(server)) {
                context.runOnClient(client -> client.options.renderDistance().set(RENDER_DISTANCE));
                ClientChecks.withHudHidden(context, () -> {
                    garden(context, server, holds, spots.get(0));
                    sporeFields(context, server, holds, spots.get(1));
                    quiet(context, server, holds, spots.get(2));
                });
            } finally {
                context.runOnClient(client -> client.options.renderDistance().set(renderDistance));
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
                ahead(spot, PITCH),
                "fog-default-garden",
                "ambient-default-garden",
                "light-" + glow + "-garden");
        atmospheres(context, server, holds, spot);
        glowcapLight(context, server, spot, BRIGHTER_GLOW, false, "light-" + BRIGHTER_GLOW + "-garden");
        glowcapLight(context, server, spot, 0, true, "light-emissive-garden");
        View fungus = aim(spot, Vec3.atBottomCenterOf(spot.tall()).add(0, FUNGUS_AIM, 0));
        built(context, server, spot, spot.tall(), fungus, "fungus-stalks-garden", HollowsMockups::stalks);
        built(context, server, spot, spot.tall(), fungus, "fungus-parasol-garden", HollowsMockups::parasol);
        built(context, server, spot, spot.tall(), fungus, "fungus-spires-garden", HollowsMockups::spires);
        for (Growth growth : Growth.values()) {
            built(
                    context,
                    server,
                    spot,
                    spot.focus(),
                    aim(spot, Vec3.atCenterOf(spot.focus())),
                    "overgrowth-" + growth.name().toLowerCase(Locale.ROOT) + "-garden",
                    (level, s) -> {
                        HollowsMockups.overgrowth(level, s, growth);
                        return growth.name();
                    });
        }
        basalt(context, server, spot);
    }

    private void sporeFields(ClientGameTestContext context, TestServerContext server, Holds holds, Spot spot) {
        shoot(
                context,
                server,
                spot,
                ahead(spot, PITCH),
                "fog-default-spore_fields",
                "ambient-default-spore_fields",
                "gas-motes-spore_fields");
        atmospheres(context, server, holds, spot);
        // The haze differs from the zone's own biome in its particles alone, which the steady-picture check cannot wait
        // out: the
        // camera settles in the zone's biome and the haze is filled after.
        shootAfter(
                context,
                server,
                spot,
                ahead(spot, PITCH),
                () -> fill(server, holds, spot, "deepcharter-test:hc_spore_fields_gas_haze"),
                client -> {},
                "gas-haze-spore_fields");
        restoreBiome(server, holds, spot);
        groundGas(context, server, spot);
        for (Drifter look : Drifter.values()) {
            built(
                    context,
                    server,
                    spot,
                    spot.at(spot.feet(), DRIFTER_AIM, 0, 0),
                    aim(spot, spot.eye().add(spot.forward().scale(DRIFTER_AIM)).add(0, DRIFTER_RISE, 0)),
                    "drifters-" + look.name().toLowerCase(Locale.ROOT) + "-spore_fields",
                    (level, s) -> HollowsMockups.drifters(level, s, look) + " drifters");
        }
    }

    private void quiet(ClientGameTestContext context, TestServerContext server, Holds holds, Spot spot) {
        int ribs = HollowsMockups.ribHeight(spot);
        View hall = aim(spot, Vec3.atBottomCenterOf(spot.tall()).add(0, ribs / 3.0, 0));
        View roof = aim(spot, Vec3.atBottomCenterOf(spot.tall()).add(0, ribs, 0));
        shoot(context, server, spot, ahead(spot, PITCH), "fog-default-quiet", "ambient-default-quiet");
        shoot(context, server, spot, hall, "quiet-bare-quiet");
        shoot(context, server, spot, roof, "quiet-bare_roof-quiet");
        atmospheres(context, server, holds, spot);
        int height = server.computeOnServer(s -> {
            ServerLevel level = TestLayers.level(s, LAYER);
            int built = HollowsMockups.quiet(level, spot);
            resendLight(level, spot.tall());
            return built;
        });
        LOGGER.info("[hollows-concepts] the Quiet's ribs are {} tall", height);
        awaitLight(context, server);
        shoot(context, server, spot, hall, "quiet-cathedral-quiet");
        shoot(context, server, spot, roof, "quiet-cathedral_roof-quiet");
        server.runOnServer(s -> HollowsMockups.clearEntities(TestLayers.level(s, LAYER)));
    }

    // ---------------------------------------------------------------------------------------------- basalt and lava

    /**
     * Call 8 round the Garden's camera: the shipped rock on a wall of columns up close and a dug pocket that opens sealed lava, then the
     * wall with round 1's lava joints (a mock-up) and in the warmer basalt pack, last, so the client reloads only to turn it on and off. A
     * view with no wall or lava near the camera is left out and logged.
     */
    private void basalt(ClientGameTestContext context, TestServerContext server, Spot spot) {
        Optional<Face> wall = server.computeOnServer(s -> HollowsSpots.wall(TestLayers.level(s, LAYER), spot));
        Optional<Pocket> pocket =
                server.computeOnServer(s -> HollowsSpots.lavaPocket(TestLayers.level(s, LAYER), spot));
        LOGGER.info("[hollows-concepts] basalt: wall {}, lava pocket {}", wall, pocket);
        wall.ifPresent(face -> shoot(context, server, face.camera(), facing(face.camera()), "basalt-default-garden"));
        pocket.ifPresent(p -> built(
                context,
                server,
                p.camera(),
                p.lava(),
                facing(p.camera()),
                "basalt-default_lava-garden",
                (level, s) -> HollowsMockups.dig(level, p) + " blocks dug, " + p.depth() + " deep"));
        if (wall.isEmpty()) {
            LOGGER.warn(
                    "[hollows-concepts] no wall of columns near the Garden's camera: no basalt-default, -seams or -warm still");
            return;
        }
        Face face = wall.get();
        built(
                context,
                server,
                face.camera(),
                face.rock(),
                facing(face.camera()),
                "basalt-seams-garden",
                (level, s) -> HollowsMockups.lavaJoints(level, face) + " blocks of joint turned to lava");
        ClientPacks.enable(context, TestPacks.BASALT_WARM);
        try {
            shoot(context, server, face.camera(), facing(face.camera()), "basalt-warm-garden");
        } finally {
            ClientPacks.disable(context, TestPacks.BASALT_WARM);
        }
    }

    /** A close camera turned to the middle of what it faces. */
    private static View facing(Spot camera) {
        return aim(camera, Vec3.atCenterOf(camera.focus()));
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
        shoot(context, server, spot, ahead(spot, PITCH), name);
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
            shoot(context, server, spot, ahead(spot, PITCH), name);
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
     * Builds {@code build} round the spot, takes the still {@code name} from the camera turned to {@code view}, then takes the mock-up's entities
     * away and puts the blocks round {@code around} back as they were generated.
     */
    private void built(
            ClientGameTestContext context,
            TestServerContext server,
            Spot spot,
            BlockPos around,
            View view,
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
            shoot(context, server, spot, view, name);
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

    /**
     * A low gas layer that hugs the floor ahead of the camera, a mock-up: glowing particles spawned on the client over the floor cells
     * {@link HollowsMockups#gasFloor} finds, {@value #GAS_PER_TICK} a tick at up to {@value #GAS_RISE} over the floor, the same cells in
     * every run.
     */
    private void groundGas(ClientGameTestContext context, TestServerContext server, Spot spot) {
        List<Vec3> floor = server.computeOnServer(s -> HollowsMockups.gasFloor(TestLayers.level(s, LAYER), spot));
        LOGGER.info("[hollows-concepts] gas-ground-spore_fields: {} floor cells", floor.size());
        Random random = new Random(GAS_SEED);
        try {
            shootAfter(
                    context,
                    server,
                    spot,
                    ahead(spot, GAS_PITCH),
                    () -> {},
                    client -> {
                        for (int i = 0; i < GAS_PER_TICK && !floor.isEmpty(); i++) {
                            Vec3 cell = floor.get(random.nextInt(floor.size()));
                            ClientLight.clientLevel(client)
                                    .addParticle(
                                            ParticleTypes.GLOW,
                                            cell.x + random.nextDouble() - 0.5,
                                            cell.y + random.nextDouble() * GAS_RISE,
                                            cell.z + random.nextDouble() - 0.5,
                                            0,
                                            0,
                                            0);
                        }
                    },
                    "gas-ground-spore_fields");
        } finally {
            server.runOnServer(s -> HollowsMockups.clearEntities(TestLayers.level(s, LAYER)));
        }
    }

    // --------------------------------------------------------------------------------------------------- stills

    /** Where the camera faces: its yaw and pitch. */
    private record View(float yaw, float pitch) {}

    /** The spot's own heading, at {@code pitch}. */
    private static View ahead(Spot spot, float pitch) {
        return new View(spot.yaw(), pitch);
    }

    /** The camera turned to look at {@code target}. */
    private static View aim(Spot spot, Vec3 target) {
        float[] look = spot.look(target);
        return new View(look[0], look[1]);
    }

    private void shoot(ClientGameTestContext context, TestServerContext server, Spot spot, View view, String... names) {
        shootAfter(context, server, spot, view, () -> {}, client -> {}, names);
    }

    /**
     * Stands the camera at the spot turned to {@code view} and lets the light and fog settle (particles at their minimum, so the
     * steady-picture check can tell), runs {@code then}, turns the particles on and runs {@code eachTick} on the client each tick for turns the particles on, gives them
     * {@value #PARTICLE_TICKS} ticks to fill the view, and takes the still under each of {@code names}, and a frame.
     */
    private void shootAfter(
            ClientGameTestContext context,
            TestServerContext server,
            Spot spot,
            View view,
            Runnable then,
            Consumer<Minecraft> eachTick,
            String... names) {
        AtmosphereScenes.stand(context, server, dimension, spot.feet(), view.yaw(), view.pitch(), names[0]);
        then.run();
        ClientChecks.withHudHidden(context, ParticleStatus.ALL, () -> {
            for (int tick = 0; tick < PARTICLE_TICKS; tick++) {
                context.runOnClient(eachTick::accept);
                // tick-wait: the ambient particles appear a few a tick round the camera; nothing reports that the view
                // is full
                context.waitTicks(1);
            }
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
