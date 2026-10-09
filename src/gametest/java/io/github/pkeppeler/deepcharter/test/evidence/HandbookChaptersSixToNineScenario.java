package io.github.pkeppeler.deepcharter.test.evidence;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

import net.minecraft.resources.Identifier;

import io.github.pkeppeler.deepcharter.client.handbook.HandbookPage;
import io.github.pkeppeler.deepcharter.client.handbook.HandbookPages;
import io.github.pkeppeler.deepcharter.client.handbook.HandbookScreen;
import io.github.pkeppeler.deepcharter.client.handbook.HandbookScreenTuning;
import io.github.pkeppeler.deepcharter.handbook.HandbookChapter;
import io.github.pkeppeler.deepcharter.handbook.HandbookChapters;

/**
 * Evidence scenario "m2-handbook-chapters-6-9" for #84: the pages of chapters 6 to 9 and the end page that follows them. Chapters 1 to
 * 8 are shown done and chapter 9 as the current one with two of its four directives done, so every page of the four chapters is in
 * full and the tick marks show.
 */
public class HandbookChaptersSixToNineScenario extends EvidenceScenario {
	private static final int GUI_SCALE = 2;
	private static final int HOLD_FRAMES = 3;
	private static final int FLIP_FRAME_TICKS = 2;
	private static final int FIRST_SHOWN_CHAPTER = 6;
	private static final int CHAPTERS_DONE = 8;
	private static final int LAST_CHAPTER_DIRECTIVES_DONE = 2;

	@Override
	protected String name() {
		return "m2-handbook-chapters-6-9";
	}

	@Override
	protected void run(ClientGameTestContext context) {
		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			context.runOnClient(client -> client.options.guiScale().set(GUI_SCALE));
			Map<Identifier, HandbookChapter> chapters = context.computeOnClient(client -> {
				Map<Identifier, HandbookChapter> shipped = new LinkedHashMap<>();
				HandbookChapters.all(client.getConnection().registryAccess()).stream()
						.filter(chapter -> !chapter.key().identifier().getPath().equals("sample"))
						.forEach(chapter -> shipped.put(chapter.key().identifier(), chapter.value()));
				return shipped;
			});
			Set<Identifier> done = new HashSet<>();
			chapters.values().stream().limit(CHAPTERS_DONE).forEach(chapter -> chapter.directives().forEach(entry -> done.add(entry.id())));
			chapters.values().stream().skip(CHAPTERS_DONE).findFirst()
					.ifPresent(chapter -> chapter.directives().stream().limit(LAST_CHAPTER_DIRECTIVES_DONE).forEach(entry -> done.add(entry.id())));

			context.setScreen(() -> new HandbookScreen(HandbookPages.of(chapters, done), id -> true, id -> { }, List.of()));
			context.waitForScreen(HandbookScreen.class);
			context.waitTicks(2);
			HandbookScreen screen = context.computeOnClient(client -> (HandbookScreen) client.gui.screen());
			List<HandbookPage> pages = screen.pages();
			boolean first = true;
			for (int page = 0; page < pages.size(); page++) {
				String label = label(pages.get(page));
				if (label.isEmpty()) {
					continue;
				}
				int target = page;
				context.runOnClient(client -> screen.goTo(target));
				if (!first) {
					context.waitTicks(FLIP_FRAME_TICKS);
					frame(context);
				}
				first = false;
				context.waitTicks(HandbookScreenTuning.current().flipTicks());
				for (int frame = 0; frame < HOLD_FRAMES; frame++) {
					frame(context);
				}
				screenshot(context, String.format("page-%02d-%s", page + 1, label));
			}
			context.setScreen(() -> null);
		}
	}

	/** The name of a page from chapter 6 on, and of the end page; empty for every page before them. */
	private static String label(HandbookPage page) {
		return switch (page) {
			case HandbookPage.ChapterText text when text.number() >= FIRST_SHOWN_CHAPTER -> "chapter-" + text.number() + "-text-" + text.part();
			case HandbookPage.Chapter chapter when chapter.number() >= FIRST_SHOWN_CHAPTER -> "chapter-" + chapter.number() + "-directives";
			case HandbookPage.Appendix appendix -> "end-of-chapters";
			default -> "";
		};
	}
}
