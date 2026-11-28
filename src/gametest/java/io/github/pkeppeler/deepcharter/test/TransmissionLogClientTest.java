package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.ClientChecks.require;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.charter.CharterId;
import io.github.pkeppeler.deepcharter.charter.Charters;
import io.github.pkeppeler.deepcharter.client.transmission.TransmissionLogKeys;
import io.github.pkeppeler.deepcharter.client.transmission.TransmissionLogScreen;
import io.github.pkeppeler.deepcharter.client.transmission.TransmissionOverlay;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.QuietTransmissions;
import io.github.pkeppeler.deepcharter.transmission.Transmission;
import io.github.pkeppeler.deepcharter.transmission.TransmissionCatalog;
import io.github.pkeppeler.deepcharter.transmission.Transmissions;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.locale.Language;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import org.jspecify.annotations.Nullable;

/**
 * Client GameTest for #1615: the comms log. Its key opens a screen of the transmissions the charter has sent the player, in order,
 * each row saying when it came, and none it has not been sent. The latest is chosen; its text reads as it came, a garbled one still
 * garbled. A row chooses another, and REPLAY closes the log and types the chosen one out again on the overlay.
 */
public class TransmissionLogClientTest implements FabricClientGameTest {
    private static final String CREW_NAME = "Comms Log Crew";

    @Override
    public void runTest(ClientGameTestContext context) {
        ClientTestLog.start(this);
        try (var _ = QuietTransmissions.shown();
                TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            ClientWait.until(
                    context, "the client in the world", client -> client.player != null && client.level != null);
            singleplayer.getServer().runOnServer(server -> {
                ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
                require(Charters.found(server, player.getUUID(), CREW_NAME).isEmpty(), "founding should succeed");
                CharterId charter = Charters.charterOfOrThrow(server, player.getUUID())
                        .orElseThrow()
                        .id();
                for (String fired : List.of("t07", "t08", "t10")) {
                    Transmissions.fire(server, charter, id(fired));
                }
            });

            context.getInput().pressKey(TransmissionLogKeys.OPEN);
            ClientWait.until(
                    context,
                    "the comms log open",
                    client -> client.gui.screen() instanceof TransmissionLogScreen screen
                            && screen.entries().size() >= 3);
            List<Identifier> listed = context.computeOnClient(client -> log(client.gui.screen()).entries().stream()
                    .map(Transmission::id)
                    .toList());
            require(
                    listed.subList(listed.size() - 3, listed.size()).equals(List.of(id("t07"), id("t08"), id("t10"))),
                    "the log should end with what was fired last, in order: " + listed);
            require(listed.contains(id("t01")), "the log should hold the founding's welcome, t01: " + listed);
            require(!listed.contains(id("t09")), "the log must not show t09, which was never sent: " + listed);
            require(
                    TransmissionLogScreen.when(TransmissionCatalog.require(id("t07")))
                            .equals("Layer 1, upper third"),
                    "t07's row should say it came in layer 1's upper third, says "
                            + TransmissionLogScreen.when(TransmissionCatalog.require(id("t07"))));

            // The latest is chosen, and a garbled text reads as garbled as it came.
            String garbled = context.computeOnClient(
                    client -> String.join(" ", log(client.gui.screen()).chosenLines()));
            require(
                    chosen(context).equals(Optional.of(id("t10"))),
                    "the latest transmission should be chosen, is " + chosen(context));
            require(
                    garbled.contains("▒▒▒") && garbled.contains("▌SIGNAL LOST"),
                    "t10 should read garbled and close with its signal lost, reads: " + garbled);
            context.takeScreenshot("transmission-log-t10");

            // The founding's own transmissions may come first, so the row is found by its place in the list.
            int row = listed.indexOf(id("t07")) + 1;
            context.clickScreenButton(String.format(Locale.ROOT, "%02d  %s", row, "Layer 1, upper third"));
            ClientWait.until(
                    context,
                    "t07 chosen",
                    client -> log(client.gui.screen())
                            .chosen()
                            .map(Transmission::id)
                            .equals(Optional.of(id("t07"))));
            String text = context.computeOnClient(
                    client -> String.join(" ", log(client.gui.screen()).chosenLines()));
            String opening = Language.getInstance()
                    .getOrDefault(TransmissionCatalog.require(id("t07")).bodyKey())
                    .substring(0, 20);
            require(text.startsWith(opening), "t07's text should read as it came, reads: " + text);
            context.takeScreenshot("transmission-log-t07");

            context.runOnClient(client -> TransmissionOverlay.reset());
            context.clickScreenButton("screen.deepcharter.transmission_log.replay");
            ClientWait.until(
                    context,
                    "t07 replayed on the overlay, the log closed",
                    client -> client.gui.screen() == null
                            && TransmissionOverlay.transmission()
                                    .map(Transmission::id)
                                    .equals(Optional.of(id("t07"))));
        }
    }

    private static Optional<Identifier> chosen(ClientGameTestContext context) {
        return context.computeOnClient(
                client -> log(client.gui.screen()).chosen().map(Transmission::id));
    }

    private static TransmissionLogScreen log(@Nullable Object screen) {
        if (screen instanceof TransmissionLogScreen log) {
            return log;
        }
        throw new AssertionError("the comms log should be open, the screen is " + screen);
    }

    private static Identifier id(String name) {
        return Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, name);
    }
}
