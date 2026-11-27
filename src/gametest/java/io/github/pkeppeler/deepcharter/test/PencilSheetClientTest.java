package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.ClientChecks.require;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.client.handbook.ClientPencil;
import io.github.pkeppeler.deepcharter.client.handbook.HandbookMemory;
import io.github.pkeppeler.deepcharter.client.handbook.HandbookPage;
import io.github.pkeppeler.deepcharter.client.handbook.HandbookPages;
import io.github.pkeppeler.deepcharter.client.handbook.HandbookScreen;
import io.github.pkeppeler.deepcharter.client.handbook.HandbookScreenTuning;
import io.github.pkeppeler.deepcharter.handbook.HandbookChapter;
import io.github.pkeppeler.deepcharter.handbook.PencilPath;
import io.github.pkeppeler.deepcharter.handbook.PencilPaths;
import io.github.pkeppeler.deepcharter.handbook.PencilPlace;
import io.github.pkeppeler.deepcharter.test.support.ClientChecks;
import io.github.pkeppeler.deepcharter.test.support.DrawnText;
import io.github.pkeppeler.deepcharter.test.support.DrawnText.Drawn;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * Client GameTest for the pencil sheet (#849): the paths of earlier miners after the last chapter, drawn in the green of a margin note,
 * only once a Note has drawn one; a fixed page, so nothing on it drags or zooms; and everything on it fits the sheet at the default
 * window and the smallest, beside the Back and Next buttons. The screens are driven by hand, with no world, and read from the text
 * they draw ({@link DrawnText}); the real game is in the pencil paths scenario.
 */
public class PencilSheetClientTest implements FabricClientGameTest {
    // no-world-ok: the pages are built from steps with no item targets, so no item stack is drawn.
    private static final int LEFT_MOUSE = 1;
    private static final int WIDTH = 427;
    private static final int HEIGHT = 240;
    private static final int MIN_WIDTH = 200;
    private static final int MIN_HEIGHT = 100;
    /** As many chapters as ship, so the tab column is as long as a player's at the smallest window. */
    private static final int CHAPTERS = 9;

    /** The shipped paths, the first still open and the second reached. */
    private static final List<HandbookPage.PencilEntry> SHIPPED = List.of(
            new HandbookPage.PencilEntry(PencilPaths.ONES_BELOW, false),
            new HandbookPage.PencilEntry(PencilPaths.OLD_SHAFT, true));

    @Override
    public void runTest(ClientGameTestContext context) {
        ClientTestLog.start(this);
        context.runOnClient(client -> {
            theSheetComesAfterTheLastChapterAndOnlyWhenAPathIsDrawn();
            theSheetDrawsTheNotesTheHintsAndTheReachedPlaces();
            theSheetIsAFixedPage();
            aPathCompletingWhileTheBookIsOpenIsInkedInAtOnce();
            everythingFitsTheSheetAtTheDefaultWindowAndTheSmallest();
            theTabOpensTheSheetAndTheChapterTabsLeaveIt();
            theBookReopensOnTheSheet();
        });
    }

    private static Map<Identifier, HandbookChapter> chapters() {
        Map<Identifier, HandbookChapter> chapters = new LinkedHashMap<>();
        for (int chapter = 1; chapter <= CHAPTERS; chapter++) {
            Identifier id = Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "test" + chapter);
            chapters.put(
                    id,
                    new HandbookChapter(
                            chapter,
                            Component.literal("Title " + chapter),
                            List.of(new HandbookChapter.Entry(
                                    Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "handbook/test/p" + chapter),
                                    Component.literal("Do thing " + chapter),
                                    List.of()))));
        }
        return chapters;
    }

    private static List<HandbookPage> pages(List<HandbookPage.PencilEntry> entries) {
        return HandbookPages.withPencil(HandbookPages.of(chapters(), Set.of()), entries);
    }

    private static HandbookScreen screen(HandbookMemory memory, int width, int height) {
        HandbookScreen screen = new HandbookScreen(pages(SHIPPED), id -> true, id -> {}, List.of(), memory);
        screen.init(width, height);
        return screen;
    }

    private static int pencilPage(List<HandbookPage> pages) {
        for (int index = 0; index < pages.size(); index++) {
            if (pages.get(index) instanceof HandbookPage.Pencil) {
                return index;
            }
        }
        throw new AssertionError("the book has no pencil sheet");
    }

    /** A screen on the pencil sheet, its page flip over. */
    private static HandbookScreen sheet(HandbookMemory memory, int width, int height) {
        HandbookScreen screen = screen(memory, width, height);
        screen.goTo(pencilPage(pages(SHIPPED)));
        settle(screen);
        return screen;
    }

    private static void settle(HandbookScreen screen) {
        for (int tick = 0; tick < HandbookScreenTuning.current().flipTicks(); tick++) {
            screen.tick();
        }
    }

    private static List<String> texts(HandbookScreen screen) {
        return DrawnText.of(screen).stream().map(Drawn::text).toList();
    }

    private static MouseButtonEvent mouse(double x, double y) {
        return new MouseButtonEvent(x, y, new MouseButtonInfo(LEFT_MOUSE, 0));
    }

    private static List<AbstractWidget> widgetsLabelled(HandbookScreen screen, String label) {
        List<AbstractWidget> found = new ArrayList<>();
        for (var child : screen.children()) {
            if (child instanceof AbstractWidget widget
                    && widget.visible
                    && widget.getMessage().getString().equals(label)) {
                found.add(widget);
            }
        }
        return found;
    }

    private static AbstractWidget pencilTab(HandbookScreen screen) {
        String label = Component.translatable("deepcharter.handbook.pencil.tab").getString();
        List<AbstractWidget> tabs = widgetsLabelled(screen, label).stream()
                .filter(widget -> widget.getX() >= screen.width / 2)
                .toList();
        require(tabs.size() == 1, "one visible pencil tab on the sheet's edge, found " + tabs.size());
        return tabs.getFirst();
    }

    private static void click(HandbookScreen screen, AbstractWidget widget) {
        screen.mouseClicked(mouse(widget.getX() + 1, widget.getY() + 1), false);
        screen.mouseReleased(mouse(widget.getX() + 1, widget.getY() + 1));
    }

    private static void theSheetComesAfterTheLastChapterAndOnlyWhenAPathIsDrawn() {
        List<HandbookPage> plain = HandbookPages.of(chapters(), Set.of());
        require(
                pages(List.of()).equals(plain),
                "with no path drawn the book has no pencil sheet: the map continues in pencil only once a Note draws a path");
        List<HandbookPage> pages = pages(SHIPPED);
        int sheet = pencilPage(pages);
        require(
                pages.get(sheet - 1) instanceof HandbookPage.Chapter last && last.number() == CHAPTERS,
                "the sheet is bound right after the last chapter's map, page " + sheet);
        require(pages.get(sheet + 1) instanceof HandbookPage.Appendix, "and before the end page");
        require(pages.size() == plain.size() + 1, "one sheet holds both shipped paths");

        PencilPath third = new PencilPath("test_third", 5, new PencilPlace.Note(6));
        List<HandbookPage.PencilEntry> three = new ArrayList<>(SHIPPED);
        three.add(new HandbookPage.PencilEntry(third, false));
        List<HandbookPage> split = pages(three);
        require(
                split.size() == plain.size() + 2
                        && split.get(sheet) instanceof HandbookPage.Pencil first
                        && first.part() == 1
                        && first.parts() == 2
                        && first.entries().size() == HandbookPages.PENCILS_PER_PAGE
                        && split.get(sheet + 1) instanceof HandbookPage.Pencil second
                        && second.part() == 2
                        && second.entries().equals(List.of(three.getLast())),
                "more paths than a sheet holds flow onto the next sheet: " + split.subList(sheet, sheet + 2));
    }

    private static void theSheetDrawsTheNotesTheHintsAndTheReachedPlaces() {
        HandbookScreen screen = sheet(new HandbookMemory(), WIDTH, HEIGHT);
        List<String> texts = texts(screen);
        for (String key : List.of("deepcharter.handbook.pencil.label", "deepcharter.handbook.pencil.title")) {
            require(texts.contains(Component.translatable(key).getString()), "the sheet draws " + key + ": " + texts);
        }
        require(
                texts.stream().anyMatch(text -> text.startsWith("N07. Prayer")),
                "the box of the Note that draws the first path names it: " + texts);
        require(
                texts.stream().anyMatch(text -> text.startsWith("N10. Ines")),
                "the box of the Note that draws the second path names it: " + texts);
        require(
                texts.stream().anyMatch(text -> text.startsWith("Past the Claim")),
                "an open path shows what the earlier miner pencilled: " + texts);
        require(
                texts.stream().anyMatch(text -> text.startsWith("The Kells")),
                "a reached path names its place: " + texts);
        require(
                texts.stream().noneMatch(text -> text.startsWith("Ines went down")),
                "a reached path no longer shows its hint: " + texts);
        require(
                texts.stream().noneMatch(text -> text.startsWith("PROSPECTOR-0002")),
                "an open path does not name its place yet: " + texts);
    }

    /** Dragging and scrolling on the sheet change nothing it draws. */
    private static void theSheetIsAFixedPage() {
        HandbookScreen screen = sheet(new HandbookMemory(), WIDTH, HEIGHT);
        List<Drawn> before = DrawnText.of(screen);
        double x = WIDTH / 2.0;
        double y = HEIGHT / 2.0;
        screen.mouseClicked(mouse(x, y), false);
        screen.mouseDragged(mouse(x + 30, y + 30), 30, 30);
        screen.mouseReleased(mouse(x + 30, y + 30));
        screen.mouseScrolled(x, y, 0, 1);
        screen.mouseScrolled(x, y, 0, -3);
        require(DrawnText.of(screen).equals(before), "a drag or a scroll on the pencil sheet moves nothing");
    }

    /** The server's sync reaches an open sheet: its next draw shows the place the path now names. */
    private static void aPathCompletingWhileTheBookIsOpenIsInkedInAtOnce() {
        HandbookScreen screen = sheet(new HandbookMemory(), WIDTH, HEIGHT);
        String place = Component.translatable(PencilPaths.ONES_BELOW.placeKey()).getString();
        require(
                texts(screen).stream().noneMatch(text -> text.startsWith(place)),
                "setup: the first path is open on the sheet: " + texts(screen));
        try {
            ClientPencil.set(List.of(PencilPaths.ONES_BELOW.id()));
            require(
                    texts(screen).stream().anyMatch(text -> text.startsWith(place)),
                    "the open sheet names the place once the sync says it is reached: " + texts(screen));
        } finally {
            ClientPencil.set(List.of());
        }
    }

    private static void everythingFitsTheSheetAtTheDefaultWindowAndTheSmallest() {
        int[][] windows = {{WIDTH, HEIGHT}, {MIN_WIDTH, MIN_HEIGHT}};
        for (int[] window : windows) {
            HandbookScreen screen = sheet(new HandbookMemory(), window[0], window[1]);
            String where = "the pencil sheet at " + window[0] + " x " + window[1];
            List<Drawn> drawn = DrawnText.of(screen);
            require(drawn.size() >= 6, where + " draws its heading and both rows: " + texts(screen));
            List<AbstractWidget> buttons = new ArrayList<>();
            for (String key : List.of("deepcharter.handbook.screen.back", "deepcharter.handbook.screen.next")) {
                buttons.addAll(
                        widgetsLabelled(screen, Component.translatable(key).getString()));
            }
            for (Drawn text : drawn) {
                ClientChecks.requireNone(where, DrawnText.leavesScreen(screen, text));
                ClientChecks.requireNone(where, DrawnText.underWidget(text, buttons));
                for (Drawn other : drawn) {
                    require(
                            text == other || !ClientChecks.overlaps(text.box(), other.box()),
                            where + ": '" + text.shortText() + "' overlaps '" + other.shortText() + "'");
                }
            }
            AbstractWidget tab = pencilTab(screen);
            require(
                    tab.getX() >= 0 && tab.getRight() <= window[0] && tab.getBottom() <= window[1],
                    where + ": the pencil tab is on the screen");
            int lineHeight = Minecraft.getInstance().font.lineHeight;
            for (var child : screen.children()) {
                if (child instanceof AbstractWidget widget && widget.visible && widget.getX() >= screen.width / 2) {
                    require(
                            widget.getHeight() >= lineHeight && widget.getY() >= 0 && widget.getBottom() <= window[1],
                            where + ": the side tab '" + widget.getMessage().getString() + "' is " + widget.getHeight()
                                    + " px tall at y " + widget.getY() + ", a line of text is " + lineHeight);
                }
            }
        }
    }

    private static void theTabOpensTheSheetAndTheChapterTabsLeaveIt() {
        HandbookScreen screen = screen(new HandbookMemory(), WIDTH, HEIGHT);
        screen.goTo(1);
        settle(screen);
        screen.goTo(pencilPage(pages(SHIPPED)) - 1);
        settle(screen);
        click(screen, pencilTab(screen));
        settle(screen);
        require(screen.page() == pencilPage(pages(SHIPPED)), "the pencil tab opens the sheet, page " + screen.page());
        require(
                screen.section() == CHAPTERS + 1,
                "the pencil tab reads as selected on the sheet: the section is the one after the chapters, got "
                        + screen.section());
        List<AbstractWidget> chapterOne = widgetsLabelled(screen, "1").stream()
                .filter(widget -> widget.getX() >= screen.width / 2)
                .toList();
        require(chapterOne.size() == 1, "the sheet shows the chapter tabs too: " + chapterOne.size());
        click(screen, chapterOne.getFirst());
        settle(screen);
        require(
                screen.page() != pencilPage(pages(SHIPPED)),
                "a chapter's tab leaves the sheet for that chapter, page " + screen.page());
    }

    private static void theBookReopensOnTheSheet() {
        HandbookMemory memory = new HandbookMemory();
        HandbookScreen first = sheet(memory, WIDTH, HEIGHT);
        require(first.page() == pencilPage(pages(SHIPPED)), "setup: the sheet is open");
        HandbookScreen second = new HandbookScreen(pages(SHIPPED), id -> true, id -> {}, List.of(), memory);
        require(
                second.page() == pencilPage(pages(SHIPPED)),
                "the book opens on the sheet it was left on, got page " + second.page());
    }
}
