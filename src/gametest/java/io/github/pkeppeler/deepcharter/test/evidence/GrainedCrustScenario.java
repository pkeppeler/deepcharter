package io.github.pkeppeler.deepcharter.test.evidence;

import io.github.pkeppeler.deepcharter.layer.GrainedCrust;
import io.github.pkeppeler.deepcharter.layer.LayerBlocks;
import io.github.pkeppeler.deepcharter.layer.LayerTuning;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.test.support.ClientLight;
import io.github.pkeppeler.deepcharter.test.support.ClientSession;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.RoomCarver;
import io.github.pkeppeler.deepcharter.test.support.TestEntities;
import io.github.pkeppeler.deepcharter.test.support.TestLayers;
import java.util.Set;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.CameraType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Evidence scenario "grained-crust" (#746, ADR 0060): the four rules of the grained crust, one after the other, in a carved room of layer 1 with a
 * grained wall across it. The player is in survival mode and rides a pod.
 *
 * <ol>
 *   <li>The pod is driven at the wall: the drill skates (sparks, a squeal, heat) and the wall stays.
 *   <li>Cobblestone is placed against the wall, the way a player places it: the first time, a message says why, and on the next pulse the
 *       blocks crumble and drop nothing.
 *   <li>Water is set to flow at the wall and is absorbed there.
 *   <li>The floor under the pod turns to grained crust: the stopped pod loses hull on each pulse, the same pod driven across it loses
 *       none, and the player on foot on it is hurt.
 * </ol>
 *
 * Names and messages are placeholders until the lore session names them (#12).
 */
public class GrainedCrustScenario extends EvidenceScenario {
    private static final int X = 5400;
    private static final int Z = 5400;
    private static final int FLOOR_Y = LIVE_GAUGES_FLOOR_Y;
    /** The wall is two blocks thick, from here east: its west face is the one the pod meets. */
    private static final int WALL_X = X + 5;

    private static final int ROOM_WEST = X - 7;
    private static final int ROOM_EAST = X + 9;
    private static final int HALF_WIDTH = 5;
    private static final int HEIGHT = 8;
    private static final int TICKS_PER_FRAME = 6;
    private static final int LIMIT_TICKS = 600;
    private static final float EAST = -90f;
    private static final float LOOK_DOWN = 18f;

    @Override
    protected String name() {
        return "grained-crust";
    }

    @Override
    protected void run(ClientGameTestContext context) {
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            context.waitTicks(40);
            singleplayer.getServer().runOnServer(server -> {
                ServerLevel one = TestLayers.level(server, 1);
                build(one);
                ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
                player.setGameMode(GameType.SURVIVAL);
                player.teleportTo(one, X - 4, FLOOR_Y, Z + 0.5, Set.of(), EAST, LOOK_DOWN, true);
                PodEntity pod = TestEntities.create(PodRegistry.POD, one);
                pod.setPos(X - 4, FLOOR_Y, Z + 0.5);
                one.addFreshEntity(pod);
                if (!player.startRiding(pod)) {
                    throw new AssertionError("the player could not mount the pod");
                }
            });
            ClientWait.until(
                    context,
                    "the player riding a pod in layer 1",
                    client -> client.player != null
                            && client.player.getVehicle() instanceof PodEntity
                            && TestLayers.isIn(ClientLight.clientLevel(client), 1));
            context.runOnClient(client -> {
                client.options.setCameraType(CameraType.THIRD_PERSON_BACK);
                ClientSession.player(client).setXRot(LOOK_DOWN);
            });
            context.waitTicks(40);
            frames(context, 4);
            screenshot(context, "grained-crust-wall");

            skate(context, singleplayer);
            crumble(context, singleplayer);
            absorb(context, singleplayer);
            squeeze(context, singleplayer);
        }
    }

    /** Rule 1: the pod is driven east into the wall and cannot bite. */
    private void skate(ClientGameTestContext context, TestSingleplayerContext singleplayer) {
        float hull = refill(singleplayer);
        context.getInput().holdKey(options -> options.keyUp);
        int ticks = 0;
        while (podX(singleplayer) < WALL_X - 3 && ticks < LIMIT_TICKS) {
            context.waitTicks(TICKS_PER_FRAME);
            ticks += TICKS_PER_FRAME;
            frame(context);
        }
        for (int i = 0; i < 10; i++) {
            context.waitTicks(TICKS_PER_FRAME);
            frame(context);
            if (i == 6) {
                screenshot(context, "grained-crust-skate");
            }
        }
        context.getInput().releaseKey(options -> options.keyUp);
        float lost = hull - podHull(singleplayer);
        if (lost <= 0f) {
            throw new AssertionError("the skating drill should have heated the hull, it lost " + lost);
        }
        if (!singleplayer
                .getServer()
                .computeOnServer(server -> level(server)
                        .getBlockState(new BlockPos(WALL_X, FLOOR_Y, Z))
                        .is(LayerBlocks.GRAINED_CRUST))) {
            throw new AssertionError("the drill made progress across the grain");
        }
        singleplayer.getServer().runOnServer(server -> {
            PodEntity pod = pod(server);
            pod.setPos(X - 2, FLOOR_Y, Z + 0.5);
            pod.setFuel(100f);
        });
        context.waitTicks(20);
    }

    /** Rule 2: blocks placed against the wall are told off once and crumble on the pulse. */
    private void crumble(ClientGameTestContext context, TestSingleplayerContext singleplayer) {
        refill(singleplayer);
        // Place about three seconds before a pulse, so that the blocks stand long enough to be seen.
        int waited = 0;
        while (!singleplayer.getServer().computeOnServer(server -> {
            int toPulse = GrainedCrust.ticksToPulse(level(server));
            return toPulse > 60 && toPulse < 70;
        })) {
            context.waitTicks(1); // tick-wait: polling the world clock for a moment a few seconds before the pulse
            if (++waited > 2 * LayerTuning.DEFAULT.grainPulseTicks()) {
                throw new AssertionError("the pulse never came round to the moment to place");
            }
        }
        singleplayer.getServer().runOnServer(server -> {
            ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
            for (int y = 0; y < 3; y++) {
                BlockPos wall = new BlockPos(WALL_X, FLOOR_Y + y, Z + 3);
                ItemStack stack = new ItemStack(Items.COBBLESTONE);
                player.setItemInHand(InteractionHand.MAIN_HAND, stack);
                BlockHitResult hit =
                        new BlockHitResult(Vec3.atCenterOf(wall).add(-0.5, 0, 0), Direction.WEST, wall, false);
                player.gameMode.useItemOn(player, level(server), stack, InteractionHand.MAIN_HAND, hit);
            }
        });
        frames(context, 4);
        screenshot(context, "grained-crust-placed");
        int ticks = 0;
        boolean shot = false;
        while (placed(singleplayer) > 0 && ticks < LIMIT_TICKS) {
            context.waitTicks(TICKS_PER_FRAME);
            ticks += TICKS_PER_FRAME;
            frame(context);
            if (!shot && ticksToPulse(singleplayer) < 2 * TICKS_PER_FRAME) {
                screenshot(context, "grained-crust-before-pulse");
                shot = true;
            }
        }
        if (placed(singleplayer) > 0) {
            throw new AssertionError("the placed blocks did not crumble within " + LIMIT_TICKS + " ticks");
        }
        screenshot(context, "grained-crust-crumbled");
        frames(context, 6);
    }

    /** Rule 3: water runs at the wall and is gone where it touches it. */
    private void absorb(ClientGameTestContext context, TestSingleplayerContext singleplayer) {
        singleplayer
                .getServer()
                .runOnServer(server -> level(server)
                        .setBlock(
                                new BlockPos(WALL_X - 4, FLOOR_Y, Z - 3),
                                Blocks.WATER.defaultBlockState(),
                                Block.UPDATE_ALL));
        for (int i = 0; i < 14; i++) {
            context.waitTicks(TICKS_PER_FRAME);
            frame(context);
            if (i == 8) {
                screenshot(context, "grained-crust-absorbed");
            }
        }
        if (singleplayer
                .getServer()
                .computeOnServer(server -> !level(server)
                        .getFluidState(new BlockPos(WALL_X - 1, FLOOR_Y, Z - 3))
                        .isEmpty())) {
            throw new AssertionError("the water stayed against the wall");
        }
        singleplayer.getServer().runOnServer(server -> {
            ServerLevel one = level(server);
            for (int x = ROOM_WEST; x < WALL_X; x++) {
                for (int z = Z - HALF_WIDTH; z <= Z + HALF_WIDTH; z++) {
                    BlockPos pos = new BlockPos(x, FLOOR_Y, z);
                    if (!one.getFluidState(pos).isEmpty()) {
                        one.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                    }
                }
            }
        });
        context.waitTicks(30);
    }

    /** Rule 4: the floor turns to grained crust. A pod that stops on it is squeezed, one that keeps moving is not, and a player on foot is hurt. */
    private void squeeze(ClientGameTestContext context, TestSingleplayerContext singleplayer) {
        float full = refill(singleplayer);
        singleplayer.getServer().runOnServer(server -> {
            ServerLevel one = level(server);
            for (int x = ROOM_WEST; x < WALL_X - 1; x++) {
                for (int z = Z - HALF_WIDTH; z <= Z + HALF_WIDTH; z++) {
                    one.setBlock(
                            new BlockPos(x, FLOOR_Y - 1, z),
                            LayerBlocks.GRAINED_CRUST.defaultBlockState(),
                            Block.UPDATE_CLIENTS);
                }
            }
        });
        float squeezeHull = LayerTuning.DEFAULT.grainSqueezeHull();
        int ticks = 0;
        boolean shot = false;
        while (full - podHull(singleplayer) < 2 * squeezeHull - 0.01
                && ticks < 3 * LayerTuning.DEFAULT.grainPulseTicks()) {
            context.waitTicks(TICKS_PER_FRAME);
            ticks += TICKS_PER_FRAME;
            frame(context);
            if (!shot && full - podHull(singleplayer) > 0.01) {
                screenshot(context, "grained-crust-squeeze-pod");
                shot = true;
            }
        }
        double lostStopped = full - podHull(singleplayer);
        if (Math.abs(lostStopped - 2 * squeezeHull) > 0.01) {
            throw new AssertionError(
                    "a stopped pod should lose " + 2 * squeezeHull + " in two pulses, it lost " + lostStopped);
        }

        // Driven west across the pad from 20 ticks before a pulse, the same pod takes nothing.
        singleplayer.getServer().runOnServer(server -> pod(server).setPos(WALL_X - 3, FLOOR_Y, Z + 0.5));
        int waited = 0;
        while (singleplayer.getServer().computeOnServer(server -> GrainedCrust.ticksToPulse(level(server))) != 20) {
            context.waitTicks(1); // tick-wait: polling the world clock for the moment to set off
            if (++waited > 2 * LayerTuning.DEFAULT.grainPulseTicks()) {
                throw new AssertionError("the pulse never came round to the moment to set off");
            }
        }
        float before = podHull(singleplayer);
        double from = podX(singleplayer);
        context.getInput().holdKey(options -> options.keyDown);
        for (int i = 0; i < 7; i++) {
            context.waitTicks(TICKS_PER_FRAME);
            frame(context);
            if (i == 4) {
                screenshot(context, "grained-crust-moving");
            }
        }
        context.getInput().releaseKey(options -> options.keyDown);
        if (from - podX(singleplayer) < 3.0) {
            throw new AssertionError("the pod should have driven across the pad");
        }
        if (podHull(singleplayer) != before) {
            throw new AssertionError("a moving pod should take no squeeze, its hull went from " + before + " to "
                    + podHull(singleplayer));
        }

        // The player gets out and stands on the pad.
        singleplayer.getServer().runOnServer(server -> {
            ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
            player.stopRiding();
            player.teleportTo(level(server), X - 4, FLOOR_Y, Z - 2.5, Set.of(), EAST, LOOK_DOWN, true);
            player.setHealth(player.getMaxHealth());
            // A fed player heals as fast as the pulse hurts.
            player.getFoodData().setFoodLevel(6);
        });
        context.waitTicks(20);
        frames(context, 2);
        ticks = 0;
        shot = false;
        while (playerHealth(singleplayer) >= 20f - 0.01 && ticks < 2 * LayerTuning.DEFAULT.grainPulseTicks()) {
            context.waitTicks(TICKS_PER_FRAME);
            ticks += TICKS_PER_FRAME;
            frame(context);
        }
        for (int i = 0; i < 6; i++) {
            context.waitTicks(TICKS_PER_FRAME);
            frame(context);
            if (!shot && i == 2) {
                screenshot(context, "grained-crust-squeeze-foot");
                shot = true;
            }
        }
        if (playerHealth(singleplayer) >= 20f - 0.01) {
            throw new AssertionError("the player on foot on the grain should have been squeezed");
        }
    }

    /** The room: a stone bed, air above it, a wall of grained crust across it, and lamps. Sealed, so no worldgen lava reaches it. */
    private static void build(ServerLevel level) {
        RoomCarver.carve(
                level, ROOM_WEST, ROOM_EAST, FLOOR_Y - 8, FLOOR_Y - 1, Z - HALF_WIDTH, Z + HALF_WIDTH, Blocks.STONE);
        RoomCarver.carve(
                level,
                new BlockPos(ROOM_WEST, FLOOR_Y, Z - HALF_WIDTH),
                new BlockPos(ROOM_EAST, FLOOR_Y + HEIGHT, Z + HALF_WIDTH),
                Blocks.AIR.defaultBlockState(),
                Block.UPDATE_ALL);
        for (int x = WALL_X; x <= WALL_X + 1; x++) {
            for (int y = FLOOR_Y; y <= FLOOR_Y + HEIGHT; y++) {
                for (int z = Z - HALF_WIDTH; z <= Z + HALF_WIDTH; z++) {
                    level.setBlock(
                            new BlockPos(x, y, z), LayerBlocks.GRAINED_CRUST.defaultBlockState(), Block.UPDATE_ALL);
                }
            }
        }
        for (BlockPos lamp : new BlockPos[] {
            new BlockPos(X - 6, FLOOR_Y + 4, Z),
            new BlockPos(X - 1, FLOOR_Y + 6, Z - 4),
            new BlockPos(X - 1, FLOOR_Y + 6, Z + 4),
            new BlockPos(X + 2, FLOOR_Y + 3, Z)
        }) {
            level.setBlock(lamp, Blocks.GLOWSTONE.defaultBlockState(), Block.UPDATE_ALL);
        }
    }

    private void frames(ClientGameTestContext context, int count) {
        for (int i = 0; i < count; i++) {
            context.waitTicks(TICKS_PER_FRAME);
            frame(context);
        }
    }

    private static ServerLevel level(MinecraftServer server) {
        return TestLayers.level(server, 1);
    }

    private static PodEntity pod(MinecraftServer server) {
        return server.getPlayerList().getPlayers().getFirst().getVehicle() instanceof PodEntity pod
                ? pod
                : podOnTheFloor(server);
    }

    /** The one pod of the room, when the player is out of it. */
    private static PodEntity podOnTheFloor(MinecraftServer server) {
        return level(server).getEntities(PodRegistry.POD, pod -> true).getFirst();
    }

    private static float refill(TestSingleplayerContext singleplayer) {
        return singleplayer.getServer().computeOnServer(server -> {
            PodEntity pod = pod(server);
            pod.setHull(pod.maxHull());
            pod.setFuel(100f);
            return pod.hull();
        });
    }

    private static float podHull(TestSingleplayerContext singleplayer) {
        return singleplayer.getServer().computeOnServer(server -> pod(server).hull());
    }

    private static double podX(TestSingleplayerContext singleplayer) {
        return singleplayer.getServer().computeOnServer(server -> pod(server).getX());
    }

    private static float playerHealth(TestSingleplayerContext singleplayer) {
        return singleplayer
                .getServer()
                .computeOnServer(
                        server -> server.getPlayerList().getPlayers().getFirst().getHealth());
    }

    private static int ticksToPulse(TestSingleplayerContext singleplayer) {
        return singleplayer.getServer().computeOnServer(server -> GrainedCrust.ticksToPulse(level(server)));
    }

    /** How many of the three cobblestones are still standing against the wall. */
    private static int placed(TestSingleplayerContext singleplayer) {
        return singleplayer.getServer().computeOnServer(server -> {
            int standing = 0;
            for (int y = 0; y < 3; y++) {
                if (level(server)
                        .getBlockState(new BlockPos(WALL_X - 1, FLOOR_Y + y, Z + 3))
                        .is(Blocks.COBBLESTONE)) {
                    standing++;
                }
            }
            return standing;
        });
    }
}
