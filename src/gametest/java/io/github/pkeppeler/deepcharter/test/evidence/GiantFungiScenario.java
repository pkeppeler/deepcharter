package io.github.pkeppeler.deepcharter.test.evidence;

import io.github.pkeppeler.deepcharter.layer.LayerChain;
import io.github.pkeppeler.deepcharter.layer.LightSync;
import io.github.pkeppeler.deepcharter.layer.Zones;
import io.github.pkeppeler.deepcharter.surface.GiantLayout;
import io.github.pkeppeler.deepcharter.surface.ParasolShape;
import io.github.pkeppeler.deepcharter.surface.SpireShape;
import io.github.pkeppeler.deepcharter.test.evidence.GiantFungiSpots.Spot;
import io.github.pkeppeler.deepcharter.test.evidence.GiantMockups.Snapshot;
import io.github.pkeppeler.deepcharter.test.support.AtmosphereScenes;
import io.github.pkeppeler.deepcharter.test.support.ClientChecks;
import io.github.pkeppeler.deepcharter.test.support.ClientLight;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.EvidencePins;
import io.github.pkeppeler.deepcharter.test.support.EvidenceWorld;
import io.github.pkeppeler.deepcharter.test.support.TestLayers;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ParticleStatus;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Evidence scenario "giant-fungi" (#1202): the parasol and the spires, and the mix of them with the great stalk, in
 * generated caverns of layer 3 in the campaign world (the design tour's seed), with the sky hidden ({@link
 * EvidencePins#skyHidden}, #1161). Each view is taken as played and, named {@code -nv}, with night vision.
 *
 * <ol>
 * <li>The mix the world grew, in each zone: two giants of two forms in neighbouring chunks, inside the fog, or where
 * the zone has no such pair, the view with the most giants in it.
 * <li>Before and after: the #1149 mock-ups ({@link GiantMockups}) and the new forms, grown at the same place in a
 * Garden cavern and seen from the same camera.
 *   <li>Each form close (12 blocks off) and far (30 blocks off).
 *   <li>Six seeds of each form, one still each, from the same camera.
 * <li>Top down, with the two chunks outlined in the world: a parasol fitted against the east side of its chunk and a
 * spire cluster against the west side of the next, each whole in its own chunk.
 * </ol>
 *
 * Every build is put back as the world grew it before the next. The scenario ends on the title screen.
 */
public class GiantFungiScenario extends EvidenceScenario {
    private static final Logger LOGGER = LoggerFactory.getLogger(GiantFungiScenario.class);
    private static final String SEED = "deepcharter-design-tour";
    private static final int LAYER = 3;
    /** The region searched for the cameras, far from the colony, and the next ones east of it. */
    private static final int REGION_X = 3_000;

    private static final int REGION_Z = 3_000;
    private static final int REGION_STEP = 160;
    private static final int REGIONS = 8;
    /** The two distances the forms are seen from. */
    private static final int CLOSE = 12;

    private static final int FAR = 30;
    /** How far to either side of a close form the camera must see past, in blocks: about the frame's side thirds. */
    private static final int CLOSE_FLANK = 6;
    /** The sheets of six seeds are shot from this far off, so the widest cap and the tallest spire fit the frame. */
    private static final int VARIETY_DISTANCE = 22;
    /** How high up a form the camera aims, as a share of its height. */
    private static final double AIM_SHARE = 0.55;
    /** The box round a build that is put back after it. */
    private static final int BUILD_RADIUS = 18;

    private static final int BUILD_BELOW = 4;
    private static final int BUILD_ABOVE = 36;
    /**
     * The top-down camera stands this high over the chunk outlines, at the least: near enough that the fog leaves
     * their colours, far enough that both chunks fill the frame.
     */
    private static final int TOP_DOWN_OVER = 17;
    /** The box of the top-down still, cleared of the giants the world grew and put back after it. */
    private static final int TOP_DOWN_RADIUS = 26;

    private static final int TOP_DOWN_BELOW = 8;
    private static final int TOP_DOWN_ABOVE = 48;

    /**
     * The parasol and spires seeds shown close, far and in the before and after: an open parasol with a curve, a forked
     * cluster.
     */
    private static final long PARASOL_SEED = 21;

    private static final long SPIRES_SEED = 34;
    /**
     * The six seeds of each form's sheet, picked to show their range: an open cap, a wide tipped bell, an old torn cap
     * on a curving stem, a twin, a small cap on a double curve and a bell with a twin; and six, seven, three, six, four
     * and seven spires, straight, leaning and forking.
     */
    private static final long[] PARASOL_SEEDS = {21, 2, 13, 3, 4, 26};

    private static final long[] SPIRES_SEEDS = {34, 7, 3, 1, 9, 29};
    /** Night vision outlasts the scenario's longest run of stills, so it never fades in one. */
    private static final int NIGHT_VISION_TICKS = 1_000_000;

    private ResourceKey<Level> dimension = Level.OVERWORLD;
    private final Set<Integer> forced = new HashSet<>();

    @Override
    protected String name() {
        return "giant-fungi";
    }

    @Override
    protected void run(ClientGameTestContext context) {
        try {
            pinTheClient();
            fungi(context);
        } finally {
            EvidencePins.reset();
        }
        ClientWait.screen(context, TitleScreen.class);
    }

    /** Holds still what moves on client ticks and frames ({@code evidence.md}), and hides the sky. */
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

    private void fungi(ClientGameTestContext context) {
        try (TestSingleplayerContext singleplayer = context.worldBuilder()
                .setUseConsistentSettings(false)
                .adjustSettings(state -> state.setSeed(SEED))
                .create()) {
            awaitWorld(context);
            EvidenceWorld.pin(context, singleplayer);
            EvidenceWorld.skyPhase(context, singleplayer, EvidenceWorld.SKY_DARKEST);
            TestServerContext server = singleplayer.getServer();
            server.runOnServer(s -> s.getPlayerList().getPlayers().getFirst().setGameMode(GameType.SPECTATOR));
            dimension = server.computeOnServer(s -> TestLayers.dimension(s, LAYER));
            // Minimal particles: the zones' drifting motes would keep the steady-picture check from settling.
            ClientChecks.withHudHidden(context, ParticleStatus.MINIMAL, () -> {
                Spot stage = findSpot(context, server, 0);
                LOGGER.info(
                        "[giant-fungi] staging at {} toward {}, {} clear", stage.feet(), stage.tall(), stage.clear());
                beforeAndAfter(context, server, stage);
                closeAndFar(context, server, stage);
                variety(context, server, stage);
                topDown(context, server, stage);
                for (int zone = 0; zone < Zones.COUNT; zone++) {
                    GiantFungiSpots.MixView view = findMix(context, server, zone);
                    float[] look = lookFrom(view.feet(), view.target());
                    shootBoth(context, server, view.feet(), look[0], look[1], "mix-" + view.zone());
                }
            });
        }
    }

    private GiantFungiSpots.MixView findMix(ClientGameTestContext context, TestServerContext server, int zone) {
        for (int region = 0; region < REGIONS; region++) {
            int x = REGION_X + region * REGION_STEP;
            if (forced.add(x)) {
                forceRegion(server, x);
                awaitLight(context, server);
            }
            Optional<GiantFungiSpots.MixView> view = server.computeOnServer(s -> {
                ServerLevel level = TestLayers.level(s, LAYER);
                LayerChain.Band band = TestLayers.band(level, LAYER);
                // Two neighbours of two forms where the zone has them; a zone of rare sites may not.
                return GiantFungiSpots.pair(level, band, zone, x, REGION_Z)
                        .or(() -> GiantFungiSpots.mix(level, band, zone, x, REGION_Z));
            });
            if (view.isPresent()) {
                LOGGER.info(
                        "[giant-fungi] mix {}: feet {}, {} giant marks in view",
                        view.get().zone(),
                        view.get().feet(),
                        view.get().giants());
                return view.get();
            }
        }
        throw new AssertionError("no giant fungus grew in zone " + zone + " of layer 3 in " + REGIONS + " regions");
    }

    private Spot findSpot(ClientGameTestContext context, TestServerContext server, int zone) {
        for (int region = 0; region < REGIONS; region++) {
            int x = REGION_X + region * REGION_STEP;
            if (forced.add(x)) {
                forceRegion(server, x);
                awaitLight(context, server);
            }
            Optional<Spot> spot = server.computeOnServer(s -> {
                ServerLevel level = TestLayers.level(s, LAYER);
                return GiantFungiSpots.find(level, TestLayers.band(level, LAYER), zone, x, REGION_Z);
            });
            if (spot.isPresent()) {
                LOGGER.info(
                        "[giant-fungi] zone {} stage: feet {} yaw {} tall {} clear {} score {}",
                        zone,
                        spot.get().feet().toShortString(),
                        spot.get().yaw(),
                        spot.get().tall().toShortString(),
                        spot.get().clear(),
                        spot.get().score());
                return spot.get();
            }
        }
        throw new AssertionError("no camera spot in zone " + zone + " of layer 3 in " + REGIONS + " regions");
    }

    private static void forceRegion(TestServerContext server, int x) {
        int reach = GiantFungiSpots.SEARCH_RADIUS + GiantFungiSpots.RAY_REACH;
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

    // ---------------------------------------------------------------------------------------------- the forms

    /** A form grown at the stage's tall focus, rolled from {@code seed} for the room there. */
    private static GiantLayout parasol(Spot stage, long seed) {
        ParasolShape.Params params = ParasolShape.roll(
                        RandomSource.create(seed)::nextInt,
                        stage.clear() - ParasolShape.CAP_ROWS,
                        ParasolShape.Style.DEFAULT)
                .orElseThrow(() -> new AssertionError("no room for a parasol: " + stage.clear()));
        return ParasolShape.layout(params, RandomSource.create(seed + 1)::nextInt);
    }

    private static GiantLayout spires(Spot stage, long seed) {
        SpireShape.Params params = SpireShape.roll(RandomSource.create(seed)::nextInt, stage.clear())
                .orElseThrow(() -> new AssertionError("no room for spires: " + stage.clear()));
        return SpireShape.layout(params, RandomSource.create(seed + 1)::nextInt);
    }

    /**
     * The #1149 mock-up and the new form, each grown at the stage's tall focus and shot from one camera, about {@value
     * #VARIETY_DISTANCE} blocks off and aimed at the middle of the new form's height, so the pair is the same view.
     */
    private void beforeAndAfter(ClientGameTestContext context, TestServerContext server, Spot stage) {
        pair(
                context,
                server,
                stage,
                "parasol",
                parasol(stage, PARASOL_SEED),
                level -> GiantMockups.parasol(level, stage));
        pair(context, server, stage, "spires", spires(stage, SPIRES_SEED), level -> GiantMockups.spires(level, stage));
    }

    private void pair(
            ClientGameTestContext context,
            TestServerContext server,
            Spot stage,
            String form,
            GiantLayout layout,
            Function<ServerLevel, Object> mockup) {
        Vec3 aim = Vec3.atBottomCenterOf(stage.tall()).add(0, (layout.maxY() + 1) * AIM_SHARE, 0);
        BlockPos feet = server.computeOnServer(s -> back(TestLayers.level(s, LAYER), stage, aim, VARIETY_DISTANCE));
        float[] look = lookFrom(feet, aim);
        built(context, server, stage, mockup, () -> shootOne(context, server, feet, look, "before-" + form));
        built(
                context,
                server,
                stage,
                level -> GiantMockups.grow(level, layout, stage.tall()),
                () -> shootOne(context, server, feet, look, "after-" + form));
    }

    private void closeAndFar(ClientGameTestContext context, TestServerContext server, Spot stage) {
        for (String form : List.of("parasol", "spires")) {
            GiantLayout layout = form.equals("parasol") ? parasol(stage, PARASOL_SEED) : spires(stage, SPIRES_SEED);
            Vec3 aim = Vec3.atBottomCenterOf(stage.tall()).add(0, (layout.maxY() + 1) * AIM_SHARE, 0);
            built(context, server, stage, level -> GiantMockups.grow(level, layout, stage.tall()), () -> {
                for (int distance : new int[] {CLOSE, FAR}) {
                    BlockPos feet = server.computeOnServer(s -> back(TestLayers.level(s, LAYER), stage, aim, distance));
                    float[] look = lookFrom(feet, aim);
                    shootBoth(
                            context,
                            server,
                            feet,
                            look[0],
                            look[1],
                            form + "-" + (distance == CLOSE ? "close" : "far"));
                }
            });
        }
    }

    private void variety(ClientGameTestContext context, TestServerContext server, Spot stage) {
        for (String form : List.of("parasol", "spires")) {
            long[] seeds = form.equals("parasol") ? PARASOL_SEEDS : SPIRES_SEEDS;
            for (int i = 0; i < seeds.length; i++) {
                long seed = seeds[i];
                GiantLayout layout = form.equals("parasol") ? parasol(stage, seed) : spires(stage, seed);
                Vec3 aim = Vec3.atBottomCenterOf(stage.tall()).add(0, (layout.maxY() + 1) * AIM_SHARE, 0);
                BlockPos feet =
                        server.computeOnServer(s -> back(TestLayers.level(s, LAYER), stage, aim, VARIETY_DISTANCE));
                float[] look = lookFrom(feet, aim);
                String name = "variety-" + form + "-" + i;
                built(context, server, stage, level -> GiantMockups.grow(level, layout, stage.tall()), () -> {
                    nightVision(server, true);
                    try {
                        shootOne(context, server, feet, look, name);
                    } finally {
                        nightVision(server, false);
                    }
                });
            }
        }
    }

    /**
     * A parasol fitted against the east side of the stage's chunk and a spire cluster against the west side of the
     * chunk east of it, seen from straight above, north up: each whole, in its own chunk. The giants the world grew
     * there are cleared first. The two chunks are outlined in the world, red and yellow, in their border cells a row
     * under the parasol's gills, so the chunk edge runs between the red and the yellow line.
     */
    private void topDown(ClientGameTestContext context, TestServerContext server, Spot stage) {
        ChunkPos chunk = ChunkPos.containing(stage.tall());
        ChunkPos east = new ChunkPos(chunk.x() + 1, chunk.z());
        GiantLayout.Fit parasol = parasol(stage, PARASOL_SEED).fitInChunk(15, 8).orElseThrow();
        GiantLayout.Fit spires = spires(stage, SPIRES_SEED).fitInChunk(0, 8).orElseThrow();
        BlockPos parasolBase = new BlockPos(
                chunk.getMinBlockX() + parasol.baseX(), stage.tall().getY(), chunk.getMinBlockZ() + parasol.baseZ());
        BlockPos spiresBase = new BlockPos(
                east.getMinBlockX() + spires.baseX(), stage.tall().getY(), east.getMinBlockZ() + spires.baseZ());
        int outlineY = parasolBase.getY() + GiantMockups.gillsRow(parasol.layout()) - 1;
        int top = Math.max(
                parasolBase.getY() + parasol.layout().maxY(),
                spiresBase.getY() + spires.layout().maxY());
        BlockPos middle = new BlockPos(east.getMinBlockX(), stage.tall().getY(), chunk.getMinBlockZ() + 8);
        BlockPos eye = middle.atY(Math.max(outlineY + TOP_DOWN_OVER, top + 4));
        Snapshot under = server.computeOnServer(s ->
                Snapshot.around(TestLayers.level(s, LAYER), middle, TOP_DOWN_RADIUS, TOP_DOWN_BELOW, TOP_DOWN_ABOVE));
        Snapshot cleared = server.computeOnServer(s -> Snapshot.clearGiants(
                TestLayers.level(s, LAYER),
                middle.offset(-TOP_DOWN_RADIUS, -TOP_DOWN_BELOW, -TOP_DOWN_RADIUS),
                middle.offset(TOP_DOWN_RADIUS, TOP_DOWN_ABOVE, TOP_DOWN_RADIUS)));
        try {
            server.runOnServer(s -> {
                ServerLevel level = TestLayers.level(s, LAYER);
                LOGGER.info(
                        "[giant-fungi] top down: {} grown cells cleared, parasol {} cells, spires {} cells, outlines {}"
                                + " and {} cells at Y {}",
                        cleared.size(),
                        GiantMockups.grow(level, parasol.layout(), parasolBase),
                        GiantMockups.grow(level, spires.layout(), spiresBase),
                        GiantMockups.outline(
                                level, chunk, outlineY, Blocks.CONCRETE.red().defaultBlockState()),
                        GiantMockups.outline(
                                level, east, outlineY, Blocks.CONCRETE.yellow().defaultBlockState()),
                        outlineY);
                LightSync.holdSendUntilLit(level, parasolBase.offset(-24, 0, -24), spiresBase.offset(24, 0, 24));
            });
            awaitLight(context, server);
            nightVision(server, true);
            // Facing north, so east is to the right.
            shootOne(context, server, eye, new float[] {180f, 90f}, "top-down-chunks");
        } finally {
            nightVision(server, false);
            server.runOnServer(s -> {
                ServerLevel level = TestLayers.level(s, LAYER);
                under.restore(level);
                cleared.restore(level);
                LightSync.holdSendUntilLit(level, parasolBase.offset(-24, 0, -24), spiresBase.offset(24, 0, 24));
            });
            awaitLight(context, server);
        }
    }

    // ---------------------------------------------------------------------------------------------- the camera

    /**
     * Where the camera stands about {@code distance} blocks from the stage's tall focus, seeing {@code target} and the
     * foot: on the side of the stage's own camera if it can, the stage's own camera if no side has one.
     */
    private static BlockPos back(ServerLevel level, Spot stage, Vec3 target, int distance) {
        // Close up, a rock beside the camera fills a side of the frame: the camera there sees past both sides of the
        // form, if any can.
        if (distance == CLOSE) {
            Optional<BlockPos> clear =
                    GiantFungiSpots.cameraAt(level, stage.tall(), target, distance, stage.yaw(), CLOSE_FLANK);
            if (clear.isPresent()) {
                return clear.get();
            }
        }
        return GiantFungiSpots.cameraAt(level, stage.tall(), target, distance, stage.yaw(), 0)
                .orElse(stage.feet());
    }

    /** The yaw and pitch at which an eye at {@code feet} looks at {@code target}. */
    private static float[] lookFrom(BlockPos feet, Vec3 target) {
        Vec3 d = target.subtract(new Vec3(feet.getX() + 0.5, feet.getY() + 1.62, feet.getZ() + 0.5));
        return new float[] {
            (float) Math.toDegrees(Math.atan2(-d.x, d.z)),
            (float) Math.toDegrees(Math.atan2(-d.y, Math.hypot(d.x, d.z)))
        };
    }

    private static void nightVision(TestServerContext server, boolean on) {
        server.runOnServer(s -> {
            ServerPlayer player = s.getPlayerList().getPlayers().getFirst();
            if (on) {
                player.addEffect(new MobEffectInstance(MobEffects.NIGHT_VISION, NIGHT_VISION_TICKS, 0, false, false));
            } else {
                player.removeEffect(MobEffects.NIGHT_VISION);
            }
        });
    }

    private void shootBoth(
            ClientGameTestContext context,
            TestServerContext server,
            BlockPos feet,
            float yaw,
            float pitch,
            String name) {
        shootOne(context, server, feet, new float[] {yaw, pitch}, name);
        nightVision(server, true);
        try {
            shootOne(context, server, feet, new float[] {yaw, pitch}, name + "-nv");
        } finally {
            nightVision(server, false);
        }
    }

    private void shootOne(
            ClientGameTestContext context, TestServerContext server, BlockPos feet, float[] look, String name) {
        AtmosphereScenes.stand(context, server, dimension, feet, look[0], look[1], name);
        screenshot(context, name);
        ClientLight.logStillLight(context, server, dimension, name, feet);
        frame(context);
    }

    /**
     * Clears the giants the world grew round the stage, whole, and builds there, runs {@code shots}, then puts the
     * cavern back as the world grew it.
     */
    private void built(
            ClientGameTestContext context,
            TestServerContext server,
            Spot stage,
            Function<ServerLevel, Object> build,
            Runnable shots) {
        Snapshot snapshot = server.computeOnServer(
                s -> Snapshot.around(TestLayers.level(s, LAYER), stage.tall(), BUILD_RADIUS, BUILD_BELOW, BUILD_ABOVE));
        Snapshot cleared = server.computeOnServer(s -> Snapshot.clearGiants(
                TestLayers.level(s, LAYER),
                stage.tall().offset(-BUILD_RADIUS, -BUILD_BELOW, -BUILD_RADIUS),
                stage.tall().offset(BUILD_RADIUS, BUILD_ABOVE, BUILD_RADIUS)));
        try {
            Object made = server.computeOnServer(s -> {
                ServerLevel level = TestLayers.level(s, LAYER);
                Object result = build.apply(level);
                resendLight(level, stage.tall());
                return result;
            });
            LOGGER.info("[giant-fungi] built {}", made);
            awaitLight(context, server);
            shots.run();
        } finally {
            server.runOnServer(s -> {
                ServerLevel level = TestLayers.level(s, LAYER);
                snapshot.restore(level);
                cleared.restore(level);
                resendLight(level, stage.tall());
            });
            awaitLight(context, server);
        }
    }

    private static void resendLight(ServerLevel level, BlockPos around) {
        LightSync.holdSendUntilLit(
                level, around.offset(-BUILD_RADIUS, 0, -BUILD_RADIUS), around.offset(BUILD_RADIUS, 0, BUILD_RADIUS));
    }

    private static void awaitLight(ClientGameTestContext context, TestServerContext server) {
        ClientWait.until(
                context,
                "the server's light engine to be idle",
                () -> ClientLight.serverLightIdle(context, server),
                () -> "light work pending");
    }
}
