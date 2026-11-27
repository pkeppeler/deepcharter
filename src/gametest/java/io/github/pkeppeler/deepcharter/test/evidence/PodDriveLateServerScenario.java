package io.github.pkeppeler.deepcharter.test.evidence;

import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.test.PodShellClientTest;
import io.github.pkeppeler.deepcharter.test.support.ClientSession;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.OpenGround;
import io.github.pkeppeler.deepcharter.test.support.PacketJitter;
import io.github.pkeppeler.deepcharter.test.support.TestEntities;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.CameraType;
import net.minecraft.world.level.block.Blocks;

/**
 * Evidence scenario "pod-drive-late-server" (#1041): the pod driving along a checkered floor of open ground through a server that runs
 * late, one frame for every client tick. {@link PacketJitter#stall} holds the server's moves for 6 ticks, then for 3, and then for 4
 * ticks across the release of the key, so the pod stops while the client is blind. A pod that stands still and lunges shows as a
 * freeze and a jump between frames; one that rides the stall out does not.
 */
public class PodDriveLateServerScenario extends EvidenceScenario {
    private static final int DRIVE_FRAMES = 30;
    private static final int LONG_STALL_TICKS = 6;
    private static final int SHORT_STALL_TICKS = 3;
    private static final int STOP_STALL_TICKS = 4;
    private static final int AFTER_STALL_FRAMES = 25;
    private static final int STOP_FRAMES = 40;

    @Override
    protected String name() {
        return "pod-drive-late-server";
    }

    @Override
    protected void run(ClientGameTestContext context) {
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            context.waitTicks(40);
            // A checkered floor of open ground, so the motion shows and nothing stops the drive.
            OpenGround.prepare(
                    context,
                    singleplayer.getServer(),
                    player -> true,
                    (x, z) -> (Math.floorDiv(x, 2) + Math.floorDiv(z, 2)) % 2 == 0
                            ? Blocks.CONCRETE.white()
                            : Blocks.CONCRETE.black());
            PodShellClientTest.mountFirstPlayer(singleplayer.getServer());
            ClientWait.until(
                    context,
                    "the player riding a pod",
                    client -> ClientSession.player(client).getVehicle() instanceof PodEntity);
            // The shared mount helper starts the pod damaged; the picture is of a sound pod.
            singleplayer.getServer().runOnServer(server -> {
                PodEntity pod =
                        TestEntities.vehicle(server.getPlayerList().getPlayers().getFirst(), PodEntity.class);
                pod.setHull(pod.maxHull());
            });
            context.runOnClient(client -> client.options.setCameraType(CameraType.THIRD_PERSON_BACK));
            context.waitTicks(20);
            frame(context);

            try {
                context.getInput().holdKey(options -> options.keyUp);
                record(context, DRIVE_FRAMES);
                stall(context, LONG_STALL_TICKS);
                record(context, AFTER_STALL_FRAMES);
                stall(context, SHORT_STALL_TICKS);
                record(context, AFTER_STALL_FRAMES);
                // The key is released one tick into the stall: the server stops while the client hears nothing.
                context.getInput().releaseKey(options -> options.keyUp);
                stall(context, STOP_STALL_TICKS);
                record(context, STOP_FRAMES);
            } finally {
                PacketJitter.stop();
                context.getInput().releaseKey(options -> options.keyUp);
            }
        }
    }

    private static void stall(ClientGameTestContext context, int ticks) {
        context.runOnClient(client -> PacketJitter.stall(ticks));
    }

    private void record(ClientGameTestContext context, int frames) {
        for (int i = 0; i < frames; i++) {
            context.waitTick();
            frame(context);
        }
    }
}
