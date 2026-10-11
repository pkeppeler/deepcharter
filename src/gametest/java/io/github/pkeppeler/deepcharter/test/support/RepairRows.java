package io.github.pkeppeler.deepcharter.test.support;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.github.pkeppeler.deepcharter.client.repair.RepairStationScreen;

/** Which rows of the repair station's list are shown, read from the scroll line the screen draws. */
public final class RepairRows {
	/** The scroll line starts "3-5 OF 9": the first and last row shown, and the count (lang key {@code screen.deepcharter.repair.scroll}). */
	private static final Pattern SCROLL = Pattern.compile("^(\\d+)-\\d+ OF \\d+");

	private RepairRows() {
	}

	/** The index of the first row shown: the scroll line's first number less one, or 0 when the list fits and the screen draws no scroll line. Client thread. */
	public static int firstRow(RepairStationScreen screen) {
		for (RepairStationScreen.TextLine line : screen.textLines()) {
			Matcher matcher = SCROLL.matcher(line.text());
			if (line.dim() && matcher.find()) {
				return Integer.parseInt(matcher.group(1)) - 1;
			}
		}
		return 0;
	}
}
