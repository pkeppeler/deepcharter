package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.ClientChecks.require;
import static io.github.pkeppeler.deepcharter.test.support.ClientChecks.requireNone;

import io.github.pkeppeler.deepcharter.client.charter.ClientCharter;
import io.github.pkeppeler.deepcharter.client.upgrade.UpgradeScreen;
import io.github.pkeppeler.deepcharter.terminal.TerminalOpenPayload;
import io.github.pkeppeler.deepcharter.test.support.ClientChecks;
import io.github.pkeppeler.deepcharter.test.support.ClientSession;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.ScreenScroll;
import io.github.pkeppeler.deepcharter.test.support.UpgradeShown;
import io.github.pkeppeler.deepcharter.upgrade.ComponentTrack;
import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

/**
 * Client GameTest for the layout of the upgrade screen at the default window (854 x 480, GUI scale 2, so 427 x 240): with a button for every
 * track that fits (the list scrolls, and each track in {@code ComponentTrack} is scrolled to in turn), and whichever track is chosen, every button lies inside the screen, none overlaps another, and the Close button is among them.
 *
 * <p>Kept beside {@code ScreenLayoutClientTest} (#381), which checks the same fit for every screen, because this one also holds the screen to an 8 pixel
 * bottom margin and counts one button per track.
 */
public class UpgradeScreenLayoutClientTest implements FabricClientGameTest {
    private static final int WIDTH = 427;
    private static final int HEIGHT = 240;

    @Override
    public void runTest(ClientGameTestContext context) {
        ClientTestLog.start(this);
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            UpgradeTerminalClientTest.Scene scene =
                    singleplayer.getServer().computeOnServer(UpgradeTerminalClientTest::setUp);
            ClientWait.until(
                    context,
                    "the charter account",
                    client -> ClientCharter.view().isPresent(),
                    client -> "charter " + ClientCharter.view());
            context.runOnClient(client -> ClientPlayNetworking.send(new TerminalOpenPayload(scene.terminal())));
            ClientWait.screen(context, UpgradeScreen.class);
            UpgradeScreen screen = context.computeOnClient(client -> ClientSession.screen(client, UpgradeScreen.class));
            require(
                    screen.width == WIDTH && screen.height == HEIGHT,
                    "the test window is 854 x 480 at GUI scale 2, a screen of " + WIDTH + " x " + HEIGHT + ", got "
                            + screen.width + " x " + screen.height);
            ClientWait.until(
                    context,
                    "the track list",
                    client -> screen.upgrade().flatMap(view -> view.pod()).isPresent()
                            && ClientChecks.buttons(screen).size() > screen.visibleTracks());

            for (ComponentTrack track : ComponentTrack.values()) {
                context.runOnClient(client -> UpgradeShown.reveal(screen, track));
                String name = context.computeOnClient(
                        client -> Component.translatable("deepcharter.upgrade.track." + track.id())
                                .getString());
                context.clickScreenButton(name + "  T0");
                ClientWait.until(context, "the track " + track.id(), client -> UpgradeShown.isChosen(screen, track));
                context.runOnClient(client -> checkLayout(screen, track));
            }
            context.runOnClient(client -> checkScrolling(screen));
            context.setScreen(() -> null);
        }
    }

    /** The list scrolls to its ends with the wheel: the first track leaves the screen when the last one comes in, and the hint says which tracks are shown. */
    private static void checkScrolling(UpgradeScreen screen) {
        int total = ComponentTrack.values().length;
        require(
                screen.visibleTracks() < total || screen.visibleTracks() == total,
                "the screen shows at most all " + total + " tracks");
        if (screen.visibleTracks() == total) {
            return;
        }
        ScreenScroll.scrollTo(screen, 0);
        require(screen.mouseScrolled(0, 0, 0, -1), "the wheel scrolls the list");
        require(
                ClientChecks.buttons(screen).stream()
                        .noneMatch(button -> button.getMessage().getString().startsWith("DRILL  T")),
                "one wheel notch down moves the list one track: the first is gone");
        ScreenScroll.scrollTo(screen, total);
        String last = Component.translatable("deepcharter.upgrade.track." + ComponentTrack.values()[total - 1].id())
                        .getString()
                + "  T";
        String first =
                Component.translatable("deepcharter.upgrade.track." + ComponentTrack.values()[0].id())
                                .getString() + "  T";
        List<String> labels = ClientChecks.buttons(screen).stream()
                .map(button -> button.getMessage().getString())
                .toList();
        require(
                labels.stream().anyMatch(label -> label.contains(last)),
                "scrolled to the end, the last track is shown: " + labels);
        require(
                labels.stream().noneMatch(label -> label.contains(first)),
                "scrolled to the end, the first track is gone: " + labels);
        ScreenScroll.scrollTo(screen, 0);
        require(
                ClientChecks.buttons(screen).stream()
                        .anyMatch(button -> button.getMessage().getString().contains(first)),
                "scrolled back, the first track is shown again");
        require(
                screen.getNarrationMessage().getString().contains("OF " + total),
                "the narration tells which tracks are shown: "
                        + screen.getNarrationMessage().getString());
    }

    private static void checkLayout(UpgradeScreen screen, ComponentTrack track) {
        List<Button> buttons = ClientChecks.buttons(screen);
        int trackButtons = (int) buttons.stream()
                .filter(button -> button.getMessage().getString().matches("(> )?.*  T\\d+"))
                .count();
        require(
                trackButtons == screen.visibleTracks(),
                "a button for each of the " + screen.visibleTracks() + " tracks that fit, found " + trackButtons);
        require(
                buttons.stream()
                        .anyMatch(button -> button.getMessage()
                                .getString()
                                .equals(Component.translatable("screen.deepcharter.terminal.close")
                                        .getString())),
                "a Close button");
        for (Button button : buttons) {
            String when = "with " + track.id() + " chosen";
            requireNone(when, ClientChecks.buttonLeavesScreen(screen, button));
            requireNone(when, ClientChecks.widgetOverlaps(button, buttons));
            require(
                    ClientChecks.Box.of(button).bottom() <= HEIGHT - 8,
                    when + ": " + ClientChecks.labelOf(button) + " ends at "
                            + ClientChecks.Box.of(button).bottom() + ", under the 8 pixel margin of the " + HEIGHT
                            + " pixel screen");
        }
    }
}
