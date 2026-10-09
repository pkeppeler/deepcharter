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

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.core.BlockPos;

import io.github.pkeppeler.deepcharter.charter.Charters;
import io.github.pkeppeler.deepcharter.client.charter.ClientCharter;
import io.github.pkeppeler.deepcharter.client.repair.RepairStationScreen;
import io.github.pkeppeler.deepcharter.terminal.TerminalTypes;
import io.github.pkeppeler.deepcharter.terminal.TerminalView;
import io.github.pkeppeler.deepcharter.test.support.ClientChecks;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;

import static io.github.pkeppeler.deepcharter.test.support.ClientChecks.require;

/**
 * Client GameTest for #268: the repair station fits the screen at the GUI size of an 854 by 480 window (about 427 by 240) and
 * at 320 by 240, the smallest GUI. The account is the longest the screen can show, and the hull line is both the one with a
 * pod and the longer one without. At every scroll position each button and text line, the typed-out intro included, is inside
 * the screen and clear of the others, and between them the positions show every row of the list.
 */
public class RepairStationFitClientTest implements FabricClientGameTest {
	private static final String LAST_ROW = "BUY MATTER TRANSMITTER $1500";
	private static final long LONGEST_DEPOSIT = 9_999_999_999_999L;
	private static final int FAR = 1000;

	@Override
	public void runTest(ClientGameTestContext context) {
		ClientTestLog.start(this);
		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			RepairStationClientTest.Scene scene = singleplayer.getServer().computeOnServer(RepairStationClientTest::setUp);
			singleplayer.getServer().runOnServer(server -> {
				require(Charters.deposit(server, scene.charter().id(), LONGEST_DEPOSIT).isEmpty(), "funding should succeed");
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
					ClientChecks.requireNone("at " + size[0] + " by " + size[1] + (at == farAway ? " without a pod" : " with a pod"),
							context.computeOnClient(client -> problem(screen, size[2])));
				}
			}
			context.setScreen(() -> null);
		}
	}

	private static Optional<String> problem(RepairStationScreen screen, int fewestRows) {
		if (screen.visibleRows() < fewestRows) {
			return Optional.of("the list shows " + screen.visibleRows() + " rows, fewer than " + fewestRows);
		}
		if (!screen.getNarrationMessage().getString().contains("OF " + screen.rowCount())) {
			return Optional.of("the narration does not say which rows are shown: " + screen.getNarrationMessage().getString());
		}
		Set<String> seen = new HashSet<>();
		screen.scrollTo(0);
		for (int first = 0; first < screen.rowCount(); first++) {
			screen.scrollTo(first);
			Optional<String> problem = stepProblem(screen, seen);
			if (problem.isPresent()) {
				return Optional.of("scrolled to row " + screen.firstRow() + ": " + problem.get());
			}
		}
		if (!seen.contains(LAST_ROW)) {
			return Optional.of("the list never shows '" + LAST_ROW + "'");
		}
		// The rows, and CLOSE.
		if (seen.size() != screen.rowCount() + 1) {
			return Optional.of("the list shows " + (seen.size() - 1) + " rows of " + screen.rowCount());
		}
		return keyProblem(screen);
	}

	/** Down from the last shown row scrolls one row and focuses the row that came into view; Up from the first row goes back. */
	private static Optional<String> keyProblem(RepairStationScreen screen) {
		screen.scrollTo(0);
		List<Button> buttons = ClientChecks.buttons(screen);
		Button last = buttons.get(screen.visibleRows() - 1);
		screen.setFocused(last);
		screen.keyPressed(new KeyEvent(InputConstants.KEY_DOWN, 0, 0));
		if (screen.firstRow() != 1 || !(screen.getFocused() instanceof Button now) || now.getY() != last.getY()) {
			return Optional.of("Down from the last row did not scroll one row and focus the row that came into view");
		}
		Button first = ClientChecks.buttons(screen).getFirst();
		screen.setFocused(first);
		screen.keyPressed(new KeyEvent(InputConstants.KEY_TAB, 0, InputConstants.MOD_SHIFT));
		if (screen.firstRow() != 0 || !(screen.getFocused() instanceof Button back) || back.getY() != first.getY()) {
			return Optional.of("Shift+Tab from the first row did not scroll back one row and focus it");
		}
		return Optional.empty();
	}

	/** Every button and text line lies inside the screen, a button holds its whole label, and nothing overlaps. */
	private static Optional<String> stepProblem(RepairStationScreen screen, Set<String> seen) {
		List<Button> buttons = ClientChecks.buttons(screen);
		List<RepairStationScreen.TextLine> lines = new ArrayList<>(screen.introLines());
		lines.addAll(screen.textLines());
		for (Button button : buttons) {
			String label = button.getMessage().getString();
			seen.add(label);
			Optional<String> problem = ClientChecks.buttonLeavesScreen(screen, button)
					.or(() -> ClientChecks.labelClipped(button))
					.or(() -> ClientChecks.buttonOverlaps(button, buttons));
			if (problem.isPresent()) {
				return problem;
			}
		}
		for (RepairStationScreen.TextLine line : lines) {
			ClientChecks.Box box = ClientChecks.Box.ofText(line.x(), line.y(), line.text());
			Optional<String> problem = ClientChecks.textLeavesScreen(screen, "the line", line.x(), line.y(), line.text())
					.or(() -> !line.dim() && box.bottom() > screen.listTop()
							? Optional.of("the line '" + line.text() + "' ends at " + box.bottom() + ", past where the list starts at " + screen.listTop())
							: Optional.empty())
					.or(() -> ClientChecks.textUnderButton("the line", line.x(), line.y(), line.text(), buttons));
			if (problem.isPresent()) {
				return problem;
			}
			for (RepairStationScreen.TextLine other : lines) {
				if (other != line && ClientChecks.overlaps(box, ClientChecks.Box.ofText(other.x(), other.y(), other.text()))) {
					return Optional.of("the line '" + line.text() + "' overlaps '" + other.text() + "'");
				}
			}
		}
		return Optional.empty();
	}
}
