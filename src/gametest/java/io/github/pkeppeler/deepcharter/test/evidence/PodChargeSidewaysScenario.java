package io.github.pkeppeler.deepcharter.test.evidence;

import io.github.pkeppeler.deepcharter.ore.HazardBlocks;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.repair.Consumable;
import io.github.pkeppeler.deepcharter.repair.RepairRegistry;
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
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * Evidence scenario "pod-charge-sideways" (#1613): a pod faces a band of Company rock in a sealed stone room. The
 * pilot sets a blasting charge on the face ahead (a right click), the fuse counts down on the action bar with "BACK
 * AWAY", the pod backs out of the reach, and the charge blows: the 2 slabs of the bore's face go and the rock behind
 * them stays; a spectator then looks at the hole from the side.
 */
public class PodChargeSidewaysScenario extends EvidenceScenario {
    private static final int X = 5500;
    private static final int Z = 5100;
    private static final int FLOOR_Y = LIVE_GAUGES_FLOOR_Y;
    private static final int TICKS_PER_FRAME = 3;
    private static final int MAX_TICKS = 900;
    private static final float LOOK_DOWN = -15f;
    /** Past the reach of the blast: the pod's box (1.9 wide) ends more than the 3 blocks of chargeReach from the
     * volume, whose east side is X - 1. */
    private static final double SAFE_X = X + 3.5;
    /** The slabs of the band: the face at X - 2 and the one behind it, which a blasting charge clears, and the one
     * behind that, which stays. */
    private static final int[] BAND = {X - 2, X - 3};

    private static final int STAYS_X = X - 4;

    @Override
    protected String name() {
        return "pod-charge-sideways";
    }

    @Override
    protected void run(ClientGameTestContext context) {
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            context.waitTicks(40);
            singleplayer.getServer().runOnServer(server -> {
                ServerLevel one = TestLayers.level(server, 1);
                RoomCarver.carve(one, X - 11, X + 9, FLOOR_Y - 9, FLOOR_Y + 10, Z - 7, Z + 7, Blocks.STONE);
                RoomCarver.carve(
                        one,
                        new BlockPos(X - 10, FLOOR_Y, Z - 6),
                        new BlockPos(X + 8, FLOOR_Y + 8, Z + 6),
                        Blocks.AIR.defaultBlockState(),
                        Block.UPDATE_ALL);
                for (int z = Z - 4; z <= Z + 3; z++) {
                    for (int y = FLOOR_Y; y < FLOOR_Y + 4; y++) {
                        for (int x : BAND) {
                            one.setBlock(new BlockPos(x, y, z), HazardBlocks.COMPANY_ROCK.defaultBlockState(), 3);
                        }
                        one.setBlock(new BlockPos(STAYS_X, y, z), HazardBlocks.COMPANY_ROCK.defaultBlockState(), 3);
                    }
                }
                for (int x = -6; x <= 6; x += 6) {
                    for (int z = -4; z <= 4; z += 4) {
                        one.setBlock(new BlockPos(X + x, FLOOR_Y + 8, Z + z), Blocks.GLOWSTONE.defaultBlockState(), 3);
                    }
                }
            });
            ClientWait.lightSettled(context, singleplayer.getServer(), 1, X, Z, 13);
            // The band from the side and above, before the pilot boards.
            lookAtBand(singleplayer);
            ClientWait.arrived(context, singleplayer.getServer());
            context.waitTicks(30);
            screenshot(context, "pod-charge-sideways-before");
            singleplayer.getServer().runOnServer(server -> {
                ServerLevel one = TestLayers.level(server, 1);
                ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
                player.setGameMode(GameType.SURVIVAL);
                player.teleportTo(one, X, FLOOR_Y, Z, Set.of(), Direction.WEST.toYRot(), LOOK_DOWN, true);
            });
            ClientWait.arrived(context, singleplayer.getServer());
            singleplayer.getServer().runOnServer(server -> {
                ServerLevel one = TestLayers.level(server, 1);
                ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
                PodEntity pod = TestEntities.create(PodRegistry.POD, one);
                pod.setPos(X, FLOOR_Y, Z);
                pod.setYRot(Direction.WEST.toYRot());
                one.addFreshEntity(pod);
                if (!player.startRiding(pod)) {
                    throw new AssertionError("the player could not mount the pod");
                }
            });
            ClientWait.until(
                    context,
                    "the pilot seated in the pod in layer 1",
                    client -> client.player != null
                            && client.player.getVehicle() instanceof PodEntity
                            && TestLayers.clientIsIn(client, 1));
            ClientWait.until(
                    context,
                    "the pod resting on its floor",
                    client -> TestEntities.vehicle(ClientSession.player(client)).onGround());
            context.runOnClient(client -> {
                client.options.setCameraType(CameraType.THIRD_PERSON_BACK);
                ClientSession.player(client).setXRot(LOOK_DOWN);
            });
            context.waitTicks(30);
            for (int i = 0; i < 6; i++) {
                frame(context);
                context.waitTicks(TICKS_PER_FRAME);
            }

            singleplayer.getServer().runOnServer(server -> {
                ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
                player.getInventory()
                        .setItem(
                                player.getInventory().getSelectedSlot(),
                                new ItemStack(RepairRegistry.item(Consumable.BLASTING_CHARGE)));
                if (!player.gameMode
                        .useItem(
                                player,
                                player.level(),
                                player.getItemInHand(InteractionHand.MAIN_HAND),
                                InteractionHand.MAIN_HAND)
                        .consumesAction()) {
                    throw new AssertionError("the charge should be set on the face ahead");
                }
            });
            for (int i = 0; i < 4; i++) {
                context.waitTicks(TICKS_PER_FRAME);
                frame(context);
            }
            screenshot(context, "pod-charge-sideways-fuse");
            // Back away while the fuse burns.
            context.getInput().holdKey(options -> options.keyDown);
            int ticks = 0;
            while (podX(singleplayer) < SAFE_X && ticks < MAX_TICKS) {
                context.waitTicks(TICKS_PER_FRAME);
                ticks += TICKS_PER_FRAME;
                frame(context);
            }
            context.getInput().releaseKey(options -> options.keyDown);
            if (podX(singleplayer) < SAFE_X) {
                throw new AssertionError("the pod should back clear of the blast, it is at x " + podX(singleplayer));
            }
            ticks = 0;
            while (!blasted(singleplayer) && ticks < MAX_TICKS) {
                context.waitTicks(TICKS_PER_FRAME);
                ticks += TICKS_PER_FRAME;
                frame(context);
            }
            if (!blasted(singleplayer)) {
                throw new AssertionError("the charge should have blown and cleared the face and the slab behind it");
            }
            for (int i = 0; i < 6; i++) {
                context.waitTicks(TICKS_PER_FRAME);
                frame(context);
            }
            screenshot(context, "pod-charge-sideways-blown");
            if (podHull(singleplayer) < 99f) {
                throw new AssertionError("a pod that backed clear should keep its hull");
            }
            if (!stays(singleplayer)) {
                throw new AssertionError("the Company rock round and behind the blast should still stand");
            }
            singleplayer
                    .getServer()
                    .runOnServer(server ->
                            server.getPlayerList().getPlayers().getFirst().stopRiding());
            lookAtBand(singleplayer);
            for (int i = 0; i < 10; i++) {
                context.waitTicks(TICKS_PER_FRAME);
                frame(context);
            }
            screenshot(context, "pod-charge-sideways-through");
        }
    }

    /** Floats the first player, as a spectator, at the side of the room and above the pod's place, looking at the
     * face of the band. */
    private static void lookAtBand(TestSingleplayerContext singleplayer) {
        singleplayer.getServer().runOnServer(server -> {
            ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
            player.setGameMode(GameType.SPECTATOR);
            Vec3 eye = new Vec3(X + 3.0, FLOOR_Y + 4.0, Z + 6.0);
            Vec3 face = new Vec3(X - 2.0, FLOOR_Y + 1.0, Z);
            Vec3 look = face.subtract(eye);
            float yaw = (float) Math.toDegrees(Math.atan2(-look.x, look.z));
            float pitch = (float) Math.toDegrees(Math.atan2(-look.y, Math.hypot(look.x, look.z)));
            player.teleportTo(player.level(), eye.x, eye.y - player.getEyeHeight(), eye.z, Set.of(), yaw, pitch, true);
        });
    }

    private static boolean blasted(TestSingleplayerContext singleplayer) {
        return singleplayer.getServer().computeOnServer(server -> {
            ServerLevel one = TestLayers.level(server, 1);
            for (int z = Z - 1; z <= Z; z++) {
                for (int y = FLOOR_Y; y < FLOOR_Y + 3; y++) {
                    for (int x : BAND) {
                        if (!one.getBlockState(new BlockPos(x, y, z)).isAir()) {
                            return false;
                        }
                    }
                }
            }
            return true;
        });
    }

    private static boolean stays(TestSingleplayerContext singleplayer) {
        return singleplayer.getServer().computeOnServer(server -> {
            ServerLevel one = TestLayers.level(server, 1);
            return one.getBlockState(new BlockPos(STAYS_X, FLOOR_Y, Z)).is(HazardBlocks.COMPANY_ROCK)
                    && one.getBlockState(new BlockPos(BAND[0], FLOOR_Y, Z - 3)).is(HazardBlocks.COMPANY_ROCK)
                    && one.getBlockState(new BlockPos(BAND[0], FLOOR_Y + 3, Z)).is(HazardBlocks.COMPANY_ROCK);
        });
    }

    private static float podHull(TestSingleplayerContext singleplayer) {
        return singleplayer
                .getServer()
                .computeOnServer(server -> TestEntities.vehicle(
                                server.getPlayerList().getPlayers().getFirst(), PodEntity.class)
                        .hull());
    }

    private static double podX(TestSingleplayerContext singleplayer) {
        return singleplayer
                .getServer()
                .computeOnServer(server -> TestEntities.vehicle(
                                server.getPlayerList().getPlayers().getFirst())
                        .getX());
    }
}
