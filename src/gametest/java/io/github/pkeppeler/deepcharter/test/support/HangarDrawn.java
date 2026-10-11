package io.github.pkeppeler.deepcharter.test.support;

import java.util.List;

import net.minecraft.client.gui.screens.Screen;

import io.github.pkeppeler.deepcharter.test.support.DrawnText.Drawn;

/**
 * The hangar terminal's price and advance lines as the screen draws them ({@link DrawnText}): the restore prices open with "RESTORE PRICES"
 * and the Company's advance with "THE COMPANY ADVANCES", both drawn last and in that order. The screen must be laid out. Client thread.
 */
public final class HangarDrawn {
	private static final String PRICES = "RESTORE PRICES";
	private static final String ADVANCE = "THE COMPANY ADVANCES";

	private HangarDrawn() {
	}

	/** The wrapped lines of the restore prices, top to bottom; empty when the screen draws none. */
	public static List<Drawn> priceLines(Screen screen) {
		List<Drawn> drawn = DrawnText.of(screen);
		int first = indexOfLineStarting(drawn, PRICES);
		if (first < 0) {
			return List.of();
		}
		int advance = indexOfLineStarting(drawn, ADVANCE);
		return drawn.subList(first, advance < 0 ? drawn.size() : advance);
	}

	/** The wrapped lines of the Company's advance, top to bottom; empty when the screen draws none. */
	public static List<Drawn> advanceLines(Screen screen) {
		List<Drawn> drawn = DrawnText.of(screen);
		int advance = indexOfLineStarting(drawn, ADVANCE);
		return advance < 0 ? List.of() : drawn.subList(advance, drawn.size());
	}

	private static int indexOfLineStarting(List<Drawn> drawn, String start) {
		for (int index = 0; index < drawn.size(); index++) {
			if (drawn.get(index).text().startsWith(start)) {
				return index;
			}
		}
		return -1;
	}
}
