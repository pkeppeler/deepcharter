package io.github.pkeppeler.deepcharter.test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.Button;
import net.minecraft.core.BlockPos;

import io.github.pkeppeler.deepcharter.client.hangar.HangarScreen;
import io.github.pkeppeler.deepcharter.hangar.HangarTerminal;
import io.github.pkeppeler.deepcharter.hangar.HangarView;
import io.github.pkeppeler.deepcharter.terminal.TerminalFeature;
import io.github.pkeppeler.deepcharter.terminal.TerminalView;
import io.github.pkeppeler.deepcharter.test.support.ClientChecks;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;

import static io.github.pkeppeler.deepcharter.test.support.ClientChecks.check;

/**
 * Client GameTest for #222: the hangar console shows the price of every action inside the screen, at the GUI size of an
 * 854 by 480 window (about 427 by 240) and at 320 by 240, the smallest GUI.
 */
public class HangarClientTest implements FabricClientGameTest {
	@Override
	public void runTest(ClientGameTestContext context) {
		ClientTestLog.start(this);
		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			ClientWait.until(context, "the client in the world", client -> client.player != null && client.level != null);
			// The advance line shows while the charter has advance left, and is gone once it is used.
			for (int advanceLeft : new int[] {3, 0}) {
				TerminalView view = new TerminalView(BlockPos.ZERO, HangarTerminal.TYPE.id(), true, true, List.of(),
						Optional.<TerminalFeature>of(new HangarView(advanceLeft)));
				for (int[] size : new int[][] {{427, 240}, {320, 240}}) {
					context.setScreen(() -> new HangarScreen(view));
					HangarScreen screen = context.computeOnClient(client -> (HangarScreen) client.gui.screen());
					context.runOnClient(client -> screen.resize(size[0], size[1]));
					ClientWait.until(context, "the hangar screen finished typing", client -> screen.typewriter().done(),
							client -> "typewriter text '" + screen.typewriter().text() + "'");
					String problem = context.computeOnClient(client -> problem(screen, advanceLeft));
					check(problem.isEmpty(), "with " + advanceLeft + " advance left, at " + size[0] + " by " + size[1] + ": " + problem);
				}
			}
			context.setScreen(() -> null);
		}
	}

	/** Every button and price or advance line lies inside the screen, a button holds its whole label, and no price line is under a button. */
	private static String problem(HangarScreen screen, int advanceLeft) {
		Font font = Minecraft.getInstance().font;
		List<Button> buttons = ClientChecks.buttons(screen);
		for (Button button : buttons) {
			String problem = ClientChecks.buttonLeavesScreen(screen, button);
			if (problem.isEmpty() && button.getY() < screen.headerBottom()) {
				problem = "'" + button.getMessage().getString() + "' starts at " + button.getY() + ", above the header text that ends at " + screen.headerBottom();
			}
			if (problem.isEmpty()) {
				problem = ClientChecks.labelClipped(button);
			}
			if (!problem.isEmpty()) {
				return problem;
			}
		}
		if (screen.priceLines().isEmpty()) {
			return "the screen shows no restore prices";
		}
		if (advanceLeft > 0 == screen.advanceLines().isEmpty()) {
			return "with " + advanceLeft + " advance left the screen shows " + screen.advanceLines().size() + " advance lines";
		}
		if (screen.priceLines().stream().anyMatch(line -> line.text().contains("ADVANCES"))) {
			return "the advance runs on in the price lines " + screen.priceLines();
		}
		if (!screen.advanceLines().isEmpty()) {
			int lastPrice = screen.priceLines().stream().mapToInt(HangarScreen.PriceLine::y).max().orElseThrow();
			int firstAdvance = screen.advanceLines().stream().mapToInt(HangarScreen.PriceLine::y).min().orElseThrow();
			if (firstAdvance < lastPrice + font.lineHeight) {
				return "the advance starts at " + firstAdvance + ", not on a line of its own below the prices that end at " + (lastPrice + font.lineHeight);
			}
		}
		List<HangarScreen.PriceLine> drawn = new ArrayList<>(screen.priceLines());
		drawn.addAll(screen.advanceLines());
		for (HangarScreen.PriceLine line : drawn) {
			String problem = ClientChecks.textLeavesScreen(screen, "the price line", line.x(), line.y(), line.text());
			if (problem.isEmpty()) {
				problem = ClientChecks.textUnderButton("the price line", line.x(), line.y(), line.text(), buttons);
			}
			if (!problem.isEmpty()) {
				return problem;
			}
		}
		return "";
	}
}
