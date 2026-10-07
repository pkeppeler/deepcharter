package io.github.pkeppeler.deepcharter.client.handbook;

import java.util.Set;

import net.minecraft.resources.Identifier;

import io.github.pkeppeler.deepcharter.handbook.HandbookChapter;
import io.github.pkeppeler.deepcharter.handbook.HandbookVisibility;

/** One page of the handbook, in the order it is bound. The Notes tab is not a page: the screen draws it apart. */
public sealed interface HandbookPage {
	/** The cover. */
	record Cover() implements HandbookPage {
	}

	/** The issue slip: whom this copy was issued to. */
	record Slip() implements HandbookPage {
	}

	/** The Founder's letter. */
	record Letter() implements HandbookPage {
	}

	/** The table of contents. It lists every {@link Chapter} page that follows. */
	record Contents() implements HandbookPage {
	}

	/**
	 * A chapter. {@code number} counts from 1 in handbook order. {@code visibility} says how much of it the player may read: a
	 * {@link HandbookVisibility#CLASSIFIED} chapter still has its page, but the screen draws no title and no directive.
	 * {@code completed} holds the directives of this chapter that the player's charter has completed.
	 */
	record Chapter(int number, Identifier id, HandbookChapter chapter, HandbookVisibility visibility, Set<Identifier> completed) implements HandbookPage {
		public Chapter {
			completed = Set.copyOf(completed);
		}

		/** Whether every directive of the chapter is completed. */
		public boolean isComplete() {
			return chapter.directives().stream().allMatch(entry -> completed.contains(entry.id()));
		}
	}

	/** The end page: the end of the official chapters, and Appendix A with its redactions. */
	record Appendix() implements HandbookPage {
	}
}
