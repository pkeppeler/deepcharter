package io.github.pkeppeler.deepcharter.test.evidence;

import io.github.pkeppeler.deepcharter.client.transmission.TransmissionOverlay;
import io.github.pkeppeler.deepcharter.ore.HazardBlocks;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.pod.PodSounder;
import io.github.pkeppeler.deepcharter.repair.Consumable;
import io.github.pkeppeler.deepcharter.test.support.ChargeUse;
import io.github.pkeppeler.deepcharter.test.support.ClientSession;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.RoomCarver;
import io.github.pkeppeler.deepcharter.test.support.ScannerPods;
import io.github.pkeppeler.deepcharter.test.support.TestEntities;
import io.github.pkeppeler.deepcharter.test.support.TestLayers;
import io.github.pkeppeler.deepcharter.upgrade.ComponentTrack;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.CameraType;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Evidence scenario "m2-dynamite-under-pod" (#1478): a Mole with a tier 1 seep sounder rests on a stone floor in layer 1, with a gas pocket in
 * each cell of the slab under its feet (a pocket looks like stone). The HUD reads SEEPAGE 1. The pilot lights a stick of dynamite from the
 * seat: the slab goes with its pockets, the pod drops one row into the hole, and the SEEPAGE line is gone. A trench in front of the pod cuts
 * the rock open, so a spectator at its far end sees the rows under the floor. Stills from the seat and from the trench, before and after.
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
    private static final float LOOK_DOWN = 45f;
    /** Set on the server thread when the pod is placed; read back there. */
    private static UUID podId = new UUID(0, 0);

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
            // The spectator goes first, so the chunks are loaded when the pod is placed in them.
            spectate(singleplayer);
            ClientWait.until(context, "the spectator in layer 1", client -> TestLayers.clientIsIn(client, 1));
            ClientWait.chunksAround(context, X, Z, 1);
            singleplayer.getServer().runOnServer(server -> {
                ServerLevel one = TestLayers.level(server, 1);
                build(one);
                PodEntity pod = TestEntities.create(PodRegistry.POD, one);
                pod.setPos(X, FLOOR_Y, Z);
                one.addFreshEntity(pod);
                podId = pod.getUUID();
                ScannerPods.fit(server, server.getPlayerList().getPlayers().getFirst(), pod, ComponentTrack.SOUNDER, 1);
                pod.setFuel(100f);
            });
            cutaway(context, singleplayer, "dynamite-under-pod-cutaway-before");

            singleplayer.getServer().runOnServer(server -> {
                ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
                player.setGameMode(GameType.SURVIVAL);
                player.teleportTo(TestLayers.level(server, 1), X, FLOOR_Y, Z, Set.of(), 0f, LOOK_DOWN, true);
                PodEntity pod = podAt(server);
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
                client.options.setCameraType(CameraType.FIRST_PERSON);
                ClientSession.player(client).setXRot(LOOK_DOWN);
            });
            settle(context);
            Snapshot before = snapshot(singleplayer);
            LOGGER.info("[dynamite-under-pod] before: {}", before);
            screenshot(context, "dynamite-under-pod-seat-before");

            singleplayer.getServer().runOnServer(server -> {
                ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
                if (!ChargeUse.use(player, Consumable.DYNAMITE).consumesAction()) {
                    throw new AssertionError("the dynamite should go off from the seat");
                }
            });
            for (int i = 0; i < 12; i++) {
                context.waitTicks(TICKS_PER_FRAME);
                frame(context);
            }
            settle(context);
            Snapshot after = snapshot(singleplayer);
            LOGGER.info("[dynamite-under-pod] after: {}", after);
            screenshot(context, "dynamite-under-pod-seat-after");
            cutaway(context, singleplayer, "dynamite-under-pod-cutaway-after");
            if (after.pockets() != 0 || after.seepage() != 0 || !(after.feetY() < before.feetY() - 0.5)) {
                throw new AssertionError(
                        "the dynamite should clear the pockets under the pod and drop it a row, before " + before
                                + ", after " + after);
            }
        }
    }

    /** A still from the far end of the trench. */
    private void cutaway(ClientGameTestContext context, TestSingleplayerContext singleplayer, String still) {
        spectate(singleplayer);
        context.waitTicks(40);
        settle(context);
        screenshot(context, still);
    }

    /** Floats the player, as a spectator, at the far end of the trench, looking back at the pod and the cut face of the rows under it. */
    private static void spectate(TestSingleplayerContext singleplayer) {
        singleplayer.getServer().runOnServer(server -> {
            ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
            player.stopRiding();
            player.setGameMode(GameType.SPECTATOR);
            Vec3 eye = new Vec3(X + 0.5, FLOOR_Y + 0.8, Z + TRENCH + 0.5);
            Vec3 target = new Vec3(X, FLOOR_Y - 0.5, Z);
            Vec3 d = target.subtract(eye);
            float yaw = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
            float pitch = (float) Math.toDegrees(Math.atan2(-d.y, Math.hypot(d.x, d.z)));
            player.teleportTo(
                    TestLayers.level(server, 1),
                    eye.x,
                    eye.y - player.getEyeHeight(),
                    eye.z,
                    Set.of(),
                    yaw,
                    pitch,
                    true);
        });
    }

    /** Clears the welcome transmission and the toasts, then records a few frames so the picture is steady. */
    private void settle(ClientGameTestContext context) {
        context.runOnClient(client -> {
            TransmissionOverlay.reset();
            client.gui.toastManager().clear();
        });
        for (int i = 0; i < 6; i++) {
            context.waitTicks(TICKS_PER_FRAME);
            frame(context);
        }
    }

    /** The pod by its id, which the server holds whether or not the player rides it. */
    private static PodEntity podAt(MinecraftServer server) {
        if (!(TestLayers.level(server, 1).getEntity(podId) instanceof PodEntity pod)) {
            throw new AssertionError("the pod " + podId + " is gone");
        }
        return pod;
    }

    /** The cells of the slab under the Mole's 2 x 2 footprint, where the pockets lie. */
    private static Iterable<BlockPos> slabUnderPod() {
        return BlockPos.betweenClosed(new BlockPos(X - 1, FLOOR_Y - 1, Z - 1), new BlockPos(X, FLOOR_Y - 1, Z));
    }

    private static Snapshot snapshot(TestSingleplayerContext singleplayer) {
        return singleplayer.getServer().computeOnServer(server -> {
            PodEntity pod = podAt(server);
            ServerLevel one = TestLayers.level(server, 1);
            int pockets = 0;
            for (BlockPos cell : slabUnderPod()) {
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
        for (BlockPos cell : slabUnderPod()) {
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
