package io.github.pkeppeler.deepcharter.test.evidence;

import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.test.PodShellClientTest;
import io.github.pkeppeler.deepcharter.test.support.ClientSession;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.PacketJitter;
import io.github.pkeppeler.deepcharter.test.support.TestEntities;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.CameraType;

/**
 * Evidence scenario "pod-drive-smooth" (#961): the pod driving along the ground and climbing on its rotor, one frame for every client
 * tick, with the network made irregular ({@link PacketJitter}) the way a real connection is. A pod that stands still for a tick and
 * lunges the next shows as a stutter between frames; a smooth one does not.
 */
public class PodDriveSmoothScenario extends EvidenceScenario {
    /** Every third client tick holds the pod's moves. */
    private static final int JITTER_PERIOD = 3;

    private static final int DRIVE_FRAMES = 60;
    private static final int CLIMB_FRAMES = 40;

    @Override
    protected String name() {
        return "pod-drive-smooth";
    }

    @Override
    protected void run(ClientGameTestContext context) {
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            context.waitTicks(40);
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

            PacketJitter.start(JITTER_PERIOD);
            try {
                context.getInput().holdKey(options -> options.keyUp);
                record(context, DRIVE_FRAMES);
                context.getInput().holdKey(options -> options.keyJump);
                record(context, CLIMB_FRAMES);
            } finally {
                PacketJitter.stop();
                context.getInput().releaseKey(options -> options.keyJump);
                context.getInput().releaseKey(options -> options.keyUp);
            }
        }
    }

    private void record(ClientGameTestContext context, int frames) {
        for (int i = 0; i < frames; i++) {
            context.waitTick();
            frame(context);
        }
    }
}
