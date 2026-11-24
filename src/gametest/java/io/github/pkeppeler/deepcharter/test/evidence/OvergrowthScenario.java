package io.github.pkeppeler.deepcharter.test.evidence;

import io.github.pkeppeler.deepcharter.layer.LayerBlocks;
import io.github.pkeppeler.deepcharter.layer.Overgrowth;
import io.github.pkeppeler.deepcharter.layer.OvergrowthTuning;
import io.github.pkeppeler.deepcharter.ore.SlagBrick;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.test.support.ClientLight;
import io.github.pkeppeler.deepcharter.test.support.ClientSession;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.Hollows;
import io.github.pkeppeler.deepcharter.test.support.RoomCarver;
import io.github.pkeppeler.deepcharter.test.support.TestEntities;
import io.github.pkeppeler.deepcharter.test.support.TestLayers;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.CameraType;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;

/**
 * Evidence scenario "overgrowth" (#974): two bores side by side in a stone block of layer 1, in the test biome that stands for the Fungal
 * Hollows, both cut the way a drill cuts them. The left one is bare; the right one is lined, with a slag brick in the wall every four cells.
 * Nobody visits them: the clock is moved on a pass at a time (the production growth pass, called with a later game time), so the
 * time-lapse shows the left bore growing over from its walls inward while the lined one stays open. Close-up stills show the grown
 * mouth face on, where the moss meets the rock, the film of moss that creeps ahead of the growth and spills round the mouth in patches, the strands
 * that hang from the roof over it, and the growth on the ceiling seen from inside the bore. Then a pod drills into the growth.
 * The place comes from where the world puts the player and from {@link #LIVE_GAUGES_FLOOR_Y}, never a fixed position.
 */
public class OvergrowthScenario extends EvidenceScenario {
    private static final int FLOOR_Y = LIVE_GAUGES_FLOOR_Y;
    private static final int TUNNEL = 12;
    private static final int BORE_WIDTH = 3;
    private static final int BORE_HEIGHT = 3;
    /** The tunnels' axes lie this far either side of the middle; the rock between them is 5 blocks thick. */
    private static final int OFFSET = 4;

    private static final int HALF_WIDTH = 8;
    private static final int TICKS_PER_FRAME = 6;
    private static final int PASSES_PER_FRAME = 3;
    private static final int GROW_FRAMES = 90;
    private static final int DRILL_TICKS = 400;
    private static final int DRILLED_SLABS = 3;
    private static final float LOOK_DOWN = 14f;
    private static final float LOOK_AHEAD = 4f;
    private static final double EYE_HEIGHT = 1.62;
    private static final OvergrowthTuning TUNING = OvergrowthTuning.DEFAULT;

    @Override
    protected String name() {
        return "overgrowth";
    }

