package io.github.pkeppeler.deepcharter.client.handbook;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import net.minecraft.resources.Identifier;

import io.github.pkeppeler.deepcharter.handbook.HandbookChapter;
import io.github.pkeppeler.deepcharter.handbook.HandbookVisibility;

/** Builds the pages of the handbook. Pure: the same chapters and progress always give the same pages. */
public final class HandbookPages {
	/**
	 * Chapters listed on one contents page. Each entry takes one line, so this many fit on the smallest sheet: three entries, a
	 * heading and the buttons.
	 */
	public static final int CONTENTS_PER_PAGE = 3;

	private HandbookPages() {
	}

	/**
	 * The cover, the issue slip, the Founder's letter, the contents (one page for each {@link #CONTENTS_PER_PAGE} chapters), one
	 * page for each chapter, then the end page.
	 *
	 * @param chapters  the chapters by id, in handbook order
	 * @param completed the directives the player's charter has completed
	 */
	public static List<HandbookPage> of(Map<Identifier, HandbookChapter> chapters, Set<Identifier> completed) {
		List<HandbookVisibility> visibility = HandbookVisibility.of(
				chapters.values().stream().map(chapter -> chapter.directives().stream().map(HandbookChapter.Entry::id).toList()).toList(),
				completed);
		List<HandbookPage> pages = new ArrayList<>();
		pages.add(new HandbookPage.Cover());
		pages.add(new HandbookPage.Slip());
		pages.add(new HandbookPage.Letter());
		int parts = Math.max(1, (chapters.size() + CONTENTS_PER_PAGE - 1) / CONTENTS_PER_PAGE);
		for (int part = 1; part <= parts; part++) {
			int first = (part - 1) * CONTENTS_PER_PAGE + 1;
			pages.add(new HandbookPage.Contents(part, parts, first, Math.max(0, Math.min(CONTENTS_PER_PAGE, chapters.size() - first + 1))));
		}
		int index = 0;
		for (Map.Entry<Identifier, HandbookChapter> chapter : chapters.entrySet()) {
			Set<Identifier> done = chapter.getValue().directives().stream().map(HandbookChapter.Entry::id).filter(completed::contains)
					.collect(Collectors.toSet());
			pages.add(new HandbookPage.Chapter(index + 1, chapter.getKey(), chapter.getValue(), visibility.get(index), done));
			index++;
		}
		pages.add(new HandbookPage.Appendix());
		return List.copyOf(pages);
	}
}
