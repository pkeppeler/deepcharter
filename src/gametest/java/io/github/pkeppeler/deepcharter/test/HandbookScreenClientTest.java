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
import io.github.pkeppeler.deepcharter.handbook.HandbookChapter;
import io.github.pkeppeler.deepcharter.handbook.HandbookItems;
import io.github.pkeppeler.deepcharter.handbook.HandbookReadPayload;
import io.github.pkeppeler.deepcharter.handbook.HandbookVisibility;
import io.github.pkeppeler.deepcharter.handbook.ReadMarks;

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
	private static final int FRONT_PAGES = 4;

	@Override
	public void runTest(ClientGameTestContext context) {
		nothingDoneShowsOneChapterAndPreviewsTheNext();
		completedChaptersStayFullAndTheRoadMovesOn();
		aPartlyDoneChapterIsStillTheCurrentOne();
		aLaterDirectiveDoneOutOfOrderSpoilsNothing();
		everythingDoneLeavesNothingClassified();
		pagesAreBoundInOrderWithVisibilityOnTheChapters();
		viewingOnlyFullChaptersMarksThemReadOnce(context);
		notesTabShowsAnEmptyState(context);
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
		check(pages.size() == FRONT_PAGES + CHAPTERS + 1, "the pages are the front pages, the chapters and the end page, got " + pages.size());
		check(pages.get(0) instanceof HandbookPage.Cover, "the cover comes first");
		check(pages.get(1) instanceof HandbookPage.Slip, "the issue slip comes second");
		check(pages.get(2) instanceof HandbookPage.Letter, "the Founder's letter comes third");
		check(pages.get(3) instanceof HandbookPage.Contents, "the contents come fourth");
		check(pages.getLast() instanceof HandbookPage.Appendix, "the end page comes last");
		HandbookVisibility[] expected = {HandbookVisibility.FULL, HandbookVisibility.FULL, HandbookVisibility.PREVIEW, HandbookVisibility.CLASSIFIED};
		for (int chapter = 1; chapter <= CHAPTERS; chapter++) {
			HandbookPage.Chapter page = (HandbookPage.Chapter) pages.get(FRONT_PAGES + chapter - 1);
			check(page.number() == chapter && page.id().equals(chapterId(chapter)), "chapter " + chapter + " is in handbook order");
			check(page.visibility() == expected[chapter - 1], "chapter " + chapter + " should be " + expected[chapter - 1] + ", was " + page.visibility());
		}
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
			context.waitFor(client -> handbookSlot(client.player.getInventory()) >= 0);
			context.runOnClient(client -> client.player.getInventory().setSelectedSlot(handbookSlot(client.player.getInventory())));

			context.getInput().pressKey(HandbookKeys.OPEN);
			context.waitForScreen(HandbookScreen.class);
			context.runOnClient(client -> client.gui.screen().keyPressed(new KeyEvent(InputConstants.KEY_H, 0, 0)));
			context.waitFor(client -> client.gui.screen() == null);

			context.getInput().pressKey(options -> options.keyUse);
			context.waitForScreen(HandbookScreen.class);
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
			context.waitTicks(HandbookScreenTuning.DEFAULT.flipTicks() + 2);
			context.takeScreenshot("handbook-chapter");
			context.waitFor(client -> ClientReadMarks.isRead(SAMPLE_CHAPTER));
			check(singleplayer.getServer().computeOnServer(server -> ReadMarks.isRead(server.getPlayerList().getPlayers().getFirst(), SAMPLE_CHAPTER)),
					"the server holds the read mark");
			check(singleplayer.getServer().computeOnServer(server -> !ReadMarks.isRead(server.getPlayerList().getPlayers().getFirst(), NO_SUCH_CHAPTER)),
					"only the viewed chapter is marked");

			singleplayer.getServer().runOnServer(server -> {
				var player = server.getPlayerList().getPlayers().getFirst();
				check(!HandbookReadPayload.handle(server, player, NO_SUCH_CHAPTER), "the server refuses a chapter that does not exist");
				check(!ReadMarks.isRead(player, NO_SUCH_CHAPTER), "a chapter that does not exist is not marked");
				check(HandbookReadPayload.viewable(server, player.getUUID(), SAMPLE_CHAPTER), "the first chapter is viewable");
				check(!HandbookReadPayload.viewable(server, player.getUUID(), SAMPLE_SLEEP), "a directive id is not a chapter");
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

	private static void check(boolean condition, String message) {
		if (!condition) {
			throw new AssertionError(message);
		}
	}
}
