package io.github.pkeppeler.deepcharter.test.support;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.minecraft.network.chat.Component;

import io.github.pkeppeler.deepcharter.client.handbook.HandbookNote;
import io.github.pkeppeler.deepcharter.client.handbook.HandbookScreen;

/**
 * What the Handbook screen shows on the Notes tab, read from the text it draws ({@link DrawnText}) rather than from its fields. The screen
 * must be initialised. Client thread.
 */
public final class HandbookDrawn {
	private static final Pattern SHEET_COUNTER = Pattern.compile("(\\d+) / (\\d+)");

	private HandbookDrawn() {
	}

	private static List<String> texts(HandbookScreen screen) {
		return DrawnText.of(screen).stream().map(DrawnText.Drawn::text).toList();
	}

	/** The Notes tab shows its list (or its empty state), not a Note. */
	public static boolean showsNotesList(HandbookScreen screen) {
		return texts(screen).contains(Component.translatable("deepcharter.handbook.notes.heading").getString());
	}

	/** The Notes tab shows its empty state: no notes yet. */
	public static boolean showsNotesEmptyState(HandbookScreen screen) {
		return texts(screen).contains(Component.translatable("deepcharter.handbook.notes.empty").getString());
	}

	/** The first sheet of {@code note} is on screen: its label, "Note N07", which the list never draws alone. */
	public static boolean showsNote(HandbookScreen screen, HandbookNote note) {
		return texts(screen).contains(Component.translatable("deepcharter.handbook.notes.label", String.format("N%02d", note.number())).getString());
	}

	/** The sheet of the open Note on screen, from 0, read from the sheet counter, which only a Note of more than one sheet draws. */
	public static int noteSheet(HandbookScreen screen) {
		for (String text : texts(screen)) {
			Matcher counter = SHEET_COUNTER.matcher(text);
			if (counter.matches()) {
				return Integer.parseInt(counter.group(1)) - 1;
			}
		}
		throw new AssertionError("the screen draws no sheet counter, so the open Note has one sheet or none is open: " + texts(screen));
	}
}
