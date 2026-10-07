package io.github.pkeppeler.deepcharter.client.handbook;

import java.util.Collection;
import java.util.List;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import io.github.pkeppeler.deepcharter.handbook.Notes;

/**
 * The Notes the server last said this player's charter has found, in Note order. Empty when the player is on no charter. An id this
 * build has no text for is dropped. The handbook screen reads it. Read and written on the client thread.
 */
public final class ClientNotes {
	private static List<Identifier> found = List.of();

	private ClientNotes() {
	}

	public static List<Identifier> found() {
		return found;
	}

	static void set(Collection<Identifier> newFound) {
		found = newFound.stream().filter(Notes::exists).toList();
	}

	/** The Notes tab entries for {@link #found}. */
	public static List<HandbookNote> entries() {
		return found.stream().map(ClientNotes::entry).toList();
	}

	/** The entry for one Note, with its text from the language file. */
	public static HandbookNote entry(Identifier note) {
		return new HandbookNote(note, Notes.number(note), Component.translatable(Notes.titleKey(note)), Component.translatable(Notes.textKey(note)));
	}
}
