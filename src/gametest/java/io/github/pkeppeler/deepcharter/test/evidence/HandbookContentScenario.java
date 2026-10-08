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
 * Evidence scenario "m2-handbook-content" for #81: the handbook is paged from the cover to Appendix A, built from the chapters the
 * server syncs. Chapters 1 to 4 are shown done and chapter 5 as the current one, so every page of the five chapters is in full.
 */
public class HandbookContentScenario extends EvidenceScenario {
	private static final int GUI_SCALE = 2;
	private static final int HOLD_FRAMES = 3;
	private static final int FLIP_FRAME_TICKS = 2;
	private static final int CHAPTERS_DONE = 4;

	@Override
	protected String name() {
		return "m2-handbook-content";
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

			context.setScreen(() -> new HandbookScreen(HandbookPages.of(chapters, done), id -> true, id -> { }, List.of()));
			context.waitForScreen(HandbookScreen.class);
			context.waitTicks(2);
			HandbookScreen screen = context.computeOnClient(client -> (HandbookScreen) client.gui.screen());
			List<HandbookPage> pages = screen.pages();
			for (int page = 0; page < pages.size(); page++) {
				int target = page;
				if (page > 0) {
					context.runOnClient(client -> screen.goTo(target));
					context.waitTicks(FLIP_FRAME_TICKS);
					frame(context);
				}
				context.waitTicks(HandbookScreenTuning.DEFAULT.flipTicks());
				for (int frame = 0; frame < HOLD_FRAMES; frame++) {
					frame(context);
				}
				screenshot(context, String.format("page-%02d-%s", page + 1, label(pages.get(page))));
			}
			context.setScreen(() -> null);
		}
	}

	private static String label(HandbookPage page) {
		return switch (page) {
			case HandbookPage.Cover cover -> "cover";
			case HandbookPage.Slip slip -> "issue-slip";
			case HandbookPage.Letter letter -> "founders-letter";
			case HandbookPage.Contents contents -> "contents-" + contents.part();
			case HandbookPage.ChapterText text -> "chapter-" + text.number() + "-text-" + text.part();
			case HandbookPage.Chapter chapter -> "chapter-" + chapter.number() + "-directives";
			case HandbookPage.Appendix appendix -> "end-of-chapters";
			case HandbookPage.Contract contract -> "appendix-a-" + contract.part();
		};
	}
}
