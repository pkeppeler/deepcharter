package io.github.pkeppeler.deepcharter.test;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.Button;
import net.minecraft.core.BlockPos;

import io.github.pkeppeler.deepcharter.client.repair.RepairStationScreen;
import io.github.pkeppeler.deepcharter.terminal.TerminalTypes;
import io.github.pkeppeler.deepcharter.terminal.TerminalView;

/**
 * Client GameTest for #268: the repair station fits the screen at the GUI size of an 854 by 480 window (about 427 by 240) and
 * at 320 by 240, the smallest GUI. At every scroll position each button and text line is inside the screen and clear of the
 * others, and between them the positions show every row of the list.
 */
public class RepairStationFitClientTest implements FabricClientGameTest {
	private static final String LAST_ROW = "BUY MATTER TRANSMITTER $1500";

	@Override
	public void runTest(ClientGameTestContext context) {
		ClientTestLog.start(this);
		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			context.waitFor(client -> client.player != null && client.level != null);
			TerminalView view = new TerminalView(BlockPos.ZERO, TerminalTypes.REPAIR_STATION.id(), true, true, List.of(), Optional.empty());
			for (int[] size : new int[][] {{427, 240}, {320, 240}}) {
				context.setScreen(() -> new RepairStationScreen(view));
				RepairStationScreen screen = context.computeOnClient(client -> (RepairStationScreen) client.gui.screen());
				context.runOnClient(client -> screen.resize(size[0], size[1]));
				String problem = context.computeOnClient(client -> problem(screen));
				check(problem.isEmpty(), "at " + size[0] + " by " + size[1] + ": " + problem);
			}
			context.setScreen(() -> null);
		}
	}

	/** Walks the list from the top to the end, one row at a time, and checks the screen at each step. */
	private static String problem(RepairStationScreen screen) {
		Set<String> seen = new HashSet<>();
		screen.scrollTo(0);
		for (int first = 0; first < screen.rowCount(); first++) {
			screen.scrollTo(first);
			String problem = stepProblem(screen, seen);
			if (!problem.isEmpty()) {
				return "scrolled to row " + screen.firstRow() + ": " + problem;
			}
		}
		// The last row, CLOSE and the other rows are all different buttons.
		if (!seen.contains(LAST_ROW)) {
			return "the list never shows '" + LAST_ROW + "'";
		}
		// Every row but CLOSE.
		if (seen.size() != screen.rowCount() + 1) {
			return "the list shows " + (seen.size() - 1) + " rows of " + screen.rowCount();
		}
		return "";
	}

	/** Every button and text line lies inside the screen, a button holds its whole label, and nothing overlaps. */
	private static String stepProblem(RepairStationScreen screen, Set<String> seen) {
		Font font = Minecraft.getInstance().font;
		List<Button> buttons = screen.children().stream().filter(Button.class::isInstance).map(Button.class::cast).toList();
		List<RepairStationScreen.TextLine> lines = screen.textLines();
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
