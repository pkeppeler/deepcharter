package io.github.pkeppeler.deepcharter.test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.mojang.blaze3d.platform.InputConstants;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.core.BlockPos;

import io.github.pkeppeler.deepcharter.charter.Charters;
import io.github.pkeppeler.deepcharter.client.charter.ClientCharter;
import io.github.pkeppeler.deepcharter.client.repair.RepairStationScreen;
import io.github.pkeppeler.deepcharter.terminal.TerminalTypes;
import io.github.pkeppeler.deepcharter.terminal.TerminalView;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;

/**
 * Client GameTest for #268: the repair station fits the screen at the GUI size of an 854 by 480 window (about 427 by 240) and
 * at 320 by 240, the smallest GUI. The account is the longest the screen can show, and the hull line is both the one with a
 * pod and the longer one without. At every scroll position each button and text line, the typed-out intro included, is inside
 * the screen and clear of the others, and between them the positions show every row of the list.
 */
public class RepairStationFitClientTest implements FabricClientGameTest {
	private static final String LAST_ROW = "BUY MATTER TRANSMITTER $1390";
	private static final long LONGEST_DEPOSIT = 9_999_999_999_999L;
	private static final int FAR = 1000;

	@Override
	public void runTest(ClientGameTestContext context) {
		ClientTestLog.start(this);
		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			RepairStationClientTest.Scene scene = singleplayer.getServer().computeOnServer(RepairStationClientTest::setUp);
			singleplayer.getServer().runOnServer(server -> {
				if (Charters.deposit(server, scene.charter().id(), LONGEST_DEPOSIT).isPresent()) {
					throw new AssertionError("funding should succeed");
				}
			});
			ClientWait.until(context, "the account above the longest deposit", client -> ClientCharter.view().map(charter -> charter.balance() > LONGEST_DEPOSIT).orElse(false),
					client -> "charter " + ClientCharter.view());
			BlockPos farAway = scene.station().offset(FAR, 0, FAR);
			// Both hull lines: the pod parked at the station, and no pod.
			for (BlockPos at : List.of(scene.station(), farAway)) {
				TerminalView view = new TerminalView(at, TerminalTypes.REPAIR_STATION.id(), true, true, List.of(), Optional.empty());
				// A size and the fewest rows it must show: a smaller list is a layout that wastes the screen.
				for (int[] size : new int[][] {{427, 240, 4}, {320, 240, 3}}) {
					context.setScreen(() -> new RepairStationScreen(view));
					RepairStationScreen screen = context.computeOnClient(client -> (RepairStationScreen) client.gui.screen());
					context.runOnClient(client -> screen.resize(size[0], size[1]));
					String problem = context.computeOnClient(client -> problem(screen, size[2]));
					check(problem.isEmpty(), "at " + size[0] + " by " + size[1] + (at == farAway ? " without a pod" : " with a pod") + ": " + problem);
				}
			}
			context.setScreen(() -> null);
		}
	}

	private static String problem(RepairStationScreen screen, int fewestRows) {
		if (screen.visibleRows() < fewestRows) {
			return "the list shows " + screen.visibleRows() + " rows, fewer than " + fewestRows;
		}
		if (!screen.getNarrationMessage().getString().contains("OF " + screen.rowCount())) {
			return "the narration does not say which rows are shown: " + screen.getNarrationMessage().getString();
		}
		Set<String> seen = new HashSet<>();
		screen.scrollTo(0);
		for (int first = 0; first < screen.rowCount(); first++) {
			screen.scrollTo(first);
			String problem = stepProblem(screen, seen);
			if (!problem.isEmpty()) {
				return "scrolled to row " + screen.firstRow() + ": " + problem;
			}
		}
		if (!seen.contains(LAST_ROW)) {
			return "the list never shows '" + LAST_ROW + "'";
		}
		// The rows, and CLOSE.
		if (seen.size() != screen.rowCount() + 1) {
			return "the list shows " + (seen.size() - 1) + " rows of " + screen.rowCount();
		}
		return keyProblem(screen);
	}

	/** Down from the last shown row scrolls one row and focuses the row that came into view; Up from the first row goes back. */
	private static String keyProblem(RepairStationScreen screen) {
		screen.scrollTo(0);
		List<Button> buttons = buttons(screen);
		Button last = buttons.get(screen.visibleRows() - 1);
		screen.setFocused(last);
		screen.keyPressed(new KeyEvent(InputConstants.KEY_DOWN, 0, 0));
		if (screen.firstRow() != 1 || !(screen.getFocused() instanceof Button now) || now.getY() != last.getY()) {
			return "Down from the last row did not scroll one row and focus the row that came into view";
		}
		Button first = buttons(screen).getFirst();
		screen.setFocused(first);
		screen.keyPressed(new KeyEvent(InputConstants.KEY_TAB, 0, InputConstants.MOD_SHIFT));
		if (screen.firstRow() != 0 || !(screen.getFocused() instanceof Button back) || back.getY() != first.getY()) {
			return "Shift+Tab from the first row did not scroll back one row and focus it";
		}
		return "";
	}

	private static List<Button> buttons(RepairStationScreen screen) {
		return screen.children().stream().filter(Button.class::isInstance).map(Button.class::cast).toList();
	}

	/** Every button and text line lies inside the screen, a button holds its whole label, and nothing overlaps. */
	private static String stepProblem(RepairStationScreen screen, Set<String> seen) {
		Font font = Minecraft.getInstance().font;
		List<Button> buttons = buttons(screen);
		List<RepairStationScreen.TextLine> lines = new ArrayList<>(screen.introLines());
		lines.addAll(screen.textLines());
		for (Button button : buttons) {
			String label = button.getMessage().getString();
			seen.add(label);
			if (button.getX() < 0 || button.getY() < 0 || button.getRight() > screen.width || button.getBottom() > screen.height) {
				return "'" + label + "' leaves the " + screen.width + " by " + screen.height + " screen";
			}
			if (font.width(label) > button.getWidth()) {
				return "'" + label + "' is " + font.width(label) + " wide in a button " + button.getWidth() + " wide";
			}
			for (Button other : buttons) {
				if (other != button && overlap(button.getX(), button.getY(), button.getRight(), button.getBottom(),
						other.getX(), other.getY(), other.getRight(), other.getBottom())) {
					return "'" + label + "' overlaps '" + other.getMessage().getString() + "'";
				}
			}
		}
		for (RepairStationScreen.TextLine line : lines) {
			int right = line.x() + font.width(line.text());
			int bottom = line.y() + font.lineHeight;
			if (line.x() < 0 || line.y() < 0 || right > screen.width || bottom > screen.height) {
				return "the line '" + line.text() + "' leaves the " + screen.width + " by " + screen.height + " screen";
			}
			if (!line.dim() && bottom > screen.listTop()) {
				return "the line '" + line.text() + "' ends at " + bottom + ", past where the list starts at " + screen.listTop();
			}
			for (Button button : buttons) {
				if (overlap(line.x(), line.y(), right, bottom, button.getX(), button.getY(), button.getRight(), button.getBottom())) {
					return "the line '" + line.text() + "' is under '" + button.getMessage().getString() + "'";
				}
			}
			for (RepairStationScreen.TextLine other : lines) {
				if (other != line && overlap(line.x(), line.y(), right, bottom,
						other.x(), other.y(), other.x() + font.width(other.text()), other.y() + font.lineHeight)) {
					return "the line '" + line.text() + "' overlaps '" + other.text() + "'";
				}
			}
		}
		return "";
	}

	private static boolean overlap(int left, int top, int right, int bottom, int otherLeft, int otherTop, int otherRight, int otherBottom) {
		return left < otherRight && otherLeft < right && top < otherBottom && otherTop < bottom;
	}

	private static void check(boolean condition, String message) {
		if (!condition) {
			throw new AssertionError(message);
		}
	}
}
