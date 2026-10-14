package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.ClientChecks.require;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.attachment.Versioned;
import io.github.pkeppeler.deepcharter.client.handbook.ClientNotes;
import io.github.pkeppeler.deepcharter.client.handbook.ClientReadMarks;
import io.github.pkeppeler.deepcharter.client.handbook.HandbookKeys;
import io.github.pkeppeler.deepcharter.client.handbook.HandbookNote;
import io.github.pkeppeler.deepcharter.client.handbook.HandbookPage;
import io.github.pkeppeler.deepcharter.client.handbook.HandbookPages;
import io.github.pkeppeler.deepcharter.client.handbook.HandbookScreen;
import io.github.pkeppeler.deepcharter.client.handbook.HandbookScreenTuning;
import io.github.pkeppeler.deepcharter.client.handbook.RedactionText;
import io.github.pkeppeler.deepcharter.handbook.HandbookChapter;
import io.github.pkeppeler.deepcharter.handbook.HandbookItems;
import io.github.pkeppeler.deepcharter.handbook.HandbookReadPayload;
import io.github.pkeppeler.deepcharter.handbook.HandbookVisibility;
import io.github.pkeppeler.deepcharter.handbook.Notes;
import io.github.pkeppeler.deepcharter.handbook.ReadMarks;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.DrawnText;
import io.github.pkeppeler.deepcharter.test.support.HandbookDrawn;
import io.github.pkeppeler.deepcharter.test.support.ShippedNotes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.locale.Language;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;

/**
 * Client GameTest for #66. The visibility model and the page order are pure logic and are checked first, with no game running.
 * Then a screen driven by hand, then the real game: the key and the item open the screen, viewing a chapter marks it read on the
 * server, the mark reaches the client again, and the server refuses a chapter the player may not read.
 */
