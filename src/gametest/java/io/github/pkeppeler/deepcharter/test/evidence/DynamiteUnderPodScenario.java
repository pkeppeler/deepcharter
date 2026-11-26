package io.github.pkeppeler.deepcharter.test.evidence;

import io.github.pkeppeler.deepcharter.ore.HazardBlocks;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.pod.PodSounder;
import io.github.pkeppeler.deepcharter.repair.Consumable;
import io.github.pkeppeler.deepcharter.repair.RepairRegistry;
import io.github.pkeppeler.deepcharter.test.support.ClientSession;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.RoomCarver;
import io.github.pkeppeler.deepcharter.test.support.ScannerPods;
import io.github.pkeppeler.deepcharter.test.support.TestEntities;
import io.github.pkeppeler.deepcharter.test.support.TestLayers;
import io.github.pkeppeler.deepcharter.upgrade.ComponentTrack;
import java.util.Set;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.CameraType;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Evidence scenario "m2-dynamite-under-pod" (#1478): a Mole with a tier 1 seep sounder rests on a stone floor in layer 1, with a gas pocket in
 * each cell of the slab under its feet. The HUD reads SEEPAGE 1. The pilot lights a stick of dynamite from the seat: the slab goes with its
 * pockets, the pod drops one row into the hole, and the SEEPAGE line is gone. A trench in front of the pod cuts the rock open, so the camera
 * sees the rows under the floor. Stills before and after the blast.
 */
public class DynamiteUnderPodScenario extends EvidenceScenario {
    private static final Logger LOGGER = LoggerFactory.getLogger(DynamiteUnderPodScenario.class);
    private static final int X = 6100;
    private static final int Z = 5900;
    private static final int FLOOR_Y = LAYER_ROOM_Y;
    private static final int RADIUS = 6;
    private static final int BED = 6;
    private static final int TRENCH = 4;
    private static final int TICKS_PER_FRAME = 3;
    /** The front camera mirrors the pitch: a negative pitch puts it above the pod, looking down at it and into the trench. */
    private static final float LOOK_DOWN = -30f;

    @Override
    protected String name() {
        return "m2-dynamite-under-pod";
    }

    /** What the server says now: the pod's feet, the slabs down to the pocket the sounder marks (0 for none), and the pockets left under the pod. */
    private record Snapshot(double feetY, int seepage, int pockets) {}

    @Override
    protected void run(ClientGameTestContext context) {
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            context.waitTicks(40);
            singleplayer.getServer().runOnServer(server -> {
                ServerLevel one = TestLayers.level(server, 1);
                build(one);
                ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
                player.setGameMode(GameType.SURVIVAL);
                player.teleportTo(one, X, FLOOR_Y, Z, Set.of(), 0f, LOOK_DOWN, true);
                PodEntity pod = TestEntities.create(PodRegistry.POD, one);
                pod.setPos(X, FLOOR_Y, Z);
                one.addFreshEntity(pod);
                ScannerPods.fit(server, player, pod, ComponentTrack.SOUNDER, 1);
                pod.setFuel(100f);
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
                    "the sounder marking the pockets one slab down",
                    () -> snapshot(singleplayer).seepage() == 1,
                    () -> snapshot(singleplayer).toString());
            context.runOnClient(client -> {
                client.options.setCameraType(CameraType.THIRD_PERSON_FRONT);
                ClientSession.player(client).setXRot(LOOK_DOWN);
            });
            context.waitTicks(30);
            tickFrames(context, 6);
            Snapshot before = snapshot(singleplayer);
            LOGGER.info("[dynamite-under-pod] before: {}", before);
            screenshot(context, "dynamite-under-pod-before");

            singleplayer.getServer().runOnServer(server -> {
                ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
                player.getInventory()
                        .setItem(
                                player.getInventory().getSelectedSlot(),
                                new ItemStack(RepairRegistry.item(Consumable.DYNAMITE)));
                if (!player.gameMode
                        .useItem(
                                player,
                                player.level(),
                                player.getItemInHand(InteractionHand.MAIN_HAND),
                                InteractionHand.MAIN_HAND)
                        .consumesAction()) {
                    throw new AssertionError("the dynamite should go off from the seat");
                }
            });
            for (int i = 0; i < 12; i++) {
                context.waitTicks(TICKS_PER_FRAME);
                frame(context);
            }
            Snapshot after = snapshot(singleplayer);
            LOGGER.info("[dynamite-under-pod] after: {}", after);
            screenshot(context, "dynamite-under-pod-after");
            if (after.pockets() != 0 || after.seepage() != 0 || !(after.feetY() < before.feetY() - 0.5)) {
                throw new AssertionError(
                        "the dynamite should clear the pockets under the pod and drop it a row, before " + before
                                + ", after " + after);
            }
        }
    }

    private static Snapshot snapshot(TestSingleplayerContext singleplayer) {
        return singleplayer.getServer().computeOnServer(server -> {
            PodEntity pod =
                    TestEntities.vehicle(server.getPlayerList().getPlayers().getFirst(), PodEntity.class);
            ServerLevel one = TestLayers.level(server, 1);
            int pockets = 0;
            for (BlockPos cell :
                    BlockPos.betweenClosed(new BlockPos(X - 1, FLOOR_Y - 1, Z - 1), new BlockPos(X, FLOOR_Y - 1, Z))) {
                if (one.getBlockState(cell).is(HazardBlocks.GAS_POCKET)) {
                    pockets++;
                }
            }
            int seepage = PodSounder.reading(pod).map(PodSounder.State::down).orElse(0);
            return new Snapshot(pod.getY(), seepage, pockets);
        });
    }

    /**
     * A stone bed under an open room, a gas pocket in each cell of the Mole's 2 x 2 footprint in the slab under it, and a trench in front of the pod
     * (south, where the front camera stands) that opens the rows under the floor to the camera.
     */
    private static void build(ServerLevel level) {
        RoomCarver.carve(
                level, X - RADIUS, X + RADIUS, FLOOR_Y - BED, FLOOR_Y - 1, Z - RADIUS, Z + RADIUS, Blocks.STONE);
        RoomCarver.carve(
                level,
                new BlockPos(X - RADIUS, FLOOR_Y, Z - RADIUS),
                new BlockPos(X + RADIUS, FLOOR_Y + 9, Z + RADIUS),
                Blocks.AIR.defaultBlockState(),
                Block.UPDATE_ALL);
        RoomCarver.carve(
                level,
                new BlockPos(X - TRENCH, FLOOR_Y - TRENCH, Z + 1),
                new BlockPos(X + TRENCH - 1, FLOOR_Y - 1, Z + RADIUS),
                Blocks.AIR.defaultBlockState(),
                Block.UPDATE_ALL);
        for (BlockPos cell :
                BlockPos.betweenClosed(new BlockPos(X - 1, FLOOR_Y - 1, Z - 1), new BlockPos(X, FLOOR_Y - 1, Z))) {
            level.setBlock(cell, HazardBlocks.GAS_POCKET.defaultBlockState(), 3);
        }
        for (BlockPos lamp : new BlockPos[] {
            new BlockPos(X + RADIUS, FLOOR_Y + 3, Z),
            new BlockPos(X - RADIUS, FLOOR_Y + 3, Z),
            new BlockPos(X, FLOOR_Y + 3, Z + RADIUS),
            new BlockPos(X, FLOOR_Y + 3, Z - RADIUS),
            new BlockPos(X - TRENCH - 1, FLOOR_Y - 2, Z + 3),
            new BlockPos(X + TRENCH, FLOOR_Y - 2, Z + 3),
            new BlockPos(X, FLOOR_Y - TRENCH - 1, Z + 3)
        }) {
            level.setBlock(lamp, Blocks.GLOWSTONE.defaultBlockState(), 3);
        }
    }
}
