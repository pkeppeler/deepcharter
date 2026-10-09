package io.github.pkeppeler.deepcharter.test;

import java.util.List;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

import io.github.pkeppeler.deepcharter.client.charter.ClientCharter;
import io.github.pkeppeler.deepcharter.client.upgrade.UpgradeScreen;
import io.github.pkeppeler.deepcharter.terminal.TerminalOpenPayload;
import io.github.pkeppeler.deepcharter.test.support.ClientChecks;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.upgrade.ComponentTrack;

import static io.github.pkeppeler.deepcharter.test.support.ClientChecks.require;
import static io.github.pkeppeler.deepcharter.test.support.ClientChecks.requireNone;

/**
 * Client GameTest for the layout of the upgrade screen at the default window (854 x 480, GUI scale 2, so 427 x 240): with a button for every
 * track in {@code ComponentTrack}, and whichever track is chosen, every button lies inside the screen, none overlaps another, and the Close button is among them.
 */
public class UpgradeScreenLayoutClientTest implements FabricClientGameTest {
	private static final int WIDTH = 427;
	private static final int HEIGHT = 240;

	@Override
	public void runTest(ClientGameTestContext context) {
		ClientTestLog.start(this);
		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			UpgradeTerminalClientTest.Scene scene = singleplayer.getServer().computeOnServer(UpgradeTerminalClientTest::setUp);
			ClientWait.until(context, "the charter account", client -> ClientCharter.view().isPresent(), client -> "charter " + ClientCharter.view());
			context.runOnClient(client -> ClientPlayNetworking.send(new TerminalOpenPayload(scene.terminal())));
			ClientWait.screen(context, UpgradeScreen.class);
			UpgradeScreen screen = context.computeOnClient(client -> (UpgradeScreen) client.gui.screen());
			require(screen.width == WIDTH && screen.height == HEIGHT, "the test window is 854 x 480 at GUI scale 2, a screen of " + WIDTH + " x " + HEIGHT + ", got " + screen.width + " x " + screen.height);
			ClientWait.until(context, "the track list", client -> screen.upgrade().flatMap(view -> view.pod()).isPresent() && ClientChecks.buttons(screen).size() > ComponentTrack.values().length);

			for (ComponentTrack track : ComponentTrack.values()) {
				if (screen.selected() != track) {
					String name = context.computeOnClient(client -> Component.translatable("deepcharter.upgrade.track." + track.id()).getString());
					context.clickScreenButton(name + "  T0");
				}
				ClientWait.until(context, "the track " + track.id() + " selected", client -> screen.selected() == track);
				context.runOnClient(client -> checkLayout(screen, track));
			}
			context.setScreen(() -> null);
		}
	}

	private static void checkLayout(UpgradeScreen screen, ComponentTrack track) {
		List<Button> buttons = ClientChecks.buttons(screen);
		int trackButtons = (int) buttons.stream().filter(button -> button.getMessage().getString().matches("(> )?.*  T\\d+")).count();
		require(trackButtons == ComponentTrack.values().length, "a button for each of the " + ComponentTrack.values().length + " tracks, found " + trackButtons);
		require(buttons.stream().anyMatch(button -> button.getMessage().getString().equals(Component.translatable("screen.deepcharter.terminal.close").getString())),
				"a Close button");
		for (Button button : buttons) {
			String when = "with " + track.id() + " chosen";
			requireNone(when, ClientChecks.buttonLeavesScreen(screen, button));
			requireNone(when, ClientChecks.buttonOverlaps(button, buttons));
			require(ClientChecks.Box.of(button).bottom() <= HEIGHT - 8, when + ": " + ClientChecks.labelOf(button) + " ends at " + ClientChecks.Box.of(button).bottom() + ", under the 8 pixel margin of the " + HEIGHT + " pixel screen");
		}
	}
}