public class HandbookScreenClientTest implements FabricClientGameTest {
    private static final Identifier SAMPLE_CHAPTER = Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "sample");
    private static final Identifier SAMPLE_SLEEP =
            Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "handbook/sample/sleep");
    private static final Identifier NO_SUCH_CHAPTER =
            Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "no_such_chapter");
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
        aLongNoteRunsOnToMoreSheets(context);
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
        require(
                actual.equals(List.of(expected)),
                "with " + completed.size() + " directives done expected " + List.of(expected) + ", got " + actual);
    }

    private static void nothingDoneShowsOneChapterAndPreviewsTheNext() {
        expectVisibility(
                Set.of(),
                HandbookVisibility.FULL,
                HandbookVisibility.PREVIEW,
                HandbookVisibility.CLASSIFIED,
                HandbookVisibility.CLASSIFIED);
    }

    private static void completedChaptersStayFullAndTheRoadMovesOn() {
        expectVisibility(
                done(1),
                HandbookVisibility.FULL,
                HandbookVisibility.FULL,
                HandbookVisibility.PREVIEW,
                HandbookVisibility.CLASSIFIED);
        expectVisibility(
                done(1, 2),
                HandbookVisibility.FULL,
                HandbookVisibility.FULL,
                HandbookVisibility.FULL,
                HandbookVisibility.PREVIEW);
    }

    private static void aPartlyDoneChapterIsStillTheCurrentOne() {
        expectVisibility(
                Set.of(directive(1, 1)),
                HandbookVisibility.FULL,
                HandbookVisibility.PREVIEW,
                HandbookVisibility.CLASSIFIED,
                HandbookVisibility.CLASSIFIED);
    }

    /** Chapter 3 is done but chapter 2 is not: the road still ends after chapter 3, since chapter 2 holds the way. */
    private static void aLaterDirectiveDoneOutOfOrderSpoilsNothing() {
        expectVisibility(
                done(1, 3),
                HandbookVisibility.FULL,
                HandbookVisibility.FULL,
                HandbookVisibility.PREVIEW,
                HandbookVisibility.CLASSIFIED);
        expectVisibility(
                done(4),
                HandbookVisibility.FULL,
                HandbookVisibility.PREVIEW,
                HandbookVisibility.CLASSIFIED,
                HandbookVisibility.CLASSIFIED);
        require(HandbookVisibility.of(List.of(), Set.of()).isEmpty(), "no chapters gives no visibility");
    }

    private static void everythingDoneLeavesNothingClassified() {
        expectVisibility(
                done(1, 2, 3, 4),
                HandbookVisibility.FULL,
                HandbookVisibility.FULL,
                HandbookVisibility.FULL,
                HandbookVisibility.FULL);
    }

    private static Map<Identifier, HandbookChapter> chapters() {
        Map<Identifier, HandbookChapter> chapters = new LinkedHashMap<>();
        for (int chapter = 1; chapter <= CHAPTERS; chapter++) {
            Identifier id = Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "test" + chapter);
            chapters.put(
                    id,
                    new HandbookChapter(
                            chapter,
                            Component.literal("Chapter " + chapter),
                            List.of(
                                    new HandbookChapter.Entry(directive(chapter, 1), Component.literal("one")),
                                    new HandbookChapter.Entry(directive(chapter, 2), Component.literal("two")))));
        }
        return chapters;
    }

    private static Identifier chapterId(int chapter) {
        return Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "test" + chapter);
    }

    private static void pagesAreBoundInOrderWithVisibilityOnTheChapters() {
        List<HandbookPage> pages = HandbookPages.of(chapters(), done(1));
        long contract =
                pages.stream().filter(HandbookPage.Contract.class::isInstance).count();
        require(
                contract >= 1 && contract == keysFrom("deepcharter.handbook.appendix.page."),
                "Appendix A has a page for each appendix.page.N key, and at least one: " + contract + " pages, "
                        + keysFrom("deepcharter.handbook.appendix.page.") + " keys");
        require(
                pages.size() == FRONT_PAGES + CHAPTERS + 1 + contract,
                "the pages are the front pages, the chapters, the end page and Appendix A, got " + pages.size());
        require(pages.get(0) instanceof HandbookPage.Cover, "the cover comes first");
        require(pages.get(1) instanceof HandbookPage.Slip, "the issue slip comes second");
        require(pages.get(2) instanceof HandbookPage.Letter, "the Founder's letter comes third");
        require(
                pages.get(3) instanceof HandbookPage.Contents && pages.get(4) instanceof HandbookPage.Contents,
                "the contents come next, over two pages");
        require(
                pages.get(FRONT_PAGES + CHAPTERS) instanceof HandbookPage.Appendix,
                "the end page comes after the chapters");
        require(
                pages.subList(FRONT_PAGES + CHAPTERS + 1, pages.size()).stream()
                        .allMatch(HandbookPage.Contract.class::isInstance),
                "Appendix A comes last");
        HandbookVisibility[] expected = {
            HandbookVisibility.FULL, HandbookVisibility.FULL, HandbookVisibility.PREVIEW, HandbookVisibility.CLASSIFIED
        };
        for (int chapter = 1; chapter <= CHAPTERS; chapter++) {
            HandbookPage.Chapter page = (HandbookPage.Chapter) pages.get(FRONT_PAGES + chapter - 1);
            require(
                    page.number() == chapter && page.id().equals(chapterId(chapter)),
                    "chapter " + chapter + " is in handbook order");
            require(
                    page.visibility() == expected[chapter - 1],
                    "chapter " + chapter + " should be " + expected[chapter - 1] + ", was " + page.visibility());
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

    private static final List<String> REAL_CHAPTERS =
            List.of("welcome", "back_online", "meet_the_mole", "fuel_is_life", "every_sale_counts");

    /** The five shipped chapter ids, so that their real text keys in the language file decide the pages. */
    private static Map<Identifier, HandbookChapter> realChapters() {
        Map<Identifier, HandbookChapter> chapters = new LinkedHashMap<>();
        for (int chapter = 1; chapter <= REAL_CHAPTERS.size(); chapter++) {
            chapters.put(
                    Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, REAL_CHAPTERS.get(chapter - 1)),
                    new HandbookChapter(
                            chapter,
                            Component.literal("Chapter " + chapter),
                            List.of(new HandbookChapter.Entry(directive(chapter, 1), Component.literal("one")))));
        }
        return chapters;
    }

    private static Identifier real(String chapter) {
        return Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, chapter);
    }

    /** A page of a chapter as a short word: {@code text:<id>:<part>} or {@code chapter:<id>:<visibility>}; any other page is its class name. */
    private static String describe(HandbookPage page) {
        return switch (page) {
            case HandbookPage.ChapterText text ->
                "text:" + text.id().getPath() + ":" + text.part() + "/" + text.parts();
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
        require(
                keysFrom("deepcharter.handbook.chapter.deepcharter.back_online.text.") == 2,
                "back_online has two text keys in the language file");
        require(
                keysFrom("deepcharter.handbook.chapter.deepcharter.welcome.text.") == 1,
                "welcome has one text key in the language file");
        List<String> chapterPages = pages.stream()
                .filter(page -> page instanceof HandbookPage.ChapterText || page instanceof HandbookPage.Chapter)
                .map(HandbookScreenClientTest::describe)
                .toList();
        List<String> expected = List.of(
                "text:welcome:1/1",
                "chapter:welcome:FULL",
                "text:back_online:1/2",
                "text:back_online:2/2",
                "chapter:back_online:FULL",
                "chapter:meet_the_mole:PREVIEW",
                "chapter:fuel_is_life:CLASSIFIED",
                "chapter:every_sale_counts:CLASSIFIED");
        require(chapterPages.equals(expected), "the chapter pages should be " + expected + ", got " + chapterPages);
        require(
                pages.stream().filter(HandbookPage.Contents.class::isInstance).count() == 2,
                "five chapters take two contents pages");
        List<HandbookPage> none = HandbookPages.of(realChapters(), Set.of());
        require(
                none.stream()
                        .filter(HandbookPage.ChapterText.class::isInstance)
                        .map(page -> ((HandbookPage.ChapterText) page).id())
                        .allMatch(real("welcome")::equals),
                "with nothing done only the first chapter has text pages");
    }

    /** Every contents entry opens the first page of its chapter: the first text page when it has one, else its page of directives. */
    private static void contentsPointAtTheFirstPageOfAChapter(ClientGameTestContext context) {
        context.runOnClient(client -> {
            List<HandbookPage> pages = HandbookPages.of(realChapters(), done(1));
            HandbookScreen screen = new HandbookScreen(pages, id -> false, id -> {}, List.of());
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
            require(opened.equals(expected), "the contents should open the pages " + expected + ", opened " + opened);
            require(
                    pages.get(opened.get(1)) instanceof HandbookPage.ChapterText text
                            && text.id().equals(real("back_online"))
                            && text.part() == 1,
                    "back_online opens on its first text page");
            require(
                    pages.get(opened.get(2)) instanceof HandbookPage.Chapter,
                    "a previewed chapter opens on its page of directives");
        });
    }

    /** A text page reports its chapter like the chapter's own page does: once, and not when the chapter is already read. */
    private static void viewingATextPageReportsItsChapterOnce(ClientGameTestContext context) {
        context.runOnClient(client -> {
            List<HandbookPage> pages = HandbookPages.of(realChapters(), done(1));
            int firstText = -1;
            int secondText = -1;
            for (int index = 0; index < pages.size(); index++) {
                if (pages.get(index) instanceof HandbookPage.ChapterText text
                        && text.id().equals(real("back_online"))) {
                    if (text.part() == 1) {
                        firstText = index;
                    } else {
                        secondText = index;
                    }
                }
            }
            require(firstText >= 0 && secondText == firstText + 1, "back_online has two text pages in a row");
            List<Identifier> viewed = new ArrayList<>();
            HandbookScreen screen = new HandbookScreen(pages, id -> false, viewed::add, List.of());
            screen.goTo(firstText);
            require(
                    viewed.equals(List.of(real("back_online"))),
                    "its first text page reports the chapter, got " + viewed);
            screen.goTo(secondText);
            screen.goTo(firstText + 2);
            require(
                    viewed.equals(List.of(real("back_online"))),
                    "the chapter is reported once for its text pages and its own page, got " + viewed);
            List<Identifier> again = new ArrayList<>();
            new HandbookScreen(pages, id -> true, again::add, List.of()).goTo(firstText);
            require(again.isEmpty(), "a text page of a chapter already read reports nothing");
        });
    }

    /** The screen is built by hand and never shown: flipping to a chapter reports it once, and only a full chapter is reported. */
    private static void viewingOnlyFullChaptersMarksThemReadOnce(ClientGameTestContext context) {
        context.runOnClient(client -> {
            List<Identifier> viewed = new ArrayList<>();
            List<HandbookPage> pages = HandbookPages.of(chapters(), done(1));
            HandbookScreen screen = new HandbookScreen(pages, id -> false, viewed::add, List.of());
            require(screen.page() == 0 && viewed.isEmpty(), "the screen opens on the cover and reports nothing");
            screen.goTo(FRONT_PAGES);
            screen.goTo(FRONT_PAGES + 1);
            screen.goTo(FRONT_PAGES);
            require(
                    viewed.equals(List.of(chapterId(1), chapterId(2))),
                    "each full chapter is reported once, got " + viewed);
            screen.goTo(FRONT_PAGES + 2);
            screen.goTo(FRONT_PAGES + 3);
            require(viewed.size() == 2, "a previewed or classified chapter is never reported, got " + viewed);
            screen.goTo(pages.size() + 10);
            require(
                    screen.page() == pages.size() - 1,
                    "flipping past the end stops at the end page, was " + screen.page());
            screen.goTo(-5);
            require(screen.page() == 0, "flipping before the start stops at the cover, was " + screen.page());

            List<Identifier> again = new ArrayList<>();
            HandbookScreen readAlready = new HandbookScreen(pages, id -> true, again::add, List.of());
            readAlready.goTo(FRONT_PAGES);
            require(again.isEmpty(), "a chapter already marked read is not reported again");
        });
    }

    private static void notesTabShowsAnEmptyState(ClientGameTestContext context) {
        context.runOnClient(client -> {
            HandbookScreen screen =
                    new HandbookScreen(HandbookPages.of(chapters(), Set.of()), id -> false, id -> {}, List.of());
            screen.init(DEFAULT_GUI_WIDTH, DEFAULT_GUI_HEIGHT);
            require(!HandbookDrawn.showsNotesList(screen), "the screen opens on the handbook tab");
            screen.showNotes();
            require(HandbookDrawn.showsNotesList(screen), "the Notes tab opens");
            require(HandbookDrawn.showsNotesEmptyState(screen), "with no notes the Notes tab shows its empty state");
            screen.showHandbook();
            require(!HandbookDrawn.showsNotesList(screen), "the handbook tab opens again");
        });
    }

    /** The longest real Note, Ines's log: it takes more than one sheet in a small window and at the default one. */
    private static final int LONG_NOTE = 10;
    /** A Note of one short line, which takes one sheet. */
    private static final int SHORT_NOTE = 6;

    private static final int SHORT_WINDOW_HEIGHT = 140;
    private static final int DEFAULT_GUI_WIDTH = 427;
    private static final int DEFAULT_GUI_HEIGHT = 240;

    /**
     * #411: a Note that does not fit one sheet runs on to the next. Next turns the sheets and wraps from the last to the first, the arrow keys
     * turn them without wrapping, opening a Note starts at its first sheet, the sheets together hold every line of the Note once, and a
     * resize that leaves fewer sheets does not leave the screen on one that is gone.
     */
    private static void aLongNoteRunsOnToMoreSheets(ClientGameTestContext context) {
        context.runOnClient(client -> {
            List<HandbookNote> notes =
                    ShippedNotes.ids().stream().map(ClientNotes::entry).toList();
            HandbookScreen screen =
                    new HandbookScreen(HandbookPages.of(chapters(), Set.of()), id -> true, id -> {}, notes);
            screen.init(DEFAULT_GUI_WIDTH, DEFAULT_GUI_HEIGHT);
            Identifier longNote = Notes.id(LONG_NOTE);
            Identifier shortNote = Notes.id(SHORT_NOTE);

            screen.openNote(shortNote);
            require(screen.noteSheets() == 1, "a one-line Note takes one sheet, got " + screen.noteSheets());
            require(!nextButton(screen).visible, "Next is hidden on a Note of one sheet");

            screen.openNote(longNote);
            int sheets = screen.noteSheets();
            require(sheets >= 2, "Ines's log takes more than one sheet, got " + sheets);
            require(
                    HandbookDrawn.noteSheet(screen) == 0 && nextButton(screen).visible,
                    "a long Note opens on its first sheet, with Next shown");
            for (int sheet = 1; sheet < sheets; sheet++) {
                press(screen, nextButton(screen));
                require(
                        HandbookDrawn.noteSheet(screen) == sheet,
                        "Next goes to sheet " + (sheet + 1) + ", was " + (HandbookDrawn.noteSheet(screen) + 1));
            }
            press(screen, nextButton(screen));
            require(
                    HandbookDrawn.noteSheet(screen) == 0,
                    "Next on the last sheet wraps to the first, was " + (HandbookDrawn.noteSheet(screen) + 1));

            require(
                    screen.keyPressed(new KeyEvent(InputConstants.KEY_RIGHT, 0, 0))
                            && HandbookDrawn.noteSheet(screen) == 1,
                    "the right arrow turns to the next sheet");
            require(
                    screen.keyPressed(new KeyEvent(InputConstants.KEY_LEFT, 0, 0))
                            && HandbookDrawn.noteSheet(screen) == 0,
                    "the left arrow turns back");
            screen.keyPressed(new KeyEvent(InputConstants.KEY_LEFT, 0, 0));
            require(HandbookDrawn.noteSheet(screen) == 0, "the left arrow stops at the first sheet");
            for (int i = 0; i < sheets + 2; i++) {
                screen.keyPressed(new KeyEvent(InputConstants.KEY_RIGHT, 0, 0));
            }
            require(
                    HandbookDrawn.noteSheet(screen) == sheets - 1,
                    "the right arrow stops at the last sheet, was " + (HandbookDrawn.noteSheet(screen) + 1));
            screen.turnNote(sheets + 5);
            require(HandbookDrawn.noteSheet(screen) == sheets - 1, "turnNote stops at the last sheet");
            screen.turnNote(-3);
            require(HandbookDrawn.noteSheet(screen) == 0, "turnNote stops at the first sheet");

            screen.turnNote(sheets - 1);
            screen.closeNote();
            screen.openNote(longNote);
            require(
                    HandbookDrawn.noteSheet(screen) == 0,
                    "opening a Note starts at its first sheet, was " + (HandbookDrawn.noteSheet(screen) + 1));

            HandbookNote note = notes.stream()
                    .filter(entry -> entry.id().equals(longNote))
                    .findFirst()
                    .orElseThrow();
            requireEveryLineOnce(screen, note);

            // A resize that leaves fewer sheets: start short (more sheets), go to the last, then grow.
            screen.init(DEFAULT_GUI_WIDTH, SHORT_WINDOW_HEIGHT);
            int narrow = screen.noteSheets();
            require(
                    narrow > sheets,
                    "a short window takes more sheets than the default one: " + narrow + " against " + sheets);
            screen.turnNote(narrow - 1);
            screen.init(DEFAULT_GUI_WIDTH, DEFAULT_GUI_HEIGHT);
            require(
                    screen.noteSheets() == sheets && HandbookDrawn.noteSheet(screen) == sheets - 1,
                    "after a resize to " + sheets
                            + " sheets the screen is on its last, not on a sheet that is gone: sheet "
                            + (HandbookDrawn.noteSheet(screen) + 1) + " of " + screen.noteSheets());
        });
    }

    /** The text each sheet draws, less the label, the title and the sheet counter, read in order, is the Note's text: no line twice, none lost. */
    private static void requireEveryLineOnce(HandbookScreen screen, HandbookNote note) {
        List<String> body = new ArrayList<>();
        for (int sheet = 0; sheet < screen.noteSheets(); sheet++) {
            screen.turnNote(sheet);
            for (DrawnText.Drawn drawn : DrawnText.of(screen)) {
                String text = drawn.text();
                boolean heading = text.equals(note.title().getString())
                        || text.contains(String.format("N%02d", note.number()))
                        || text.matches("\\d+ ?/ ?\\d+");
                if (!heading) {
                    body.add(text);
                }
            }
        }
        screen.turnNote(0);
        String expected = note.text().getString().replaceAll("\\s+", " ").trim();
        String drawn = String.join(" ", body).replaceAll("\\s+", " ").trim();
        require(
                drawn.equals(expected),
                "the sheets of Note " + note.number() + " draw its text once, in order.\nexpected: " + expected
                        + "\ndrawn:    " + drawn);
    }

    private static AbstractWidget nextButton(HandbookScreen screen) {
        String label =
                Component.translatable("deepcharter.handbook.screen.next").getString();
        return screen.children().stream()
                .filter(AbstractWidget.class::isInstance)
                .map(AbstractWidget.class::cast)
                .filter(widget -> widget.getMessage().getString().equals(label))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no Next button"));
    }

    private static void press(HandbookScreen screen, AbstractWidget button) {
        require(
                button.visible && button.active,
                "the button '" + button.getMessage().getString() + "' can be pressed");
        MouseButtonEvent click =
                new MouseButtonEvent(button.getX() + 2, button.getY() + 2, new MouseButtonInfo(LEFT_MOUSE, 0));
        require(
                screen.mouseClicked(click, false),
                "the click on '" + button.getMessage().getString() + "' is taken");
    }

    /** Four chapters in registry order: the id list and the directive list must match by index. */
    private static void theServerRefusesWhatTheCharterMayNotRead() {
        List<Identifier> ids = List.of(chapterId(1), chapterId(2), chapterId(3), chapterId(4));
        Identifier unknown = chapterId(9);
        require(
                HandbookReadPayload.viewable(ids, directives(), done(1), chapterId(1)),
                "a completed chapter is viewable");
        require(
                HandbookReadPayload.viewable(ids, directives(), done(1), chapterId(2)),
                "the current chapter is viewable");
        require(
                !HandbookReadPayload.viewable(ids, directives(), done(1), chapterId(3)),
                "the next chapter is refused: only its title shows");
        require(
                !HandbookReadPayload.viewable(ids, directives(), done(1), chapterId(4)),
                "a classified chapter is refused");
        require(!HandbookReadPayload.viewable(ids, directives(), done(1), unknown), "an unknown chapter is refused");
        require(
                HandbookReadPayload.viewable(ids, directives(), Set.of(), chapterId(1)),
                "with nothing done the first chapter is viewable");
        require(
                !HandbookReadPayload.viewable(ids, directives(), Set.of(), chapterId(2)),
                "with nothing done the second chapter is refused");
        require(
                !HandbookReadPayload.viewable(ids, directives(), done(1, 2, 3), chapterId(9)),
                "an unknown chapter is refused when all are done");
        require(
                HandbookReadPayload.viewable(ids, directives(), done(1, 2, 3, 4), chapterId(4)),
                "every chapter is viewable when all are done");
        require(
                !HandbookReadPayload.viewable(List.of(), List.of(), Set.of(), chapterId(1)),
                "nothing is viewable with no chapters");
    }

    private static void redactionMarksSplitIntoWordsAndAnOddCountRedactsTheTail() {
        List<RedactionText.Token> tokens = RedactionText.parse("report ||unusual findings|| to ||Mgmt||.");
        require(
                tokens.equals(List.of(
                        new RedactionText.Token("report", false, false),
                        new RedactionText.Token("unusual", true, true),
                        new RedactionText.Token("findings", true, true),
                        new RedactionText.Token("to", false, true),
                        new RedactionText.Token("Mgmt", true, true),
                        new RedactionText.Token(".", false, false))),
                "a pair of marks redacts the words between them, got " + tokens);
        List<RedactionText.Token> odd = RedactionText.parse("open ||secret tail");
        require(
                odd.equals(List.of(
                        new RedactionText.Token("open", false, false),
                        new RedactionText.Token("secret", true, true),
                        new RedactionText.Token("tail", true, true))),
                "an odd count of marks redacts the tail, got " + odd);
        require(RedactionText.parse("").isEmpty() && RedactionText.parse("||||").isEmpty(), "no words give no tokens");
        require(
                RedactionText.parse("  lead").equals(List.of(new RedactionText.Token("lead", false, false))),
                "a leading space is dropped");
    }

    private static void marginKeysCarryTheNamespaceAndThePath() {
        require(
                HandbookScreen.marginKey(SAMPLE_CHAPTER)
                        .equals("deepcharter.handbook.chapter.deepcharter.sample.margin"),
                "the margin key is " + HandbookScreen.marginKey(SAMPLE_CHAPTER));
        require(
                HandbookScreen.marginKey(Identifier.fromNamespaceAndPath("other", "a/b"))
                        .equals("deepcharter.handbook.chapter.other.a.b.margin"),
                "a slash in the path becomes a dot");
        require(
                !HandbookScreen.marginKey(Identifier.fromNamespaceAndPath("other", "sample"))
                        .equals(HandbookScreen.marginKey(SAMPLE_CHAPTER)),
                "two namespaces do not share a note");
    }

    private static Map<Identifier, HandbookChapter> spec(int count) {
        Map<Identifier, HandbookChapter> chapters = new LinkedHashMap<>();
        for (int chapter = 1; chapter <= count; chapter++) {
            chapters.put(
                    chapterId(chapter),
                    new HandbookChapter(
                            chapter,
                            Component.literal("PLACEHOLDER: a rather long chapter title number " + chapter),
                            List.of(new HandbookChapter.Entry(directive(chapter, 1), Component.literal("one")))));
        }
        return chapters;
    }

    /** Nine chapters (docs/SPEC.md) on a sheet at its minimum size: no chapter may fall off the contents. */
    private static void everyChapterOfNineHasAnEntryAndAClickTargetAtMinimumSize(ClientGameTestContext context) {
        context.runOnClient(client -> {
            List<HandbookPage> pages = HandbookPages.of(spec(SPEC_CHAPTERS), done(1));
            HandbookScreen screen = new HandbookScreen(pages, id -> false, id -> {}, List.of());
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
                require(
                        entries.size() == contents.count(),
                        "a contents page lists its " + contents.count() + " chapters, got " + entries.size());
                for (HandbookScreen.ContentsEntry entry : entries) {
                    require(
                            entry.bottom() <= nextButton(screen).getY(),
                            "entry for page " + entry.page() + " ends at " + entry.bottom()
                                    + ", over the Back and Next buttons at "
                                    + nextButton(screen).getY());
                    seen.add(((HandbookPage.Chapter) pages.get(entry.page())).number());
                    screen.goTo(index);
                    double x = entry.left() + 2;
                    double y = (entry.top() + entry.bottom()) / 2.0;
                    MouseButtonEvent click = new MouseButtonEvent(x, y, new MouseButtonInfo(LEFT_MOUSE, 0));
                    require(
                            screen.mouseClicked(click, false),
                            "the entry for page " + entry.page() + " is a click target");
                    require(
                            screen.page() == entry.page(),
                            "clicking the entry goes to page " + entry.page() + ", was " + screen.page());
                    for (int tick = 0; tick < HandbookScreenTuning.current().flipTicks(); tick++) {
                        screen.tick();
                    }
                    screen.goTo(index);
                    for (int tick = 0; tick < HandbookScreenTuning.current().flipTicks(); tick++) {
                        screen.tick();
                    }
                }
            }
            require(
                    seen.equals(List.of(1, 2, 3, 4, 5, 6, 7, 8, 9)),
                    "every chapter has exactly one entry, in order, got " + seen);
        });
    }

    private static void readMarksRoundTripThroughTheSavedFormat() {
        ReadMarks marks = new ReadMarks(Set.of(SAMPLE_CHAPTER, chapterId(2)));
        Tag saved = ReadMarks.CODEC
                .encodeStart(NbtOps.INSTANCE, Versioned.of(marks))
                .getOrThrow();
        Versioned<ReadMarks> value =
                ReadMarks.CODEC.parse(NbtOps.INSTANCE, saved).getOrThrow();
        require(
                value instanceof Versioned.Readable<ReadMarks> readable
                        && readable.value().equals(marks),
                "read marks load back from the saved format");
    }

    private static void inTheRealGame(ClientGameTestContext context) {
        require(
                HandbookKeys.OPEN.getDefaultKey().getValue() == InputConstants.KEY_H,
                "the handbook key is H by default");
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            ClientWait.until(
                    context,
                    "the handbook in the inventory",
                    client -> handbookSlot(client.player.getInventory()) >= 0);
            context.runOnClient(
                    client -> client.player.getInventory().setSelectedSlot(handbookSlot(client.player.getInventory())));

            context.getInput().pressKey(HandbookKeys.OPEN);
            ClientWait.screen(context, HandbookScreen.class);
            context.runOnClient(client -> client.gui.screen().keyPressed(new KeyEvent(InputConstants.KEY_H, 0, 0)));
            ClientWait.until(context, "the handbook screen closed", client -> client.gui.screen() == null);

            context.getInput().pressKey(options -> options.keyUse);
            ClientWait.screen(context, HandbookScreen.class);
            context.takeScreenshot("handbook-cover");
            HandbookScreen screen = context.computeOnClient(client -> (HandbookScreen) client.gui.screen());

            int chapterPage = context.computeOnClient(client -> {
                List<HandbookPage> pages = HandbookScreen.currentPages(client);
                for (int index = 0; index < pages.size(); index++) {
                    if (pages.get(index) instanceof HandbookPage.Chapter chapter
                            && chapter.id().equals(SAMPLE_CHAPTER)) {
                        return index;
                    }
                }
                return -1;
            });
            require(chapterPage >= 0, "the sample chapter has a page");
            require(
                    context.computeOnClient(client ->
                            HandbookScreen.currentPages(client).get(chapterPage) instanceof HandbookPage.Chapter chapter
                                    && chapter.visibility() == HandbookVisibility.FULL),
                    "the first chapter is full for a new player");
            require(
                    !context.computeOnClient(client -> ClientReadMarks.isRead(SAMPLE_CHAPTER)),
                    "the chapter is unread before it is viewed");

            context.runOnClient(client -> screen.goTo(chapterPage));
            context.waitTicks(HandbookScreenTuning.current().flipTicks() + 2);
            context.takeScreenshot("handbook-chapter");
            ClientWait.until(
                    context, "the sample chapter marked read", client -> ClientReadMarks.isRead(SAMPLE_CHAPTER));
            require(
                    singleplayer
                            .getServer()
                            .computeOnServer(server -> ReadMarks.isRead(
                                    server.getPlayerList().getPlayers().getFirst(), SAMPLE_CHAPTER)),
                    "the server holds the read mark");
            require(
                    singleplayer
                            .getServer()
                            .computeOnServer(server -> !ReadMarks.isRead(
                                    server.getPlayerList().getPlayers().getFirst(), NO_SUCH_CHAPTER)),
                    "only the viewed chapter is marked");

            singleplayer.getServer().runOnServer(server -> {
                var player = server.getPlayerList().getPlayers().getFirst();
                require(
                        !HandbookReadPayload.handle(server, player, NO_SUCH_CHAPTER),
                        "the server refuses a chapter that does not exist");
                require(!ReadMarks.isRead(player, NO_SUCH_CHAPTER), "a chapter that does not exist is not marked");
                require(
                        HandbookReadPayload.viewableFor(server, player.getUUID(), SAMPLE_CHAPTER),
                        "the first chapter is viewable");
                require(
                        !HandbookReadPayload.viewableFor(server, player.getUUID(), SAMPLE_SLEEP),
                        "a directive id is not a chapter");
                require(
                        HandbookReadPayload.handle(server, player, SAMPLE_CHAPTER),
                        "viewing a chapter again is accepted and changes nothing");
            });

            // The viewed chapter stays marked for the owner.
            require(
                    context.computeOnClient(client -> ClientReadMarks.read().equals(Set.of(SAMPLE_CHAPTER))),
                    "the client sees exactly one chapter read");
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
