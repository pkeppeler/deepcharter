package io.github.pkeppeler.deepcharter.test;

import java.util.ArrayList;
import java.util.List;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Style;

import io.github.pkeppeler.deepcharter.client.charter.ClientCharter;
import io.github.pkeppeler.deepcharter.client.hangar.HangarScreen;
import io.github.pkeppeler.deepcharter.client.repair.RepairStationScreen;
import io.github.pkeppeler.deepcharter.client.theme.PanelLook;
import io.github.pkeppeler.deepcharter.client.ui.CrtDraw;
import io.github.pkeppeler.deepcharter.client.ui.CrtText;
import io.github.pkeppeler.deepcharter.test.support.ClientChecks;
import io.github.pkeppeler.deepcharter.test.support.ClientChecks.Box;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.TerminalPanelScene;
import io.github.pkeppeler.deepcharter.test.support.TerminalPanelScene.Contract;
import io.github.pkeppeler.deepcharter.test.support.TerminalPanelScene.Scene;
import io.github.pkeppeler.deepcharter.test.support.TerminalPanelScreens;

import static io.github.pkeppeler.deepcharter.test.support.ClientChecks.require;

/**
 * Client GameTest for the terminal panel (#246). With no pack the panel is on and the terminal font is Unscii. Every one of the seven terminal
 * screens at the default window (427 x 240) keeps each button and each line of text inside the panel's content rectangle, no button overlaps
 * another, and no label is wider than its button in the font that is drawn.
 */
public class TerminalPanelClientTest implements FabricClientGameTest {
	/** The height the tallest screen (the upgrade terminal, the repair station) needs for its content. */
	private static final int MIN_CONTENT_HEIGHT = 192;
	private static final String SAMPLE = "ORE PROCESSOR ONLINE 0123456789";

	@Override
	public void runTest(ClientGameTestContext context) {
		ClientTestLog.start(this);
		require(context.computeOnClient(client -> PanelLook.current().enabled()), "the panel is on with no pack");
		require(context.computeOnClient(client -> !CrtText.style().equals(Style.EMPTY)), "the terminal font is in force with no pack");
		// A font the game cannot load (a file name with a capital, a bad size) falls back to the missing-glyph font silently, so measure it.
		require(context.computeOnClient(client -> CrtText.width(client.font, SAMPLE) != client.font.width(SAMPLE)),
				"Unscii draws the sample text no wider or narrower than the game's own font: the font did not load");
		List<String> problems = new ArrayList<>();
		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			Scene scene = singleplayer.getServer().computeOnServer(TerminalPanelScene::setUp);
			ClientWait.until(context, "the charter's account", client -> ClientCharter.view().map(charter -> charter.balance() == TerminalPanelScene.ACCOUNT).orElse(false),
					client -> "charter " + ClientCharter.view());
			for (String screen : TerminalPanelScreens.SCENE_SCREENS) {
				Screen open = TerminalPanelScreens.open(context, screen, scene, null);
				context.computeOnClient(client -> check(open, screen, problems));
				TerminalPanelScreens.close(context);
			}
		}
		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			Contract contract = singleplayer.getServer().computeOnServer(TerminalPanelScene::setUpContract);
			for (String screen : TerminalPanelScreens.CONTRACT_SCREENS) {
				Screen open = TerminalPanelScreens.open(context, screen, null, contract);
				context.computeOnClient(client -> check(open, screen, problems));
				TerminalPanelScreens.close(context);
			}
		}
		require(problems.isEmpty(), "screens that overflow their panel:\n" + String.join("\n", problems));
	}

	/** Collects what leaves the content rectangle of the panel in force, or collides, on {@code screen}. Always returns null: it runs on the client thread. */
	private static Object check(Screen screen, String when, List<String> problems) {
		PanelLook.Insets content = PanelLook.current().content();
		Box rect = new Box(content.left(), content.top(), screen.width - content.left() - content.right(), screen.height - content.top() - content.bottom());
		if (rect.height() < MIN_CONTENT_HEIGHT) {
			problems.add(when + ": the content is " + rect.height() + " high, the screens need " + MIN_CONTENT_HEIGHT);
		}
		List<Button> buttons = ClientChecks.buttons(screen);
		boolean pips = CrtDraw.pipsFit(PanelLook.current(), Minecraft.getInstance().font, screen);
		for (Button button : buttons) {
			Box box = Box.of(button);
			if (box.x() < rect.x() || box.y() < rect.y() || box.right() > rect.right() || box.bottom() > rect.bottom()) {
				problems.add(when + ": " + ClientChecks.labelOf(button) + " at " + box + " leaves the content " + rect);
			}
			// The label stays 2 pixels clear of both edges of its button, where the panel starts it (the screen draws a pip on every button or on none).
			int width = CrtText.width(Minecraft.getInstance().font, button.getMessage().getString());
			int start = PanelLook.current().labelStart(button.getWidth(), width, pips);
			if (start < PanelLook.LABEL_MARGIN || start + width > button.getWidth() - PanelLook.LABEL_MARGIN) {
				problems.add(when + ": " + ClientChecks.labelOf(button) + " is " + width + " wide, starting " + start + " in a button " + button.getWidth() + " wide");
			}
			ClientChecks.widgetOverlaps(button, buttons).ifPresent(message -> problems.add(when + ": " + message));
		}
		for (String[] line : textLines(screen)) {
			int x = Integer.parseInt(line[1]);
			int y = Integer.parseInt(line[2]);
			int width = CrtText.width(Minecraft.getInstance().font, line[0]);
			Box box = new Box(x, y, width, Minecraft.getInstance().font.lineHeight);
			if (box.x() < rect.x() || box.y() < rect.y() || box.right() > rect.right() || box.bottom() > rect.bottom()) {
				problems.add(when + ": the line '" + line[0] + "' at " + box + " leaves the content " + rect);
			}
			ClientChecks.textUnderButton("line", x, y, line[0], buttons).ifPresent(message -> problems.add(when + ": " + message));
		}
		return null;
	}

	/** The lines of text a screen can name the place of: text, x and y. */
	private static List<String[]> textLines(Screen screen) {
		List<String[]> lines = new ArrayList<>();
		if (screen instanceof HangarScreen hangar) {
			for (HangarScreen.PriceLine line : hangar.priceLines()) {
				lines.add(new String[] {line.text(), String.valueOf(line.x()), String.valueOf(line.y())});
			}
			for (HangarScreen.PriceLine line : hangar.advanceLines()) {
				lines.add(new String[] {line.text(), String.valueOf(line.x()), String.valueOf(line.y())});
			}
		} else if (screen instanceof RepairStationScreen repair) {
			for (RepairStationScreen.TextLine line : repair.textLines()) {
				lines.add(new String[] {line.text(), String.valueOf(line.x()), String.valueOf(line.y())});
			}
			for (RepairStationScreen.TextLine line : repair.introLines()) {
				lines.add(new String[] {line.text(), String.valueOf(line.x()), String.valueOf(line.y())});
			}
		}
		return lines;
	}
}
