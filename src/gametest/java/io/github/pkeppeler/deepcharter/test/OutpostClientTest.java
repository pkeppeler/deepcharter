package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.ClientChecks.require;

import io.github.pkeppeler.deepcharter.client.charter.ClientCharter;
import io.github.pkeppeler.deepcharter.client.fuel.FuelPumpScreen;
import io.github.pkeppeler.deepcharter.client.market.OreProcessorScreen;
import io.github.pkeppeler.deepcharter.ore.OreRegistry;
import io.github.pkeppeler.deepcharter.ore.OreType;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodEvents;
import io.github.pkeppeler.deepcharter.pod.PodStats;
import io.github.pkeppeler.deepcharter.terminal.TerminalOpenPayload;
import io.github.pkeppeler.deepcharter.test.support.ClientSession;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.OutpostScene;
import io.github.pkeppeler.deepcharter.test.support.OutpostScene.Scene;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.server.level.ServerPlayer;

/**
 * Client GameTest for #320: the pump and the processor of an outpost in layer 1 show the prices that the server charges, $2 a litre and
 * 80 percent for ore, and a press charges and pays exactly that.
 */
public class OutpostClientTest implements FabricClientGameTest {
    /** Marks the pod that burns no fuel while the test waits, so the litres can be compared exactly. */
    private static final String FROZEN = "outpost-test-frozen";

    /** Ironium sells for $30 at the colony, $24 at the outpost. */
    private static final int IRONIUM_AT_OUTPOST = 24;

    static {
        PodEvents.IS_POWERED.register(pod -> !pod.entityTags().contains(FROZEN));
    }

    @Override
    public void runTest(ClientGameTestContext context) {
        ClientTestLog.start(this);
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            long[] tank = new long[1];
            Scene scene = singleplayer.getServer().computeOnServer(server -> {
                Scene staged = OutpostScene.stage(server);
                OutpostScene.build(server, staged);
                OutpostScene.standIn(server, staged, 0.5, 0.5, 180f, 0f);
                PodEntity pod = OutpostScene.parkPod(server, staged, 3.0, 0.5, 0f);
                pod.addTag(FROZEN);
                tank[0] = Math.round(PodStats.of(pod).tankLitres());
                ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
                for (int i = 0; i < 3; i++) {
                    player.getInventory().add(OreRegistry.stack(OreType.IRONIUM));
                }
                return staged;
            });
            ClientWait.until(
                    context,
                    "the charter view with its starting balance",
                    client -> ClientCharter.view().isPresent()
                            && ClientCharter.view().get().balance() == OutpostScene.START_BALANCE,
                    client -> "charter " + ClientCharter.view());

            context.runOnClient(client -> ClientPlayNetworking.send(new TerminalOpenPayload(scene.pump())));
            ClientWait.screen(context, FuelPumpScreen.class);
            FuelPumpScreen pump = context.computeOnClient(client -> ClientSession.screen(client, FuelPumpScreen.class));
            ClientWait.until(
                    context,
                    "the pump screen finished typing",
                    client -> pump.typewriter().done(),
                    client -> "typewriter text '" + pump.typewriter().text() + "'");
            require(
                    pump.typewriter().text().contains("$2 A LITRE"),
                    "the outpost pump names its price, got '"
                            + pump.typewriter().text() + "'");
            context.clickScreenButton("FILL UP");
            long afterFill = OutpostScene.START_BALANCE - 2 * tank[0];
            ClientWait.until(
                    context,
                    "the balance after a fill at $2 a litre",
                    client -> ClientCharter.view().get().balance() == afterFill,
                    client -> "charter " + ClientCharter.view() + ", expected " + afterFill);
            context.setScreen(() -> null);

            context.runOnClient(client -> ClientPlayNetworking.send(new TerminalOpenPayload(scene.processor())));
            ClientWait.screen(context, OreProcessorScreen.class);
            ClientWait.until(
                    context,
                    "the processor screen finished typing",
                    client -> ClientSession.screen(client, OreProcessorScreen.class)
                            .typewriter()
                            .done());
            context.clickScreenButton("SELL ALL CARRIED ORE");
            long afterSale = afterFill + 3L * IRONIUM_AT_OUTPOST;
            ClientWait.until(
                    context,
                    "the balance after 3 Ironium at the outpost's $24",
                    client -> ClientCharter.view().get().balance() == afterSale,
                    client -> "charter " + ClientCharter.view() + ", expected " + afterSale);
            context.setScreen(() -> null);
        }
    }
}
