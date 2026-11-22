package io.github.pkeppeler.deepcharter.test.evidence;

import io.github.pkeppeler.deepcharter.layer.LayerBlocks;
import io.github.pkeppeler.deepcharter.layer.Overgrowth;
import io.github.pkeppeler.deepcharter.layer.OvergrowthTuning;
import io.github.pkeppeler.deepcharter.ore.SlagBrick;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.test.support.ClientSession;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.Hollows;
import io.github.pkeppeler.deepcharter.test.support.RoomCarver;
import io.github.pkeppeler.deepcharter.test.support.TestEntities;
import io.github.pkeppeler.deepcharter.test.support.TestLayers;
import java.util.HashSet;
import java.util.Set;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.CameraType;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * Evidence scenario "overgrowth" (#974): two bores side by side in a stone block of layer 1, in the test biome that stands for the Fungal
 * Hollows, both cut the way a drill cuts them. The left one is bare; the right one is lined, with a slag brick in the wall every four cells.
 * Nobody visits them: the clock is moved on a pass at a time (the production growth pass, called with a later game time), so the
 * time-lapse shows the left bore growing over from its walls inward while the lined one stays open. Then a pod drills into the growth.
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
    private static final OvergrowthTuning TUNING = OvergrowthTuning.DEFAULT;

    @Override
    protected String name() {
        return "overgrowth";
    }

    @Override
    protected void run(ClientGameTestContext context) {
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            context.waitTicks(40);
            BlockPos site = singleplayer.getServer().computeOnServer(server -> {
                ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
                BlockPos middle = new BlockPos(player.getBlockX(), FLOOR_Y, player.getBlockZ());
                ServerLevel one = TestLayers.level(server, 1);
                build(one, middle);
                player.setGameMode(GameType.SURVIVAL);
                player.teleportTo(
                        one, middle.getX() + 0.5, FLOOR_Y, middle.getZ() - 4.5, Set.of(), 0f, LOOK_DOWN, true);
                PodEntity pod = TestEntities.create(PodRegistry.POD, one);
                pod.setPos(middle.getX() + 0.5, FLOOR_Y, middle.getZ() - 4.5);
                one.addFreshEntity(pod);
                if (!player.startRiding(pod)) {
                    throw new AssertionError("the player could not mount the pod");
                }
                return middle;
            });
            ClientWait.until(
                    context,
                    "the player riding a pod in layer 1",
                    client -> client.player != null
                            && client.player.getVehicle() instanceof PodEntity
                            && TestLayers.clientIsIn(client, 1));
            context.runOnClient(client -> {
                client.options.setCameraType(CameraType.THIRD_PERSON_BACK);
                ClientSession.player(client).setXRot(LOOK_DOWN);
            });
            context.waitTicks(40);
            frames(context, 4);
            screenshot(context, "overgrowth-open");

            timeLapse(context, singleplayer, site);
            drillThrough(context, singleplayer, site);
        }
    }

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
            if (!shotGrowing && grown > BORE_WIDTH * BORE_HEIGHT * TUNNEL / 3) {
                screenshot(context, "overgrowth-growing");
                shotGrowing = true;
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

    /** The pod is set at the mouth of the bare bore and drilled into the growth: the slabs go at extra fuel and the bore opens. */
    private void drillThrough(ClientGameTestContext context, TestSingleplayerContext singleplayer, BlockPos site) {
        float fuel = singleplayer.getServer().computeOnServer(server -> {
            PodEntity pod = pod(server);
            pod.setPos(site.getX() - OFFSET + 0.5, FLOOR_Y, site.getZ() - 3.5);
            pod.setYRot(0f);
            pod.setFuel(100f);
            return pod.fuel();
        });
        context.waitTicks(20);
        context.getInput().holdKey(options -> options.keyUp);
        int ticks = 0;
        boolean shot = false;
        while (podZ(singleplayer) < site.getZ() + DRILLED_SLABS && ticks < DRILL_TICKS) {
            context.waitTicks(TICKS_PER_FRAME);
            ticks += TICKS_PER_FRAME;
            frame(context);
            if (!shot && podZ(singleplayer) > site.getZ() - 1) {
                screenshot(context, "overgrowth-drilling");
                shot = true;
            }
        }
        context.getInput().releaseKey(options -> options.keyUp);
        frames(context, 4);
        screenshot(context, "overgrowth-drilled");
        float burned = fuel
                - singleplayer.getServer().computeOnServer(server -> pod(server).fuel());
        if (podZ(singleplayer) < site.getZ() + DRILLED_SLABS) {
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
                level.setBlock(
                        new BlockPos(x + axis, FLOOR_Y + BORE_HEIGHT, z + dz),
                        Blocks.GLOWSTONE.defaultBlockState(),
                        Block.UPDATE_ALL);
            }
        }
        for (int lampX : new int[] {-6, 0, 6}) {
            level.setBlock(
                    new BlockPos(x + lampX, FLOOR_Y + 4, z - 5),
                    Blocks.GLOWSTONE.defaultBlockState(),
                    Block.UPDATE_ALL);
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

    private static PodEntity pod(net.minecraft.server.MinecraftServer server) {
        return TestEntities.vehicle(server.getPlayerList().getPlayers().getFirst(), PodEntity.class);
    }

    private static double podZ(TestSingleplayerContext singleplayer) {
        return singleplayer.getServer().computeOnServer(server -> pod(server).getZ());
    }
}
