package io.github.pkeppeler.deepcharter.test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Style;

import io.github.pkeppeler.deepcharter.client.charter.ClientCharter;
import io.github.pkeppeler.deepcharter.client.hangar.HangarScreen;
import io.github.pkeppeler.deepcharter.client.market.OrderRowButton;
import io.github.pkeppeler.deepcharter.client.repair.RepairStationScreen;
import io.github.pkeppeler.deepcharter.client.theme.PanelLook;
import io.github.pkeppeler.deepcharter.client.ui.CrtDraw;
import io.github.pkeppeler.deepcharter.client.ui.CrtText;
import io.github.pkeppeler.deepcharter.client.ui.PanelButton;
import io.github.pkeppeler.deepcharter.client.upgrade.UpgradeScreen;
import io.github.pkeppeler.deepcharter.test.support.ClientChecks;
import io.github.pkeppeler.deepcharter.test.support.ClientChecks.Box;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.TerminalPanelScene;
import io.github.pkeppeler.deepcharter.test.support.TerminalPanelScene.Scene;
import io.github.pkeppeler.deepcharter.test.support.TerminalPanelScreens;

import static io.github.pkeppeler.deepcharter.test.support.ClientChecks.require;

/**
 * Client GameTest for the terminal panel (#246). With no pack the panel is on and the terminal font is Unscii. Every one of the seven terminal
 * screens at the default window (427 x 240) keeps each button and each line of text inside the panel's content rectangle, no button overlaps
 * another, and no label is wider than its button in the font that is drawn.
 */
public class TerminalPanelClientTest implements FabricClientGameTest {
	/** The smallest GUI the game allows: too small for the panel, so the screens are the plain CRT. */
	private static final int SMALL_GUI_WIDTH = 320;
	private static final int SMALL_GUI_HEIGHT = 240;
	/** The plain screens' row sizes, from before the panel: the repair station's buttons, and the gap between the upgrade terminal's track rows. */
	private static final int PLAIN_REPAIR_BUTTON_HEIGHT = 20;
	private static final int PLAIN_UPGRADE_ROW_GAP = 2;
	/** Face (button less its two edges and two rims) of 8 rows for 6 rows of capitals and a clear row each side. */
	private static final int MIN_BUTTON_HEIGHT = 12;
	private static final int MIN_TWO_LINE_BUTTON = 20;
	private static final String SAMPLE = "ORE PROCESSOR ONLINE 0123456789";

