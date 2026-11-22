package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.ClientChecks.require;

import io.github.pkeppeler.deepcharter.client.fuel.FuelPumpScreen;
import io.github.pkeppeler.deepcharter.client.outpost.IncidentReportScreen;
import io.github.pkeppeler.deepcharter.outpost.OutpostVisits;
import io.github.pkeppeler.deepcharter.outpost.OutpostVisits.Ledger;
import io.github.pkeppeler.deepcharter.outpost.OutpostVisits.Pending;
import io.github.pkeppeler.deepcharter.outpost.OutpostVisits.Report;
import io.github.pkeppeler.deepcharter.outpost.VisitCause;
import io.github.pkeppeler.deepcharter.terminal.TerminalOpenPayload;
import io.github.pkeppeler.deepcharter.test.support.ClientSession;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.OutpostScene;
import io.github.pkeppeler.deepcharter.test.support.OutpostScene.Scene;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Items;

/**
 * Client GameTest for #1004: the first member to open the outpost's pump after visits is shown the incident report over the pump's screen,
 * with what was taken and lost; FILED goes back to the pump's screen, and opening the pump again shows no second report.
 */
public class IncidentReportClientTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        ClientTestLog.start(this);
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            Scene scene = singleplayer.getServer().computeOnServer(server -> {
                Scene staged = OutpostScene.stage(server);
                OutpostScene.build(server, staged);
                OutpostScene.standIn(server, staged, 0.5, 0.5, 180f, 0f);
                Report report = new Report(
                        3,
                        Map.of(BuiltInRegistries.ITEM.getKey(Items.COAL), 37),
                        Map.of(BuiltInRegistries.ITEM.getKey(Items.BARREL), 1),
                        1,
                        Set.of(VisitCause.DARK));
                OutpostVisits.get(server)
                        .put(new Ledger(staged.charter(), 1, 0, Optional.empty(), Pending.NONE, report));
                return staged;
            });

            context.runOnClient(client -> ClientPlayNetworking.send(new TerminalOpenPayload(scene.pump())));
            ClientWait.screen(context, IncidentReportScreen.class);
            String said = context.computeOnClient(client -> ClientSession.screen(client, IncidentReportScreen.class)
                    .getNarrationMessage()
                    .getString());
            for (String line : new String[] {
                "INCIDENT REPORT: OUTPOST 1",
                "3 visits while unattended",
                "The hall was dark.",
                "37 x Coal",
                "1 x Barrel",
                "1 salvage heap"
            }) {
                require(
                        said.toLowerCase(java.util.Locale.ROOT).contains(line.toLowerCase(java.util.Locale.ROOT)),
                        "the report should say '" + line + "', it says '" + said + "'");
            }
            context.clickScreenButton("FILED");
            ClientWait.screen(context, FuelPumpScreen.class);

            // Read once: the pump opens again with no report on top of it.
            context.runOnClient(client -> client.gui.setScreen(null));
            context.runOnClient(client -> ClientPlayNetworking.send(new TerminalOpenPayload(scene.pump())));
            ClientWait.screen(context, FuelPumpScreen.class);
            context.waitTicks(10); // tick-wait: nothing more arrives, so there is no state to poll for
            boolean reportOpen = context.computeOnClient(client -> client.gui.screen() instanceof IncidentReportScreen);
            require(!reportOpen, "the report was read: it must not open a second time");
            context.setScreen(() -> null);
        }
    }
}
