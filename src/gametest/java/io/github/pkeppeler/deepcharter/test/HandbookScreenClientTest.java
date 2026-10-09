package io.github.pkeppeler.deepcharter.test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.locale.Language;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.attachment.Versioned;
import io.github.pkeppeler.deepcharter.client.handbook.ClientReadMarks;
import io.github.pkeppeler.deepcharter.client.handbook.HandbookKeys;
import io.github.pkeppeler.deepcharter.client.handbook.HandbookPage;
import io.github.pkeppeler.deepcharter.client.handbook.HandbookPages;
import io.github.pkeppeler.deepcharter.client.handbook.HandbookScreen;
import io.github.pkeppeler.deepcharter.client.handbook.HandbookScreenTuning;
import io.github.pkeppeler.deepcharter.client.handbook.RedactionText;
import io.github.pkeppeler.deepcharter.handbook.HandbookChapter;
import io.github.pkeppeler.deepcharter.handbook.HandbookItems;
import io.github.pkeppeler.deepcharter.handbook.HandbookReadPayload;
import io.github.pkeppeler.deepcharter.handbook.HandbookVisibility;
import io.github.pkeppeler.deepcharter.handbook.ReadMarks;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;

import static io.github.pkeppeler.deepcharter.test.support.ClientChecks.check;

/**
 * Client GameTest for #66. The visibility model and the page order are pure logic and are checked first, with no game running.
 * Then a screen driven by hand, then the real game: the key and the item open the screen, viewing a chapter marks it read on the
 * server, the mark reaches the client again, and the server refuses a chapter the player may not read.
 */