	@Override
	public void runTest(ClientGameTestContext context) {
		ClientTestLog.start(this);
		require(context.computeOnClient(client -> PanelLook.current().enabled()), "the panel is on with no pack");
		require(context.computeOnClient(client -> CrtText.style().equals(Style.EMPTY) && !PanelLook.drawn()),
				"with no terminal screen open (the HUD) the text is the game's own font and the panel is not drawn");
		List<String> problems = new ArrayList<>();
		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			Scene scene = singleplayer.getServer().computeOnServer(TerminalPanelScene::setUp);
			ClientWait.until(context, "the charter's account", client -> ClientCharter.view().map(charter -> charter.balance() == TerminalPanelScene.ACCOUNT).orElse(false),
					client -> "charter " + ClientCharter.view());
			for (String screen : TerminalPanelScreens.SCENE_SCREENS) {
				Screen open = TerminalPanelScreens.open(context, screen, scene, null);
				require(context.computeOnClient(client -> PanelLook.drawn() && !CrtText.style().equals(Style.EMPTY)), "the terminal font is in force on " + screen + " with no pack");
				// A font the game cannot load (a file name with a capital, a bad size) falls back to the missing-glyph font silently, so measure it.
				require(context.computeOnClient(client -> CrtText.width(client.font, SAMPLE) != client.font.width(SAMPLE)),
						"Unscii draws the sample text no wider or narrower than the game's own font: the font did not load");
				context.computeOnClient(client -> check(open, screen, problems, true));
				smallGui(context, open, screen, problems);
				TerminalPanelScreens.close(context);
			}
		}
		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			BlockPos contract = singleplayer.getServer().computeOnServer(TerminalPanelScene::setUpContract);
			Screen open = TerminalPanelScreens.open(context, TerminalPanelScreens.CONTRACT, null, contract);
			context.computeOnClient(client -> check(open, TerminalPanelScreens.CONTRACT, problems, true));
			smallGui(context, open, TerminalPanelScreens.CONTRACT, problems);
			TerminalPanelScreens.close(context);
		}
		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			BlockPos offline = singleplayer.getServer().computeOnServer(TerminalPanelScene::setUpOffline);
			Screen open = TerminalPanelScreens.open(context, TerminalPanelScreens.GENERIC, null, offline);
			context.computeOnClient(client -> check(open, TerminalPanelScreens.GENERIC, problems, true));
			smallGui(context, open, TerminalPanelScreens.GENERIC, problems);
			TerminalPanelScreens.close(context);
		}
		require(problems.isEmpty(), "screens that overflow their panel:\n" + String.join("\n", problems));
	}

	/**
	 * Resizes {@code screen} to the smallest GUI and checks that it is the plain CRT again: no panel, the game's font, the old row sizes, and the
	 * same overflow checks against the whole screen.
	 */
	private static void smallGui(ClientGameTestContext context, Screen screen, String name, List<String> problems) {
		String when = name + " at " + SMALL_GUI_WIDTH + " x " + SMALL_GUI_HEIGHT;
		context.runOnClient(client -> screen.resize(SMALL_GUI_WIDTH, SMALL_GUI_HEIGHT));
		context.computeOnClient(client -> {
			require(!PanelLook.drawn(), when + ": the panel is not drawn on a GUI too small for it");
			require(CrtText.style().equals(Style.EMPTY), when + ": the text is the game's own font");
			check(screen, when, problems, false);
			List<Button> buttons = ClientChecks.buttons(screen);
			if (screen instanceof RepairStationScreen) {
				for (Button button : buttons) {
					if (button.getHeight() != PLAIN_REPAIR_BUTTON_HEIGHT) {
						problems.add(when + ": " + ClientChecks.labelOf(button) + " is " + button.getHeight() + " high, the plain screen's buttons are " + PLAIN_REPAIR_BUTTON_HEIGHT);
					}
				}
			}
			if (screen instanceof UpgradeScreen) {
				int trackX = buttons.stream().mapToInt(Button::getX).min().orElseThrow();
				List<Button> tracks = buttons.stream().filter(button -> button.getX() == trackX).sorted(Comparator.comparingInt(Button::getY)).toList();
				for (int i = 1; i < tracks.size(); i++) {
					int gap = tracks.get(i).getY() - tracks.get(i - 1).getY() - tracks.get(i - 1).getHeight();
					if (gap != PLAIN_UPGRADE_ROW_GAP) {
						problems.add(when + ": " + ClientChecks.labelOf(tracks.get(i)) + " is " + gap + " below the row above, the plain screen's rows are " + PLAIN_UPGRADE_ROW_GAP + " apart");
					}
				}
			}
			return null;
		});
	}

	/**
	 * Collects what leaves the bounds in force, or collides, on {@code screen}. With {@code panel} the bounds are the panel's content rectangle and the
	 * labels keep the panel's margins and the buttons their minimum height; without it they are the whole screen, and the plain CRT
	 * has always let a long label run past its button. Always returns null: it runs on the client thread.
	 */
	private static Object check(Screen screen, String when, List<String> problems, boolean panel) {
		PanelLook.Insets content = panel ? PanelLook.current().content() : new PanelLook.Insets(0, 0, 0, 0);
		Box rect = new Box(content.left(), content.top(), screen.width - content.left() - content.right(), screen.height - content.top() - content.bottom());
		if (panel && rect.height() < PanelLook.MIN_CONTENT_HEIGHT) {
			problems.add(when + ": the content is " + rect.height() + " high, the screens need " + PanelLook.MIN_CONTENT_HEIGHT);
		}
		List<Button> buttons = ClientChecks.buttons(screen);
		boolean pips = panel && CrtDraw.pipsFit(PanelLook.current(), Minecraft.getInstance().font, screen);
		for (Button button : buttons) {
			Box box = Box.of(button);
			if (box.x() < rect.x() || box.y() < rect.y() || box.right() > rect.right() || box.bottom() > rect.bottom()) {
				problems.add(when + ": " + ClientChecks.labelOf(button) + " at " + box + " leaves the content " + rect);
			}
			// The label stays clear of both edges of its button, where the panel starts it (the screen draws a pip on every button or on none): 4 pixels, which is
			// the sprite's 3 pixel border and a column clear of it. A two line label counts by its wider line.
			int width = button instanceof PanelButton labelled ? labelled.panelLabelWidth(Minecraft.getInstance().font)
					: CrtText.width(Minecraft.getInstance().font, button.getMessage().getString());
			int start = panel ? PanelLook.current().labelStart(button.getWidth(), width, pips) : 0;
			if (panel && (start < PanelLook.LABEL_MARGIN || start + width > button.getWidth() - PanelLook.LABEL_MARGIN)) {
				problems.add(when + ": " + ClientChecks.labelOf(button) + " is " + width + " wide, starting " + start + " in a button " + button.getWidth() + " wide");
			}
			// A button's face is 4 pixels shorter than the button and a line of capitals is 6 high, so a one line label needs a 12 pixel button for a clear row
			// above and below it, and a two line label (the work orders, which sit 2 and 10 pixels down) a 20 pixel one.
			int needed = button instanceof OrderRowButton ? MIN_TWO_LINE_BUTTON : MIN_BUTTON_HEIGHT;
			if (panel && button.getHeight() < needed) {
				problems.add(when + ": " + ClientChecks.labelOf(button) + " is " + button.getHeight() + " high, its label needs " + needed + " for a clear row above and below");
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
