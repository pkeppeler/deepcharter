package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.ClientChecks.require;

import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.test.support.ClientLight;
import io.github.pkeppeler.deepcharter.test.support.ClientSession;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.PodReadout;
import io.github.pkeppeler.deepcharter.test.support.TestEntities;
import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/** Client GameTest: the client sees itself riding a pod. */
public class PodShellClientTest implements FabricClientGameTest {
    /** Hull value that differs from a new pod's, to prove that synced data reaches the client. */
    public static final float DAMAGED_HULL = 42f;

    /** Spawn a pod under the first player on the server and put the player in its seat. */
    public static void mountFirstPlayer(TestServerContext server) {
        server.runOnServer(minecraftServer -> {
            ServerPlayer player = minecraftServer.getPlayerList().getPlayers().getFirst();
            PodEntity pod = TestEntities.create(PodRegistry.POD, player.level());
            pod.setPos(player.position());
            pod.setHull(DAMAGED_HULL);
            player.level().addFreshEntity(pod);
            require(player.startRiding(pod), "the player could not mount the new pod");
        });
    }

    @Override
    public void runTest(ClientGameTestContext context) {
        ClientTestLog.start(this);
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            mountFirstPlayer(singleplayer.getServer());
            ClientWait.until(
                    context,
                    "the client riding a pod",
                    client -> client.player != null && client.player.getVehicle() instanceof PodEntity);

            float hull = context.computeOnClient(
                    client -> TestEntities.vehicle(ClientSession.player(client), PodEntity.class)
                            .hull());
            require(
                    hull == DAMAGED_HULL,
                    "The client should see the pod's synced hull " + DAMAGED_HULL + ", saw " + hull);
            List<Component> lines = context.computeOnClient(client ->
                    PodReadout.texts(client, TestEntities.vehicle(ClientSession.player(client), PodEntity.class)));
            require(lines.getFirst().getString().contains("42"), "The HUD should show hull 42 first, showed " + lines);
            boolean sameEntity = context.computeOnClient(client -> TestEntities.vehicle(ClientSession.player(client))
                    == ClientLight.clientLevel(client)
                            .getEntity(TestEntities.vehicle(ClientSession.player(client))
                                    .getId()));
            require(sameEntity, "The client's vehicle is not the entity in its level");

            singleplayer
                    .getServer()
                    .runOnServer(minecraftServer -> minecraftServer
                            .getPlayerList()
                            .getPlayers()
                            .getFirst()
                            .stopRiding());
            ClientWait.until(
                    context,
                    "the player out of the pod",
                    client -> ClientSession.player(client).getVehicle() == null);
        }
    }
}
