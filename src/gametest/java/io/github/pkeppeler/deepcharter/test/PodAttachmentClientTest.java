package io.github.pkeppeler.deepcharter.test;

import io.github.pkeppeler.deepcharter.attachment.Versioned;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.test.support.ClientSession;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.TestAttachments;
import io.github.pkeppeler.deepcharter.test.support.TestAttachments.Example;
import io.github.pkeppeler.deepcharter.test.support.TestEntities;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.world.entity.Entity;

/** Client GameTest: a versioned pod attachment synced with {@code AttachmentSyncPredicate.all()} reaches the client. */
public class PodAttachmentClientTest implements FabricClientGameTest {
    private static final int COUNTER = 99;

    @Override
    public void runTest(ClientGameTestContext context) {
        ClientTestLog.start(this);
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            PodShellClientTest.mountFirstPlayer(singleplayer.getServer());
            ClientWait.until(
                    context,
                    "the client riding a pod",
                    client -> client.player != null && client.player.getVehicle() instanceof PodEntity);
            singleplayer
                    .getServer()
                    .runOnServer(server -> Versioned.modifyOrThrow(
                            TestEntities.vehicle(
                                    server.getPlayerList().getPlayers().getFirst()),
                            TestAttachments.EXAMPLE,
                            example -> new Example(COUNTER)));

            ClientWait.until(
                    context,
                    "the vehicle's example attachment at the counter",
                    client -> ClientSession.player(client).getVehicle() != null
                            && Versioned.of(new Example(COUNTER))
                                    .equals(TestEntities.vehicle(ClientSession.player(client))
                                            .getAttached(TestAttachments.EXAMPLE)),
                    client -> {
                        Entity vehicle = ClientSession.player(client).getVehicle();
                        return "vehicle attachment "
                                + (vehicle == null ? "none" : vehicle.getAttached(TestAttachments.EXAMPLE));
                    });
        }
    }
}
