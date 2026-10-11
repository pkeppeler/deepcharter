package io.github.pkeppeler.deepcharter.test.support;

import java.util.List;
import java.util.stream.IntStream;

import net.minecraft.resources.Identifier;

import io.github.pkeppeler.deepcharter.handbook.Notes;

/** The Notes that have text, found the way a Note block finds one: every number it can name, kept if {@link Notes#exists}. */
public final class ShippedNotes {
	private ShippedNotes() {
	}

	public static List<Identifier> ids() {
		return IntStream.rangeClosed(1, Notes.MAX_NUMBER).mapToObj(Notes::id).filter(Notes::exists).toList();
	}
}
