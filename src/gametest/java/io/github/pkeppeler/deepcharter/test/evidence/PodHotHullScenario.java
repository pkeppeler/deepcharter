package io.github.pkeppeler.deepcharter.test.evidence;

import io.github.pkeppeler.deepcharter.client.transmission.TransmissionOverlay;
import io.github.pkeppeler.deepcharter.layer.LayerChain;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.RoomCarver;
import java.util.Set;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.CameraType;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/**
 * Evidence scenario "pod-hot-hull" (#634): a pod sits in a sealed stone room lit by two lamps high up, its hull cool. Lava floods the floor under it and the hull
 * glows red-hot, ember orange along its lit plates, until the pilot pulls the rotor and climbs out. The glow fades once the hull stops burning.
 */
public class PodHotHullScenario extends EvidenceScenario {
    private static final int X = 5300;
    private static final int Z = 5000;
    private static final int FLOOR_Y = 60;
    private static final int TICKS_PER_FRAME = 4;
    private static final int MAX_TICKS = 900;
    private static final float LOOK_DOWN = 20f;
    private static final int OUT_OF_LAVA_Y = FLOOR_Y + 2;

    @Override
    protected String name() {
        return "pod-hot-hull";
    }

    @Override
    protected void run(ClientGameTestContext context) {
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            context.waitTicks(40);
            singleplayer.getServer().runOnServer(server -> {
                ServerLevel one = server.getLevel(LayerChain.dimension(1));
                RoomCarver.carve(one, X - 6, X + 6, FLOOR_Y - 1, FLOOR_Y + 13, Z - 6, Z + 6, Blocks.STONE);
                RoomCarver.carve(
                        one,
                        new BlockPos(X - 5, FLOOR_Y, Z - 5),
                        new BlockPos(X + 5, FLOOR_Y + 12, Z + 5),
                        Blocks.AIR.defaultBlockState(),
                        Block.UPDATE_ALL);
                for (BlockPos lamp :
                        new BlockPos[] {new BlockPos(X + 5, FLOOR_Y + 9, Z), new BlockPos(X - 5, FLOOR_Y + 9, Z)}) {
                    one.setBlock(lamp, Blocks.GLOWSTONE.defaultBlockState(), 3);
                }
                ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
                player.setGameMode(GameType.SURVIVAL);
                player.teleportTo(one, X, FLOOR_Y, Z, Set.of(), 0, LOOK_DOWN, true);
                PodEntity pod = PodRegistry.POD.create(one, EntitySpawnReason.COMMAND);
                pod.setPos(X, FLOOR_Y, Z);
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
                            && client.level.dimension().equals(LayerChain.dimension(1)));
            context.runOnClient(client -> {
                client.options.setCameraType(CameraType.THIRD_PERSON_BACK);
                client.player.setXRot(LOOK_DOWN);
            });
            context.waitTicks(30);
            // Founding the charter queued the employer's transmission; it would cover the HUD lines this scenario
            // shows.
            context.runOnClient(client -> TransmissionOverlay.reset());
            for (int i = 0; i < 6; i++) {
                frame(context);
                context.waitTicks(TICKS_PER_FRAME);
            }
            screenshot(context, "pod-hot-hull-cool");

            singleplayer.getServer().runOnServer(server -> {
                ServerLevel one = server.getLevel(LayerChain.dimension(1));
                for (int x = X - 2; x <= X + 2; x++) {
                    for (int z = Z - 2; z <= Z + 2; z++) {
                        one.setBlock(new BlockPos(x, FLOOR_Y, z), Blocks.LAVA.defaultBlockState(), 3);
                    }
                }
            });
            ClientWait.until(
                    context,
                    "the hull burning",
                    client -> client.player.getVehicle() instanceof PodEntity pod && pod.hullBurning());
            context.runOnClient(client -> TransmissionOverlay.reset());
            for (int i = 0; i < 8; i++) {
                context.waitTicks(TICKS_PER_FRAME);
                frame(context);
            }
            screenshot(context, "pod-hot-hull-burning");
            context.getInput().holdKey(options -> options.keyJump);
            int ticks = 0;
            while (podY(singleplayer) < OUT_OF_LAVA_Y && ticks < MAX_TICKS) {
                context.waitTicks(TICKS_PER_FRAME);
                ticks += TICKS_PER_FRAME;
                frame(context);
            }
            if (podY(singleplayer) < OUT_OF_LAVA_Y) {
                throw new AssertionError("The pilot should climb out of the lava, the pod is at " + podY(singleplayer));
            }
            ClientWait.until(
                    context,
                    "the hull cool again",
                    client -> client.player.getVehicle() instanceof PodEntity pod && !pod.hullBurning());
            for (int i = 0; i < 14; i++) {
                context.waitTicks(TICKS_PER_FRAME);
                frame(context);
            }
            screenshot(context, "pod-hot-hull-out");
            context.getInput().releaseKey(options -> options.keyJump);
        }
    }

    private static double podY(TestSingleplayerContext singleplayer) {
        return singleplayer
                .getServer()
                .computeOnServer(server -> server.getPlayerList()
                        .getPlayers()
                        .getFirst()
                        .getVehicle()
                        .getY());
    }
}
