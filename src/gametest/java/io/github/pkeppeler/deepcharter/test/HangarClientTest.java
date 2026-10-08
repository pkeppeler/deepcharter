package io.github.pkeppeler.deepcharter.test;

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
import io.github.pkeppeler.deepcharter.terminal.TerminalView;

/**
 * Client GameTest for #222: the hangar console shows the price of every action inside the screen, at the GUI size of an
 * 854 by 480 window (about 427 by 240) and at 320 by 240, the smallest GUI.
 */
public class HangarClientTest implements FabricClientGameTest {
	private static final int WAIT_TICKS = 200;

	@Override
	public void runTest(ClientGameTestContext context) {
		ClientTestLog.start(this);
		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			context.waitFor(client -> client.player != null && client.level != null);
			TerminalView view = new TerminalView(BlockPos.ZERO, HangarTerminal.TYPE.id(), true, true, List.of(), Optional.empty());
			for (int[] size : new int[][] {{427, 240}, {320, 240}}) {
				context.setScreen(() -> new HangarScreen(view));
				HangarScreen screen = context.computeOnClient(client -> (HangarScreen) client.gui.screen());
				context.runOnClient(client -> screen.resize(size[0], size[1]));
				context.waitFor(client -> screen.typewriter().done(), WAIT_TICKS);
				String problem = context.computeOnClient(client -> problem(screen));
				check(problem.isEmpty(), "at " + size[0] + " by " + size[1] + ": " + problem);
			}
			context.setScreen(() -> null);
		}
	}

	/** Every button and price line lies inside the screen, a button holds its whole label, and no price line is under a button. */
	private static String problem(HangarScreen screen) {
		Font font = Minecraft.getInstance().font;
		List<Button> buttons = screen.children().stream().filter(Button.class::isInstance).map(Button.class::cast).toList();
		for (Button button : buttons) {
			String label = button.getMessage().getString();
			if (button.getX() < 0 || button.getY() < 0 || button.getRight() > screen.width || button.getBottom() > screen.height) {
				return "'" + label + "' leaves the " + screen.width + " by " + screen.height + " screen";
			}
			if (button.getY() < screen.headerBottom()) {
				return "'" + label + "' starts at " + button.getY() + ", above the header text that ends at " + screen.headerBottom();
			}
			int textWidth = font.width(label);
			if (textWidth > button.getWidth()) {
				return "'" + label + "' is " + textWidth + " wide in a button " + button.getWidth() + " wide";
			}
		}
		if (screen.priceLines().isEmpty()) {
			return "the screen shows no restore prices";
		}
		for (HangarScreen.PriceLine line : screen.priceLines()) {
			int right = line.x() + font.width(line.text());
			if (line.x() < 0 || line.y() < 0 || right > screen.width || line.y() + font.lineHeight > screen.height) {
				return "the price line '" + line.text() + "' leaves the " + screen.width + " by " + screen.height + " screen";
			}
			for (Button button : buttons) {
				if (line.x() < button.getRight() && button.getX() < right
						&& line.y() < button.getBottom() && button.getY() < line.y() + font.lineHeight) {
					return "the price line '" + line.text() + "' is under '" + button.getMessage().getString() + "'";
				}
			}
		}
		return "";
	}

	private static void check(boolean condition, String message) {
		if (!condition) {
			throw new AssertionError(message);
		}
	}
}
