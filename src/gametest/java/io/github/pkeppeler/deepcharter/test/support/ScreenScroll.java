package io.github.pkeppeler.deepcharter.test.support;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.minecraft.client.gui.screens.Screen;

/** Scrolls a terminal screen's list the way a player does, with the mouse wheel, and reads where it stands from the narration. */
public final class ScreenScroll {
	/** The scroll hint in the narration reads "3-5 OF 9": the first and last row shown, and the count. */
	private static final Pattern HINT = Pattern.compile("(\\d+)-\\d+ OF \\d+");

	private ScreenScroll() {
	}

	/** The index of the first row shown, or 0 when the list fits and the narration has no hint. Client thread. */
	public static int first(Screen screen) {
		Matcher matcher = HINT.matcher(screen.getNarrationMessage().getString());
		return matcher.find() ? Integer.parseInt(matcher.group(1)) - 1 : 0;
	}

	/** Turns the wheel until the list shows from row {@code row}, or stops at the end it can reach. Client thread. */
	public static void scrollTo(Screen screen, int row) {
		for (int turns = 0; turns < 100; turns++) {
			int now = first(screen);
			if (now == row) {
				return;
			}
			screen.mouseScrolled(0, 0, 0, now < row ? -1 : 1);
			if (first(screen) == now) {
				return;
			}
		}
		throw new AssertionError("the list did not reach row " + row + " in 100 wheel turns");
	}
}