    @Override
    protected void run(ClientGameTestContext context) {
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            context.waitTicks(40);
            Scene scene = singleplayer.getServer().computeOnServer(server -> {
                ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
                BlockPos middle = new BlockPos(player.getBlockX(), FLOOR_Y, player.getBlockZ());
                ServerLevel one = TestLayers.level(server, 1);
                build(one, middle);
                // The growth stills are taken from a spectator's eye in the chamber, with the pod parked behind the
                // camera.
                player.setGameMode(GameType.SPECTATOR);
                // The layer's lightmap is near black: night vision lifts it for the stills without touching the world's
                // light.
                player.addEffect(new MobEffectInstance(
                        MobEffects.NIGHT_VISION, MobEffectInstance.INFINITE_DURATION, 0, false, false));
                PodEntity pod = TestEntities.create(PodRegistry.POD, one);
                pod.setPos(middle.getX() - 6.5, FLOOR_Y, middle.getZ() - 6.5);
                one.addFreshEntity(pod);
                return new Scene(middle, pod.getUUID());
            });
            BlockPos site = scene.site();
            // The scene is built while the client is elsewhere, and the player arrives once the server has lit it: a
            // client that is
            // sent a room while the light engine still works on it keeps the stale light (issue 427) and the stills
            // come out dark.
            ClientWait.until(
                    context,
                    "the server's light engine to be idle",
                    () -> ClientLight.serverLightIdle(context, singleplayer.getServer()),
                    () -> "light work pending");
            singleplayer
                    .getServer()
                    .runOnServer(server -> server.getPlayerList()
                            .getPlayers()
                            .getFirst()
                            .teleportTo(
                                    TestLayers.level(server, 1),
                                    site.getX() + 0.5,
                                    FLOOR_Y + 2,
                                    site.getZ() - 6.5,
                                    Set.of(),
                                    0f,
                                    LOOK_AHEAD,
                                    true));
            ClientWait.until(
                    context,
                    "the player in layer 1",
                    client -> client.player != null && TestLayers.clientIsIn(client, 1));
            context.runOnClient(client -> {
                client.options.setCameraType(CameraType.FIRST_PERSON);
                ClientSession.player(client).setXRot(LOOK_AHEAD);
            });
            context.waitTicks(60);
            frames(context, 4);
            screenshot(context, "overgrowth-open");

            timeLapse(context, singleplayer, site);
            closeUps(context, singleplayer, site);
            drillThrough(context, singleplayer, scene);
        }
    }

    /** Where the bores are, and which pod is ours: layer 1 holds others (the wreck sites' Prospectors), so "the first pod" is not it. */
    private record Scene(BlockPos site, UUID pod) {}

    /** Nobody comes: the growth pass runs at a game time later than any visit, a few passes a frame, until the bare bore is closed. */
    private void timeLapse(ClientGameTestContext context, TestSingleplayerContext singleplayer, BlockPos site) {
        long[] clock = {
            singleplayer
                            .getServer()
                            .computeOnServer(
                                    server -> TestLayers.level(server, 1).getGameTime())
                    + TUNING.delayTicks()
                    + 2L * TUNING.visitStampTicks()
        };
        boolean shotGrowing = false;
        boolean shotUnderside = false;
        boolean shotCreep = false;
        for (int frame = 0; frame < GROW_FRAMES; frame++) {
            singleplayer.getServer().runOnServer(server -> {
                for (int pass = 0; pass < PASSES_PER_FRAME; pass++) {
                    passAt(TestLayers.level(server, 1), site, clock[0]);
                    clock[0] += TUNING.passTicks();
                }
            });
            context.waitTicks(TICKS_PER_FRAME);
            frame(context);
            int grown = grown(singleplayer, site, -1);
            if (!shotCreep && grown > 0) {
                // The film runs ahead of the plug along the walls: the first cells to grow, and the moss on the rock
                // round them.
                frames(context, 2);
                screenshot(context, "overgrowth-creep");
                shotCreep = true;
            }
            if (!shotGrowing && grown > BORE_WIDTH * BORE_HEIGHT * TUNNEL / 3) {
                screenshot(context, "overgrowth-growing");
                shotGrowing = true;
            }
            if (!shotUnderside && grown > BORE_WIDTH * BORE_HEIGHT * TUNNEL / 6 && undersideReady(singleplayer, site)) {
                underside(context, singleplayer, site);
                shotUnderside = true;
            }
            if (grown == BORE_WIDTH * BORE_HEIGHT * TUNNEL) {
                break;
            }
        }
        frames(context, 4);
        screenshot(context, "overgrowth-closed");
        if (grown(singleplayer, site, -1) != BORE_WIDTH * BORE_HEIGHT * TUNNEL) {
            throw new AssertionError(
                    "the bare bore should have grown over, " + grown(singleplayer, site, -1) + " cells did");
        }
        if (grown(singleplayer, site, 1) != 0) {
            throw new AssertionError("the lined bore grew over: " + grown(singleplayer, site, 1) + " cells");
        }
    }

    /** True when the bare bore's middle cell is still open and its ceiling three cells in has grown: the eye can sit inside and look up at it. */
    private static boolean undersideReady(TestSingleplayerContext singleplayer, BlockPos site) {
        return singleplayer.getServer().computeOnServer(server -> {
            ServerLevel one = TestLayers.level(server, 1);
            BlockPos middle = new BlockPos(site.getX() - OFFSET, FLOOR_Y + 1, site.getZ());
            BlockPos ceiling = new BlockPos(site.getX() - OFFSET, FLOOR_Y + BORE_HEIGHT - 1, site.getZ() + 3);
            return one.getBlockState(middle).isAir()
                    && one.getBlockState(ceiling).is(LayerBlocks.OVERGROWTH);
        });
    }

    /** The eye sits inside the half-grown bare bore and looks up at the growth on its ceiling, then goes back to the chamber. */
    private void underside(ClientGameTestContext context, TestSingleplayerContext singleplayer, BlockPos site) {
        double x = site.getX() - OFFSET + 0.5;
        lookFrom(
                singleplayer,
                new Vec3(x, FLOOR_Y + 1.0, site.getZ() + 0.5),
                new Vec3(x, FLOOR_Y + BORE_HEIGHT - 0.5, site.getZ() + 3.5));
        context.waitTicks(40);
        frames(context, 2);
        screenshot(context, "overgrowth-underside");
        lookFrom(
                singleplayer,
                new Vec3(site.getX() + 0.5, FLOOR_Y + 2, site.getZ() - 6.5),
                new Vec3(site.getX() + 0.5, FLOOR_Y + 2.5, site.getZ()));
        context.waitTicks(40);
    }

    /** The grown-over mouth of the bare bore, face on, and then its top corner where the moss meets the rock. */
    private void closeUps(ClientGameTestContext context, TestSingleplayerContext singleplayer, BlockPos site) {
        double x = site.getX() - OFFSET + 0.5;
        double z = site.getZ();
        lookFrom(singleplayer, new Vec3(x, FLOOR_Y + 1.5, z - 2.5), new Vec3(x, FLOOR_Y + 1.5, z));
        context.waitTicks(40);
        frames(context, 2);
        screenshot(context, "overgrowth-face");
        lookFrom(
                singleplayer,
                new Vec3(x + 0.5, FLOOR_Y + BORE_HEIGHT + 0.6, z - 1.6),
                new Vec3(x - 1.0, FLOOR_Y + BORE_HEIGHT - 0.5, z));
        context.waitTicks(40);
        frames(context, 2);
        screenshot(context, "overgrowth-edge");
        // The ragged spill on the wall round the mouth, from beside it, and the strands that hang from the chamber's
        // roof over it.
        lookFrom(singleplayer, new Vec3(x + 3.5, FLOOR_Y + 1.5, z - 3.5), new Vec3(x + 1.0, FLOOR_Y + 1.5, z));
        context.waitTicks(40);
        frames(context, 2);
        screenshot(context, "overgrowth-spill");
        lookFrom(singleplayer, new Vec3(x + 1.5, FLOOR_Y + 1.0, z - 3.0), new Vec3(x, FLOOR_Y + 4.5, z - 1.0));
        context.waitTicks(40);
        frames(context, 2);
        screenshot(context, "overgrowth-hanging");
    }

    /** The spectator's eye at {@code eye} in layer 1, turned to look at {@code target}. */
    private static void lookFrom(TestSingleplayerContext singleplayer, Vec3 eye, Vec3 target) {
        Vec3 d = target.subtract(eye);
        float yaw = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
        float pitch = (float) Math.toDegrees(Math.atan2(-d.y, Math.hypot(d.x, d.z)));
        singleplayer
                .getServer()
                .runOnServer(server -> server.getPlayerList()
                        .getPlayers()
                        .getFirst()
                        .teleportTo(
                                TestLayers.level(server, 1),
                                eye.x,
                                eye.y - EYE_HEIGHT,
                                eye.z,
                                Set.of(),
                                yaw,
                                pitch,
                                true));
    }

    /** The pod is set at the mouth of the bare bore and drilled into the growth: the slabs go at extra fuel and the bore opens. */
    private void drillThrough(ClientGameTestContext context, TestSingleplayerContext singleplayer, Scene scene) {
        BlockPos site = scene.site();
        UUID podId = scene.pod();
        float fuel = singleplayer.getServer().computeOnServer(server -> {
            PodEntity pod = pod(server, podId);
            pod.setPos(site.getX() - OFFSET + 0.5, FLOOR_Y, site.getZ() - 3.5);
            pod.setYRot(0f);
            pod.setFuel(100f);
            ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
            player.setGameMode(GameType.SURVIVAL);
            player.teleportTo(
                    TestLayers.level(server, 1), pod.getX(), pod.getY(), pod.getZ(), Set.of(), 0f, LOOK_DOWN, true);
            if (!player.startRiding(pod)) {
                throw new AssertionError("the player could not mount the pod");
            }
            return pod.fuel();
        });
        ClientWait.until(
                context,
                "the player riding the pod",
                client -> client.player != null && client.player.getVehicle() instanceof PodEntity);
        context.runOnClient(client -> {
            client.options.setCameraType(CameraType.THIRD_PERSON_BACK);
            ClientSession.player(client).setXRot(LOOK_DOWN);
        });
        context.waitTicks(20);
        context.getInput().holdKey(options -> options.keyUp);
        int ticks = 0;
        boolean shot = false;
        while (podZ(singleplayer, podId) < site.getZ() + DRILLED_SLABS && ticks < DRILL_TICKS) {
            context.waitTicks(TICKS_PER_FRAME);
            ticks += TICKS_PER_FRAME;
            frame(context);
            if (!shot && podZ(singleplayer, podId) > site.getZ() - 1) {
                screenshot(context, "overgrowth-drilling");
                shot = true;
            }
        }
        context.getInput().releaseKey(options -> options.keyUp);
        frames(context, 4);
        screenshot(context, "overgrowth-drilled");
        float burned = fuel
                - singleplayer
                        .getServer()
                        .computeOnServer(server -> pod(server, podId).fuel());
        if (podZ(singleplayer, podId) < site.getZ() + DRILLED_SLABS) {
            throw new AssertionError(
                    "the pod did not drill " + DRILLED_SLABS + " slabs of growth in " + DRILL_TICKS + " ticks");
        }
        if (!(burned > 0f)) {
            throw new AssertionError("drilling through growth should have burned fuel");
        }
        if (grown(singleplayer, site, -1) >= BORE_WIDTH * BORE_HEIGHT * TUNNEL) {
            throw new AssertionError("the drill took no growth out of the bore");
        }
    }

    /** The stone block, a chamber in front of it, the two bores and the lights. */
    private static void build(ServerLevel level, BlockPos middle) {
        int x = middle.getX();
        int z = middle.getZ();
        BlockPos low = new BlockPos(x - HALF_WIDTH, FLOOR_Y - 3, z - 9);
        BlockPos high = new BlockPos(x + HALF_WIDTH, FLOOR_Y + 6, z + TUNNEL + 1);
        RoomCarver.carve(level, low, high, Blocks.STONE.defaultBlockState());
        RoomCarver.carve(
                level,
                new BlockPos(x - HALF_WIDTH + 1, FLOOR_Y, z - 8),
                new BlockPos(x + HALF_WIDTH - 1, FLOOR_Y + 4, z - 1),
                Blocks.AIR.defaultBlockState(),
                Block.UPDATE_ALL);
        Hollows.fill(level, low, high, Hollows.BIOME);
        for (int axis : new int[] {-OFFSET, OFFSET}) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int y = FLOOR_Y; y < FLOOR_Y + BORE_HEIGHT; y++) {
                    for (int dz = 0; dz < TUNNEL; dz++) {
                        Hollows.cut(level, new BlockPos(x + axis + dx, y, z + dz));
                    }
                }
            }
            // A niche in each wall every four cells: a slag brick in the lined bore, glowstone in the bare one, so both
            // are lit alike.
            Block niche = axis > 0 ? SlagBrick.BLOCK : Blocks.GLOWSTONE;
            for (int dz = 2; dz < TUNNEL; dz += 4) {
                level.setBlock(
                        new BlockPos(x + axis - 2, FLOOR_Y + 1, z + dz), niche.defaultBlockState(), Block.UPDATE_ALL);
                level.setBlock(
                        new BlockPos(x + axis + 2, FLOOR_Y + 1, z + dz), niche.defaultBlockState(), Block.UPDATE_ALL);
            }
            for (int dz = 1; dz < TUNNEL; dz += 3) {
                level.setBlock(
                        new BlockPos(x + axis, FLOOR_Y + BORE_HEIGHT, z + dz),
                        Blocks.GLOWSTONE.defaultBlockState(),
                        Block.UPDATE_ALL);
            }
        }
        // Light for the camera: the chamber's roof, and a lamp over the middle of each mouth.
        for (int lampX : new int[] {-6, -2, 2, 6}) {
            for (int lampZ : new int[] {-7, -4, -2}) {
                level.setBlock(
                        new BlockPos(x + lampX, FLOOR_Y + 5, z + lampZ),
                        Blocks.GLOWSTONE.defaultBlockState(),
                        Block.UPDATE_ALL);
            }
        }
    }

    /** One growth pass, at game time {@code now}, over every chunk of the two bores. */
    private static void passAt(ServerLevel level, BlockPos site, long now) {
        Set<LevelChunk> chunks = new HashSet<>();
        for (int side : new int[] {-1, 1}) {
            for (int dx : new int[] {-1, 1}) {
                for (int dz : new int[] {0, TUNNEL - 1}) {
                    chunks.add(level.getChunkAt(
                            new BlockPos(site.getX() + side * OFFSET + dx, FLOOR_Y, site.getZ() + dz)));
                }
            }
        }
        for (LevelChunk chunk : chunks) {
            Overgrowth.grow(level, chunk, now);
        }
    }

    /** Growth cells in the bore on {@code side} (-1 the bare one, 1 the lined one). */
    private static int grown(TestSingleplayerContext singleplayer, BlockPos site, int side) {
        return singleplayer.getServer().computeOnServer(server -> {
            ServerLevel one = TestLayers.level(server, 1);
            int grown = 0;
            for (int dx = -1; dx <= 1; dx++) {
                for (int y = FLOOR_Y; y < FLOOR_Y + BORE_HEIGHT; y++) {
                    for (int dz = 0; dz < TUNNEL; dz++) {
                        BlockPos cell = new BlockPos(site.getX() + side * OFFSET + dx, y, site.getZ() + dz);
                        grown += one.getBlockState(cell).is(LayerBlocks.OVERGROWTH) ? 1 : 0;
                    }
                }
            }
            return grown;
        });
    }

    private void frames(ClientGameTestContext context, int count) {
        for (int i = 0; i < count; i++) {
            context.waitTicks(TICKS_PER_FRAME);
            frame(context);
        }
    }

    private static PodEntity pod(MinecraftServer server, UUID id) {
        return TestEntities.require(TestLayers.level(server, 1), id, PodEntity.class);
    }

    private static double podZ(TestSingleplayerContext singleplayer, UUID id) {
        return singleplayer
                .getServer()
                .computeOnServer(server -> pod(server, id).getZ());
    }
}