public class HandbookScreenClientTest implements FabricClientGameTest {
	private static final Identifier SAMPLE_CHAPTER = Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "sample");
	private static final Identifier SAMPLE_SLEEP = Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "handbook/sample/sleep");
	private static final Identifier NO_SUCH_CHAPTER = Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "no_such_chapter");
	/** Chapters 1 to 4 of the synthetic handbook, each with two directives named {@code d<chapter>_<n>}. */
	private static final int CHAPTERS = 4;
	/** Cover, slip, letter, and two contents pages: four chapters at three entries a page. */
	private static final int FRONT_PAGES = 5;
	private static final int SPEC_CHAPTERS = 9;
	/** A window so small that the sheet is at its minimum size. */
	private static final int MIN_WINDOW_WIDTH = 200;
	private static final int MIN_WINDOW_HEIGHT = 100;
	private static final int LEFT_MOUSE = 1;

	@Override
	public void runTest(ClientGameTestContext context) {
		ClientTestLog.start(this);
		nothingDoneShowsOneChapterAndPreviewsTheNext();
		completedChaptersStayFullAndTheRoadMovesOn();
		aPartlyDoneChapterIsStillTheCurrentOne();
		aLaterDirectiveDoneOutOfOrderSpoilsNothing();
		everythingDoneLeavesNothingClassified();
		pagesAreBoundInOrderWithVisibilityOnTheChapters();
		textPagesComeBeforeTheirChapterAndOnlyForFullChapters();
		contentsPointAtTheFirstPageOfAChapter(context);
		viewingATextPageReportsItsChapterOnce(context);
		viewingOnlyFullChaptersMarksThemReadOnce(context);
		notesTabShowsAnEmptyState(context);
		theServerRefusesWhatTheCharterMayNotRead();
		redactionMarksSplitIntoWordsAndAnOddCountRedactsTheTail();
		marginKeysCarryTheNamespaceAndThePath();
		everyChapterOfNineHasAnEntryAndAClickTargetAtMinimumSize(context);
		readMarksRoundTripThroughTheSavedFormat();
		inTheRealGame(context);
	}

	private static Identifier directive(int chapter, int n) {
		return Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "handbook/test/d" + chapter + "_" + n);
	}

	private static List<List<Identifier>> directives() {
		List<List<Identifier>> chapters = new ArrayList<>();
		for (int chapter = 1; chapter <= CHAPTERS; chapter++) {
			chapters.add(List.of(directive(chapter, 1), directive(chapter, 2)));
		}
		return chapters;
	}

	private static Set<Identifier> done(int... chapters) {
		Set<Identifier> done = new java.util.HashSet<>();
		for (int chapter : chapters) {
			done.add(directive(chapter, 1));
			done.add(directive(chapter, 2));
		}
		return done;
	}

	private static void expectVisibility(Set<Identifier> completed, HandbookVisibility... expected) {
		List<HandbookVisibility> actual = HandbookVisibility.of(directives(), completed);
		check(actual.equals(List.of(expected)), "with " + completed.size() + " directives done expected " + List.of(expected) + ", got " + actual);
	}

	private static void nothingDoneShowsOneChapterAndPreviewsTheNext() {
		expectVisibility(Set.of(), HandbookVisibility.FULL, HandbookVisibility.PREVIEW, HandbookVisibility.CLASSIFIED, HandbookVisibility.CLASSIFIED);
	}

	private static void completedChaptersStayFullAndTheRoadMovesOn() {
		expectVisibility(done(1), HandbookVisibility.FULL, HandbookVisibility.FULL, HandbookVisibility.PREVIEW, HandbookVisibility.CLASSIFIED);
		expectVisibility(done(1, 2), HandbookVisibility.FULL, HandbookVisibility.FULL, HandbookVisibility.FULL, HandbookVisibility.PREVIEW);
	}

	private static void aPartlyDoneChapterIsStillTheCurrentOne() {
		expectVisibility(Set.of(directive(1, 1)), HandbookVisibility.FULL, HandbookVisibility.PREVIEW, HandbookVisibility.CLASSIFIED, HandbookVisibility.CLASSIFIED);
	}

	/** Chapter 3 is done but chapter 2 is not: the road still ends after chapter 3, since chapter 2 holds the way. */
	private static void aLaterDirectiveDoneOutOfOrderSpoilsNothing() {
		expectVisibility(done(1, 3), HandbookVisibility.FULL, HandbookVisibility.FULL, HandbookVisibility.PREVIEW, HandbookVisibility.CLASSIFIED);
		expectVisibility(done(4), HandbookVisibility.FULL, HandbookVisibility.PREVIEW, HandbookVisibility.CLASSIFIED, HandbookVisibility.CLASSIFIED);
		check(HandbookVisibility.of(List.of(), Set.of()).isEmpty(), "no chapters gives no visibility");
	}

	private static void everythingDoneLeavesNothingClassified() {
		expectVisibility(done(1, 2, 3, 4), HandbookVisibility.FULL, HandbookVisibility.FULL, HandbookVisibility.FULL, HandbookVisibility.FULL);
	}

	private static Map<Identifier, HandbookChapter> chapters() {
		Map<Identifier, HandbookChapter> chapters = new LinkedHashMap<>();
		for (int chapter = 1; chapter <= CHAPTERS; chapter++) {
			Identifier id = Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "test" + chapter);
			chapters.put(id, new HandbookChapter(chapter, Component.literal("Chapter " + chapter),
					List.of(new HandbookChapter.Entry(directive(chapter, 1), Component.literal("one")),
							new HandbookChapter.Entry(directive(chapter, 2), Component.literal("two")))));
		}
		return chapters;
	}

	private static Identifier chapterId(int chapter) {
		return Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "test" + chapter);
	}

	private static void pagesAreBoundInOrderWithVisibilityOnTheChapters() {
		List<HandbookPage> pages = HandbookPages.of(chapters(), done(1));
		long contract = pages.stream().filter(HandbookPage.Contract.class::isInstance).count();
		check(contract >= 1 && contract == keysFrom("deepcharter.handbook.appendix.page."),
				"Appendix A has a page for each appendix.page.N key, and at least one: " + contract + " pages, " + keysFrom("deepcharter.handbook.appendix.page.") + " keys");
		check(pages.size() == FRONT_PAGES + CHAPTERS + 1 + contract, "the pages are the front pages, the chapters, the end page and Appendix A, got " + pages.size());
		check(pages.get(0) instanceof HandbookPage.Cover, "the cover comes first");
		check(pages.get(1) instanceof HandbookPage.Slip, "the issue slip comes second");
		check(pages.get(2) instanceof HandbookPage.Letter, "the Founder's letter comes third");
		check(pages.get(3) instanceof HandbookPage.Contents && pages.get(4) instanceof HandbookPage.Contents, "the contents come next, over two pages");
		check(pages.get(FRONT_PAGES + CHAPTERS) instanceof HandbookPage.Appendix, "the end page comes after the chapters");
		check(pages.subList(FRONT_PAGES + CHAPTERS + 1, pages.size()).stream().allMatch(HandbookPage.Contract.class::isInstance), "Appendix A comes last");
		HandbookVisibility[] expected = {HandbookVisibility.FULL, HandbookVisibility.FULL, HandbookVisibility.PREVIEW, HandbookVisibility.CLASSIFIED};
		for (int chapter = 1; chapter <= CHAPTERS; chapter++) {
			HandbookPage.Chapter page = (HandbookPage.Chapter) pages.get(FRONT_PAGES + chapter - 1);
			check(page.number() == chapter && page.id().equals(chapterId(chapter)), "chapter " + chapter + " is in handbook order");
			check(page.visibility() == expected[chapter - 1], "chapter " + chapter + " should be " + expected[chapter - 1] + ", was " + page.visibility());
		}
	}

	/** How many keys {@code prefix + 1}, {@code prefix + 2}, ... in a row the language file has. */
	private static int keysFrom(String prefix) {
		int count = 0;
		while (Language.getInstance().has(prefix + (count + 1))) {
			count++;
		}
		return count;
	}

	private static final List<String> REAL_CHAPTERS = List.of("welcome", "back_online", "meet_the_mole", "fuel_is_life", "every_sale_counts");

	/** The five shipped chapter ids, so that their real text keys in the language file decide the pages. */
	private static Map<Identifier, HandbookChapter> realChapters() {
		Map<Identifier, HandbookChapter> chapters = new LinkedHashMap<>();
		for (int chapter = 1; chapter <= REAL_CHAPTERS.size(); chapter++) {
			chapters.put(Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, REAL_CHAPTERS.get(chapter - 1)), new HandbookChapter(chapter,
					Component.literal("Chapter " + chapter), List.of(new HandbookChapter.Entry(directive(chapter, 1), Component.literal("one")))));
		}
		return chapters;
	}

	private static Identifier real(String chapter) {
		return Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, chapter);
	}

	/** A page of a chapter as a short word: {@code text:<id>:<part>} or {@code chapter:<id>:<visibility>}; any other page is its class name. */
	private static String describe(HandbookPage page) {
		return switch (page) {
			case HandbookPage.ChapterText text -> "text:" + text.id().getPath() + ":" + text.part() + "/" + text.parts();
			case HandbookPage.Chapter chapter -> "chapter:" + chapter.id().getPath() + ":" + chapter.visibility();
			default -> page.getClass().getSimpleName();
		};
	}

	/**
	 * With chapter 1 done, chapters 1 and 2 are full and 3 is a preview. Each full chapter has a page for each text key it has,
	 * in order, before its own page; the preview and the classified chapters have none.
	 */
	private static void textPagesComeBeforeTheirChapterAndOnlyForFullChapters() {
		List<HandbookPage> pages = HandbookPages.of(realChapters(), done(1));
		check(keysFrom("deepcharter.handbook.chapter.deepcharter.back_online.text.") == 2, "back_online has two text keys in the language file");
		check(keysFrom("deepcharter.handbook.chapter.deepcharter.welcome.text.") == 1, "welcome has one text key in the language file");
		List<String> chapterPages = pages.stream().filter(page -> page instanceof HandbookPage.ChapterText || page instanceof HandbookPage.Chapter)
				.map(HandbookScreenClientTest::describe).toList();
		List<String> expected = List.of("text:welcome:1/1", "chapter:welcome:FULL", "text:back_online:1/2", "text:back_online:2/2", "chapter:back_online:FULL",
				"chapter:meet_the_mole:PREVIEW", "chapter:fuel_is_life:CLASSIFIED", "chapter:every_sale_counts:CLASSIFIED");
		check(chapterPages.equals(expected), "the chapter pages should be " + expected + ", got " + chapterPages);
		check(pages.stream().filter(HandbookPage.Contents.class::isInstance).count() == 2, "five chapters take two contents pages");
		List<HandbookPage> none = HandbookPages.of(realChapters(), Set.of());
		check(none.stream().filter(HandbookPage.ChapterText.class::isInstance).map(page -> ((HandbookPage.ChapterText) page).id())
				.allMatch(real("welcome")::equals), "with nothing done only the first chapter has text pages");
	}

	/** Every contents entry opens the first page of its chapter: the first text page when it has one, else its page of directives. */
	private static void contentsPointAtTheFirstPageOfAChapter(ClientGameTestContext context) {
		context.runOnClient(client -> {
			List<HandbookPage> pages = HandbookPages.of(realChapters(), done(1));
			HandbookScreen screen = new HandbookScreen(pages, id -> false, id -> { }, List.of());
			screen.init(MIN_WINDOW_WIDTH, MIN_WINDOW_HEIGHT);
			List<Integer> opened = new ArrayList<>();
			for (HandbookPage page : pages) {
				if (page instanceof HandbookPage.Contents contents) {
					screen.contentsEntries(contents).forEach(entry -> opened.add(entry.page()));
				}
			}
			List<Integer> expected = new ArrayList<>();
			for (int number = 1; number <= REAL_CHAPTERS.size(); number++) {
				for (int index = 0; index < pages.size(); index++) {
					int chapterNumber = switch (pages.get(index)) {
						case HandbookPage.ChapterText text -> text.number();
						case HandbookPage.Chapter chapter -> chapter.number();
						default -> -1;
					};
					if (chapterNumber == number) {
						expected.add(index);
						break;
					}
				}
			}
			check(opened.equals(expected), "the contents should open the pages " + expected + ", opened " + opened);
			check(pages.get(opened.get(1)) instanceof HandbookPage.ChapterText text && text.id().equals(real("back_online")) && text.part() == 1,
					"back_online opens on its first text page");
			check(pages.get(opened.get(2)) instanceof HandbookPage.Chapter, "a previewed chapter opens on its page of directives");
		});
	}

	/** A text page reports its chapter like the chapter's own page does: once, and not when the chapter is already read. */
	private static void viewingATextPageReportsItsChapterOnce(ClientGameTestContext context) {
		context.runOnClient(client -> {
			List<HandbookPage> pages = HandbookPages.of(realChapters(), done(1));
			int firstText = -1;
			int secondText = -1;
			for (int index = 0; index < pages.size(); index++) {
				if (pages.get(index) instanceof HandbookPage.ChapterText text && text.id().equals(real("back_online"))) {
					if (text.part() == 1) {
						firstText = index;
					} else {
						secondText = index;
					}
				}
			}
			check(firstText >= 0 && secondText == firstText + 1, "back_online has two text pages in a row");
			List<Identifier> viewed = new ArrayList<>();
			HandbookScreen screen = new HandbookScreen(pages, id -> false, viewed::add, List.of());
			screen.goTo(firstText);
			check(viewed.equals(List.of(real("back_online"))), "its first text page reports the chapter, got " + viewed);
			screen.goTo(secondText);
			screen.goTo(firstText + 2);
			check(viewed.equals(List.of(real("back_online"))), "the chapter is reported once for its text pages and its own page, got " + viewed);
			List<Identifier> again = new ArrayList<>();
			new HandbookScreen(pages, id -> true, again::add, List.of()).goTo(firstText);
			check(again.isEmpty(), "a text page of a chapter already read reports nothing");
		});
	}

	/** The screen is built by hand and never shown: flipping to a chapter reports it once, and only a full chapter is reported. */
	private static void viewingOnlyFullChaptersMarksThemReadOnce(ClientGameTestContext context) {
		context.runOnClient(client -> {
			List<Identifier> viewed = new ArrayList<>();
			List<HandbookPage> pages = HandbookPages.of(chapters(), done(1));
			HandbookScreen screen = new HandbookScreen(pages, id -> false, viewed::add, List.of());
			check(screen.page() == 0 && viewed.isEmpty(), "the screen opens on the cover and reports nothing");
			screen.goTo(FRONT_PAGES);
			screen.goTo(FRONT_PAGES + 1);
			screen.goTo(FRONT_PAGES);
			check(viewed.equals(List.of(chapterId(1), chapterId(2))), "each full chapter is reported once, got " + viewed);
			screen.goTo(FRONT_PAGES + 2);
			screen.goTo(FRONT_PAGES + 3);
			check(viewed.size() == 2, "a previewed or classified chapter is never reported, got " + viewed);
			screen.goTo(pages.size() + 10);
			check(screen.page() == pages.size() - 1, "flipping past the end stops at the end page, was " + screen.page());
			screen.goTo(-5);
			check(screen.page() == 0, "flipping before the start stops at the cover, was " + screen.page());

			List<Identifier> again = new ArrayList<>();
			HandbookScreen readAlready = new HandbookScreen(pages, id -> true, again::add, List.of());
			readAlready.goTo(FRONT_PAGES);
			check(again.isEmpty(), "a chapter already marked read is not reported again");
		});
	}

	private static void notesTabShowsAnEmptyState(ClientGameTestContext context) {
		context.runOnClient(client -> {
			HandbookScreen screen = new HandbookScreen(HandbookPages.of(chapters(), Set.of()), id -> false, id -> { }, List.of());
			check(!screen.onNotesTab(), "the screen opens on the handbook tab");
			screen.showNotes();
			check(screen.onNotesTab(), "the Notes tab opens");
			check(screen.notesEmpty(), "with no notes the Notes tab shows its empty state");
			screen.showHandbook();
			check(!screen.onNotesTab(), "the handbook tab opens again");
		});
	}

	/** Four chapters in registry order: the id list and the directive list must match by index. */
	private static void theServerRefusesWhatTheCharterMayNotRead() {
		List<Identifier> ids = List.of(chapterId(1), chapterId(2), chapterId(3), chapterId(4));
		Identifier unknown = chapterId(9);
		check(HandbookReadPayload.viewable(ids, directives(), done(1), chapterId(1)), "a completed chapter is viewable");
		check(HandbookReadPayload.viewable(ids, directives(), done(1), chapterId(2)), "the current chapter is viewable");
		check(!HandbookReadPayload.viewable(ids, directives(), done(1), chapterId(3)), "the next chapter is refused: only its title shows");
		check(!HandbookReadPayload.viewable(ids, directives(), done(1), chapterId(4)), "a classified chapter is refused");
		check(!HandbookReadPayload.viewable(ids, directives(), done(1), unknown), "an unknown chapter is refused");
		check(HandbookReadPayload.viewable(ids, directives(), Set.of(), chapterId(1)), "with nothing done the first chapter is viewable");
		check(!HandbookReadPayload.viewable(ids, directives(), Set.of(), chapterId(2)), "with nothing done the second chapter is refused");
		check(!HandbookReadPayload.viewable(ids, directives(), done(1, 2, 3), chapterId(9)), "an unknown chapter is refused when all are done");
		check(HandbookReadPayload.viewable(ids, directives(), done(1, 2, 3, 4), chapterId(4)), "every chapter is viewable when all are done");
		check(!HandbookReadPayload.viewable(List.of(), List.of(), Set.of(), chapterId(1)), "nothing is viewable with no chapters");
	}

	private static void redactionMarksSplitIntoWordsAndAnOddCountRedactsTheTail() {
		List<RedactionText.Token> tokens = RedactionText.parse("report ||unusual findings|| to ||Mgmt||.");
		check(tokens.equals(List.of(
				new RedactionText.Token("report", false, false),
				new RedactionText.Token("unusual", true, true),
				new RedactionText.Token("findings", true, true),
				new RedactionText.Token("to", false, true),
				new RedactionText.Token("Mgmt", true, true),
				new RedactionText.Token(".", false, false))), "a pair of marks redacts the words between them, got " + tokens);
		List<RedactionText.Token> odd = RedactionText.parse("open ||secret tail");
		check(odd.equals(List.of(
				new RedactionText.Token("open", false, false),
				new RedactionText.Token("secret", true, true),
				new RedactionText.Token("tail", true, true))), "an odd count of marks redacts the tail, got " + odd);
		check(RedactionText.parse("").isEmpty() && RedactionText.parse("||||").isEmpty(), "no words give no tokens");
		check(RedactionText.parse("  lead").equals(List.of(new RedactionText.Token("lead", false, false))), "a leading space is dropped");
	}

	private static void marginKeysCarryTheNamespaceAndThePath() {
		check(HandbookScreen.marginKey(SAMPLE_CHAPTER).equals("deepcharter.handbook.chapter.deepcharter.sample.margin"),
				"the margin key is " + HandbookScreen.marginKey(SAMPLE_CHAPTER));
		check(HandbookScreen.marginKey(Identifier.fromNamespaceAndPath("other", "a/b")).equals("deepcharter.handbook.chapter.other.a.b.margin"),
				"a slash in the path becomes a dot");
		check(!HandbookScreen.marginKey(Identifier.fromNamespaceAndPath("other", "sample")).equals(HandbookScreen.marginKey(SAMPLE_CHAPTER)),
				"two namespaces do not share a note");
	}

	private static Map<Identifier, HandbookChapter> spec(int count) {
		Map<Identifier, HandbookChapter> chapters = new LinkedHashMap<>();
		for (int chapter = 1; chapter <= count; chapter++) {
			chapters.put(chapterId(chapter), new HandbookChapter(chapter,
					Component.literal("PLACEHOLDER: a rather long chapter title number " + chapter),
					List.of(new HandbookChapter.Entry(directive(chapter, 1), Component.literal("one")))));
		}
		return chapters;
	}

	/** Nine chapters (docs/SPEC.md) on a sheet at its minimum size: no chapter may fall off the contents. */
	private static void everyChapterOfNineHasAnEntryAndAClickTargetAtMinimumSize(ClientGameTestContext context) {
		context.runOnClient(client -> {
			List<HandbookPage> pages = HandbookPages.of(spec(SPEC_CHAPTERS), done(1));
			HandbookScreen screen = new HandbookScreen(pages, id -> false, id -> { }, List.of());
			screen.init(MIN_WINDOW_WIDTH, MIN_WINDOW_HEIGHT);
			List<Integer> seen = new ArrayList<>();
			for (int index = 0; index < pages.size(); index++) {
				if (!(pages.get(index) instanceof HandbookPage.Contents contents)) {
					continue;
				}
				screen.goTo(index);
				for (int tick = 0; tick < HandbookScreenTuning.current().flipTicks(); tick++) {
					screen.tick();
				}
				List<HandbookScreen.ContentsEntry> entries = screen.contentsEntries(contents);
				check(entries.size() == contents.count(), "a contents page lists its " + contents.count() + " chapters, got " + entries.size());
				for (HandbookScreen.ContentsEntry entry : entries) {
					check(entry.bottom() <= screen.contentsBottom(), "entry for page " + entry.page() + " ends at " + entry.bottom()
							+ ", below the sheet's text area at " + screen.contentsBottom());
					seen.add(((HandbookPage.Chapter) pages.get(entry.page())).number());
					screen.goTo(index);
					double x = entry.left() + 2;
					double y = (entry.top() + entry.bottom()) / 2.0;
					MouseButtonEvent click = new MouseButtonEvent(x, y, new MouseButtonInfo(LEFT_MOUSE, 0));
					check(screen.mouseClicked(click, false), "the entry for page " + entry.page() + " is a click target");
					check(screen.page() == entry.page(), "clicking the entry goes to page " + entry.page() + ", was " + screen.page());
					for (int tick = 0; tick < HandbookScreenTuning.current().flipTicks(); tick++) {
						screen.tick();
					}
					screen.goTo(index);
					for (int tick = 0; tick < HandbookScreenTuning.current().flipTicks(); tick++) {
						screen.tick();
					}
				}
			}
			check(seen.equals(List.of(1, 2, 3, 4, 5, 6, 7, 8, 9)), "every chapter has exactly one entry, in order, got " + seen);
		});
	}

	private static void readMarksRoundTripThroughTheSavedFormat() {
		ReadMarks marks = new ReadMarks(Set.of(SAMPLE_CHAPTER, chapterId(2)));
		Tag saved = ReadMarks.CODEC.encodeStart(NbtOps.INSTANCE, Versioned.of(marks)).getOrThrow();
		Versioned<ReadMarks> value = ReadMarks.CODEC.parse(NbtOps.INSTANCE, saved).getOrThrow();
		check(value instanceof Versioned.Readable<ReadMarks> readable && readable.value().equals(marks),
				"read marks load back from the saved format");
	}

	private static void inTheRealGame(ClientGameTestContext context) {
		check(HandbookKeys.OPEN.getDefaultKey().getValue() == InputConstants.KEY_H, "the handbook key is H by default");
		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			ClientWait.until(context, "the handbook in the inventory", client -> handbookSlot(client.player.getInventory()) >= 0);
			context.runOnClient(client -> client.player.getInventory().setSelectedSlot(handbookSlot(client.player.getInventory())));

			context.getInput().pressKey(HandbookKeys.OPEN);
			ClientWait.screen(context, HandbookScreen.class);
			context.runOnClient(client -> client.gui.screen().keyPressed(new KeyEvent(InputConstants.KEY_H, 0, 0)));
			ClientWait.until(context, "the handbook screen closed", client -> client.gui.screen() == null);

			context.getInput().pressKey(options -> options.keyUse);
			ClientWait.screen(context, HandbookScreen.class);
			context.takeScreenshot("handbook-cover");
			HandbookScreen screen = context.computeOnClient(client -> (HandbookScreen) client.gui.screen());

			int chapterPage = context.computeOnClient(client -> {
				List<HandbookPage> pages = screen.pages();
				for (int index = 0; index < pages.size(); index++) {
					if (pages.get(index) instanceof HandbookPage.Chapter chapter && chapter.id().equals(SAMPLE_CHAPTER)) {
						return index;
					}
				}
				return -1;
			});
			check(chapterPage >= 0, "the sample chapter has a page");
			check(context.computeOnClient(client -> screen.pages().get(chapterPage) instanceof HandbookPage.Chapter chapter
					&& chapter.visibility() == HandbookVisibility.FULL), "the first chapter is full for a new player");
			check(!context.computeOnClient(client -> ClientReadMarks.isRead(SAMPLE_CHAPTER)), "the chapter is unread before it is viewed");

			context.runOnClient(client -> screen.goTo(chapterPage));
			context.waitTicks(HandbookScreenTuning.current().flipTicks() + 2);
			context.takeScreenshot("handbook-chapter");
			ClientWait.until(context, "the sample chapter marked read", client -> ClientReadMarks.isRead(SAMPLE_CHAPTER));
			check(singleplayer.getServer().computeOnServer(server -> ReadMarks.isRead(server.getPlayerList().getPlayers().getFirst(), SAMPLE_CHAPTER)),
					"the server holds the read mark");
			check(singleplayer.getServer().computeOnServer(server -> !ReadMarks.isRead(server.getPlayerList().getPlayers().getFirst(), NO_SUCH_CHAPTER)),
					"only the viewed chapter is marked");

			singleplayer.getServer().runOnServer(server -> {
				var player = server.getPlayerList().getPlayers().getFirst();
				check(!HandbookReadPayload.handle(server, player, NO_SUCH_CHAPTER), "the server refuses a chapter that does not exist");
				check(!ReadMarks.isRead(player, NO_SUCH_CHAPTER), "a chapter that does not exist is not marked");
				check(HandbookReadPayload.viewableFor(server, player.getUUID(), SAMPLE_CHAPTER), "the first chapter is viewable");
				check(!HandbookReadPayload.viewableFor(server, player.getUUID(), SAMPLE_SLEEP), "a directive id is not a chapter");
				check(HandbookReadPayload.handle(server, player, SAMPLE_CHAPTER), "viewing a chapter again is accepted and changes nothing");
			});

			// The viewed chapter stays marked for the owner.
			check(context.computeOnClient(client -> ClientReadMarks.read().equals(Set.of(SAMPLE_CHAPTER))), "the client sees exactly one chapter read");
			context.setScreen(() -> null);
		}
	}

	private static int handbookSlot(Inventory inventory) {
		for (int slot = 0; slot < Inventory.getSelectionSize(); slot++) {
			if (HandbookItems.isHandbook(inventory.getItem(slot))) {
				return slot;
			}
		}
		return -1;
	}
}
