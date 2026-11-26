package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.ClientChecks.require;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.pkeppeler.deepcharter.client.handbook.HandbookMemory;
import io.github.pkeppeler.deepcharter.client.handbook.HandbookPage;
import io.github.pkeppeler.deepcharter.client.handbook.HandbookScreen;
import io.github.pkeppeler.deepcharter.client.handbook.HandbookScreenTuning;
import io.github.pkeppeler.deepcharter.test.support.ClientChecks.Box;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.DrawnText;
import io.github.pkeppeler.deepcharter.test.support.DrawnText.Drawn;
import io.github.pkeppeler.deepcharter.test.support.ShippedHandbook;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.network.chat.Component;

/**
 * Client GameTest for #1429: text that does not fit a handbook sheet runs on to the next sheet. The shipped handbook,
 * every chapter full, is walked sheet by sheet with Next at the GUI sizes real windows reach, and no text may end below
 * the top of Back and Next. The sheets of a text page draw its text once, in order, and the page counter, Back, the
 * arrow keys, the contents, the index card's page link and a resize all count the extra sheets. A world is loaded only
 * for the synced chapter registry; the screens are built by hand and read from the text they draw ({@link DrawnText}).
 */
public class HandbookPageFlowClientTest implements FabricClientGameTest {
    private static final Pattern COUNTER = Pattern.compile("(\\d+) / (\\d+)");
    /**
     * More Next presses than the smallest sheet needs for the whole book; past it, the walk is going round in circles.
     */
    private static final int MAX_TURNS = 2000;

    private static final int LEFT_MOUSE = 1;
    /** A window so small that the sheet is at its minimum size, where every chapter's text runs over several sheets. */
    private static final Size SMALLEST = new Size("the minimum sheet (a 200 x 100 GUI)", 200, 100);
    /** The default window at GUI scale 2, where the sheet is at its largest. */
    private static final Size DEFAULT = Size.of(854, 480, 2);

    /**
     * The GUI sizes no text may run off the sheet at: the default window; the smallest window GUI scale 4 allows, whose
     * 320 x 240 GUI is the narrowest sheet any GUI scale reaches; and a 1920 x 1080 window at GUI scales 3 and 4. A
     * wider or taller window draws the default sheet.
     */
    private static final List<Size> SIZES =
            List.of(DEFAULT, Size.of(1280, 960, 4), Size.of(1920, 1080, 3), Size.of(1920, 1080, 4));

    /** A GUI size, named by the window and GUI scale it comes from. */
    private record Size(String name, int width, int height) {
        /** The GUI size of a window at a GUI scale, rounded up as vanilla's {@code Window} rounds it. */
        static Size of(int windowWidth, int windowHeight, int scale) {
            return new Size(
                    windowWidth + " x " + windowHeight + " at GUI scale " + scale,
                    Math.ceilDiv(windowWidth, scale),
                    Math.ceilDiv(windowHeight, scale));
        }
    }

    /**
     * A sheet the walk stopped on: its page's index, the sheet of that page from 0, the counter's number and total, and
     * the text drawn.
     */
    private record Stop(int page, int sheet, int number, int total, List<Drawn> drawn) {}

    // The world is only here for the chapter registry it syncs; the screens do not touch it.
    @SuppressWarnings("try")
    @Override
    public void runTest(ClientGameTestContext context) {
        ClientTestLog.start(this);
        try (TestSingleplayerContext ignored = context.worldBuilder().create()) {
            ClientWait.until(
                    context,
                    "the player and the synced chapters",
                    client -> client.player != null && client.getConnection() != null);
            context.runOnClient(client -> {
                List<HandbookPage> pages = ShippedHandbook.everyChapterFull(client);
                noTextRunsOffASheet(pages);
                theSheetsOfATextPageDrawItsTextOnce(pages);
                theCounterNumbersEverySheet(pages);
                backRetracesNext(pages);
                theArrowKeysTurnSheets(pages);
                theContentsOpenAChaptersFirstSheet(pages);
                thePageLinkOpensAChaptersFirstSheet(pages);
                aResizeKeepsTheSheetInTheBook(pages);
            });
        }
    }

    /** Every sheet of every page, at every size: no text the screen draws ends below the top of Back and Next. */
    private static void noTextRunsOffASheet(List<HandbookPage> pages) {
        List<String> problems = new ArrayList<>();
        for (Size size : SIZES) {
            HandbookScreen screen = screen(pages, size);
            int limit = nextButton(screen).getY();
            for (Stop stop : walk(screen)) {
                List<Drawn> below = stop.drawn().stream()
                        .filter(drawn -> !isCounter(drawn) && drawn.box().bottom() > limit)
                        .toList();
                if (!below.isEmpty()) {
                    problems.add(size.name() + ": " + describe(pages, stop) + ": '"
                            + below.getFirst().shortText() + "' ends at y "
                            + below.getFirst().box().bottom()
                            + ", below the top of Back and Next at " + limit + " (" + below.size()
                            + " lines below it)");
                }
            }
        }
        require(problems.isEmpty(), "text runs off the handbook sheet:\n" + String.join("\n", problems));
    }

    /**
     * The sheets of a chapter's text page, read in order without the label, the title, the counter and the margin note,
     * draw the page's text: no line twice, none lost.
     */
    private static void theSheetsOfATextPageDrawItsTextOnce(List<HandbookPage> pages) {
        for (Size size : withSmallest()) {
            List<Stop> stops = walk(screen(pages, size));
            for (int first = 0; first < stops.size(); ) {
                int page = stops.get(first).page();
                int end = first;
                while (end < stops.size() && stops.get(end).page() == page) {
                    end++;
                }
                if (pages.get(page) instanceof HandbookPage.ChapterText text) {
                    List<String> body = new ArrayList<>();
                    for (Stop stop : stops.subList(first, end)) {
                        body.addAll(bodyOf(text, stop, size));
                    }
                    String expected = normal(Component.translatable(HandbookScreen.textKey(text.id(), text.part()))
                            .getString());
                    String drawn = normal(String.join(" ", body));
                    require(
                            drawn.equals(expected),
                            size.name() + ": the sheets of " + describe(pages, stops.get(first))
                                    + " should draw its text once, in order.\nexpected: " + expected + "\ndrawn:    "
                                    + drawn);
                }
                first = end;
            }
        }
    }

    /** The text lines of one sheet of a text page: what the text column draws after the label and the title. */
    private static List<String> bodyOf(HandbookPage.ChapterText text, Stop stop, Size size) {
        String label = Component.translatable("deepcharter.handbook.chapter.label", text.number())
                .getString();
        List<Drawn> column =
                stop.drawn().stream().filter(drawn -> !isCounter(drawn)).toList();
        require(
                !column.isEmpty() && column.getFirst().text().equals(label),
                size.name() + ": sheet " + (stop.sheet() + 1) + " of chapter " + text.number()
                        + "'s text starts with its label '" + label + "'");
        int left = column.getFirst().box().x();
        List<String> lines = column.stream()
                .filter(drawn -> drawn.box().x() == left)
                .map(Drawn::text)
                .toList();
        String title = normal(text.chapter().title().getString());
        int start = 1;
        StringBuilder heading = new StringBuilder();
        while (start < lines.size() && !normal(heading.toString()).equals(title)) {
            heading.append(' ').append(lines.get(start++));
        }
        return lines.subList(start, lines.size());
    }

    /** The counter reads 1 to N across the walk, one number a sheet, and N is how many sheets the walk turned. */
    private static void theCounterNumbersEverySheet(List<HandbookPage> pages) {
        for (Size size : withSmallest()) {
            List<Stop> stops = walk(screen(pages, size));
            for (int index = 0; index < stops.size(); index++) {
                Stop stop = stops.get(index);
                require(
                        stop.number() == index + 1 && stop.total() == stops.size(),
                        size.name() + ": " + describe(pages, stop) + " should be counted " + (index + 1) + " / "
                                + stops.size() + ", was " + stop.number() + " / " + stop.total());
            }
        }
    }

    /**
     * From the last sheet, Back visits every sheet Next did, in reverse: onto a page of several sheets it lands on the
     * last.
     */
    private static void backRetracesNext(List<HandbookPage> pages) {
        HandbookScreen screen = screen(pages, SMALLEST);
        List<Stop> stops = walk(screen);
        require(
                stops.stream().anyMatch(stop -> stop.sheet() > 0),
                "setup: at the minimum sheet some page runs over more than one sheet");
        for (int index = stops.size() - 2; index >= 0; index--) {
            press(screen, backButton(screen));
            settle(screen);
            Stop expected = stops.get(index);
            int number = counter(DrawnText.of(screen))[0];
            require(
                    screen.page() == expected.page() && number == expected.number(),
                    "Back should go to " + describe(pages, expected) + " (" + expected.number() + "), went to page "
                            + screen.page() + " (" + number + ")");
        }
        require(!backButton(screen).active, "Back stops at the cover");
    }

    /** The right and left arrows turn the sheets of a page as Next and Back do. */
    private static void theArrowKeysTurnSheets(List<HandbookPage> pages) {
        HandbookScreen screen = screen(pages, SMALLEST);
        List<Stop> stops = walk(screen);
        Stop second = stops.stream()
                .filter(stop -> stop.sheet() == 1)
                .findFirst()
                .orElseThrow(() -> new AssertionError("setup: no page runs over two sheets at the minimum sheet"));
        screen.goTo(second.page());
        settle(screen);
        require(
                counter(DrawnText.of(screen))[0] == second.number() - 1,
                "flipping to " + describe(pages, second) + " opens its first sheet");
        require(screen.keyPressed(new KeyEvent(InputConstants.KEY_RIGHT, 0, 0)), "the right arrow is taken");
        settle(screen);
        require(
                screen.page() == second.page() && counter(DrawnText.of(screen))[0] == second.number(),
                "the right arrow turns to the page's second sheet");
        require(screen.keyPressed(new KeyEvent(InputConstants.KEY_LEFT, 0, 0)), "the left arrow is taken");
        settle(screen);
        require(
                screen.page() == second.page() && counter(DrawnText.of(screen))[0] == second.number() - 1,
                "the left arrow turns back to the first sheet");
    }

    /** A contents entry opens the first sheet of its chapter, at the minimum sheet. */
    private static void theContentsOpenAChaptersFirstSheet(List<HandbookPage> pages) {
        HandbookScreen screen = screen(pages, SMALLEST);
        List<Stop> stops = walk(screen);
        for (int index = 0; index < pages.size(); index++) {
            if (!(pages.get(index) instanceof HandbookPage.Contents contents)) {
                continue;
            }
            for (HandbookScreen.ContentsEntry entry : screen.contentsEntries(contents)) {
                screen.goTo(index);
                settle(screen);
                click(screen, entry.left() + 2, (entry.top() + entry.bottom()) / 2.0);
                settle(screen);
                Stop first = firstStopOf(stops, entry.page());
                require(
                        screen.page() == entry.page() && counter(DrawnText.of(screen))[0] == first.number(),
                        "the contents entry for " + describe(pages, first) + " opens its first sheet, "
                                + first.number() + ", got page " + screen.page() + " ("
                                + counter(DrawnText.of(screen))[0] + ")");
            }
        }
    }

    /**
     * Each chapter map's index card names the number the counter shows on the chapter's first sheet, and opens it. In
     * the default window: the minimum one puts the card under the chapter tabs. Some chapter's text runs over two
     * sheets there too, so the links after it count the extra sheet.
     */
    private static void thePageLinkOpensAChaptersFirstSheet(List<HandbookPage> pages) {
        HandbookScreen screen = screen(pages, DEFAULT);
        List<Stop> stops = walk(screen);
        require(
                stops.stream().anyMatch(stop -> stop.sheet() > 0),
                "setup: in the default window some page runs over more than one sheet");
        for (int index = 0; index < pages.size(); index++) {
            if (!(pages.get(index) instanceof HandbookPage.Chapter chapter)) {
                continue;
            }
            Stop first = stops.stream()
                    .filter(stop -> HandbookPage.chapterNumber(pages.get(stop.page())) == chapter.number())
                    .findFirst()
                    .orElseThrow();
            screen.goTo(index);
            settle(screen);
            String link = Component.translatable("deepcharter.handbook.quest.see_page", first.number())
                    .getString();
            Box box = DrawnText.of(screen).stream()
                    .filter(drawn -> drawn.text().equals(link))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("chapter " + chapter.number()
                            + "'s index card should link '" + link + "', the counter of its first sheet: "
                            + DrawnText.of(screen).stream().map(Drawn::text).toList()))
                    .box();
            click(screen, box.x() + 1, box.y() + box.height() / 2.0);
            settle(screen);
            require(
                    screen.page() == first.page() && counter(DrawnText.of(screen))[0] == first.number(),
                    "chapter " + chapter.number() + "'s page link opens " + describe(pages, first));
        }
    }

    /**
     * On the last sheet of a long page at the minimum sheet, a resize to the default window leaves the screen on that
     * page's last sheet.
     */
    private static void aResizeKeepsTheSheetInTheBook(List<HandbookPage> pages) {
        List<Stop> wide = walk(screen(pages, DEFAULT));
        HandbookScreen screen = screen(pages, SMALLEST);
        List<Stop> narrow = walk(screen);
        Stop longest = narrow.stream()
                .max((a, b) -> Integer.compare(a.sheet(), b.sheet()))
                .orElseThrow();
        screen.goTo(longest.page());
        for (int sheet = 0; sheet < longest.sheet(); sheet++) {
            press(screen, nextButton(screen));
        }
        settle(screen);
        require(
                counter(DrawnText.of(screen))[0] == longest.number(),
                "setup: on the last sheet of " + describe(pages, longest));
        screen.init(DEFAULT.width(), DEFAULT.height());
        settle(screen);
        Stop last = wide.stream()
                .filter(stop -> stop.page() == longest.page())
                .reduce((a, b) -> b)
                .orElseThrow();
        int[] shown = counter(DrawnText.of(screen));
        require(
                screen.page() == longest.page() && shown[0] == last.number() && shown[1] == wide.size(),
                "after a resize the screen is on the last sheet of " + describe(pages, longest) + ", " + last.number()
                        + " / " + wide.size() + ", got page " + screen.page() + " at " + shown[0] + " / " + shown[1]);
    }

    private static List<Size> withSmallest() {
        List<Size> sizes = new ArrayList<>(SIZES);
        sizes.add(SMALLEST);
        return sizes;
    }

    private static HandbookScreen screen(List<HandbookPage> pages, Size size) {
        HandbookScreen screen = new HandbookScreen(pages, id -> true, id -> {}, List.of(), new HandbookMemory());
        screen.init(size.width(), size.height());
        return screen;
    }

    /** From the cover, presses Next until it is inactive, stopping on every sheet. */
    private static List<Stop> walk(HandbookScreen screen) {
        screen.goTo(0);
        List<Stop> stops = new ArrayList<>();
        for (int turn = 0; ; turn++) {
            require(turn < MAX_TURNS, "Next never reaches the end of the book in " + MAX_TURNS + " presses");
            settle(screen);
            List<Drawn> drawn = DrawnText.of(screen);
            int page = screen.page();
            int sheet = !stops.isEmpty() && stops.getLast().page() == page
                    ? stops.getLast().sheet() + 1
                    : 0;
            int[] counter = counter(drawn);
            stops.add(new Stop(page, sheet, counter[0], counter[1], drawn));
            AbstractWidget next = nextButton(screen);
            if (!next.active) {
                return stops;
            }
            press(screen, next);
        }
    }

    private static Stop firstStopOf(List<Stop> stops, int page) {
        return stops.stream()
                .filter(stop -> stop.page() == page)
                .findFirst()
                .orElseThrow(() -> new AssertionError("the walk never stopped on page " + page));
    }

    /** A page of the walk, for a failure message: the chapter and its text page or map, and which sheet of it. */
    private static String describe(List<HandbookPage> pages, Stop stop) {
        String page = switch (pages.get(stop.page())) {
            case HandbookPage.ChapterText text ->
                "chapter " + text.number() + " (" + text.id().getPath() + "), text page " + text.part() + " of "
                        + text.parts();
            case HandbookPage.Chapter chapter ->
                "chapter " + chapter.number() + " (" + chapter.id().getPath() + "), its map";
            case HandbookPage other -> other.toString();
        };
        return page + ", sheet " + (stop.sheet() + 1);
    }

    private static boolean isCounter(Drawn drawn) {
        return COUNTER.matcher(drawn.text()).matches();
    }

    /** The page counter's number and total. */
    private static int[] counter(List<Drawn> drawn) {
        for (Drawn text : drawn) {
            Matcher matcher = COUNTER.matcher(text.text());
            if (matcher.matches()) {
                return new int[] {Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2))};
            }
        }
        throw new AssertionError("the screen draws no page counter: "
                + drawn.stream().map(Drawn::text).toList());
    }

    private static String normal(String text) {
        return text.replaceAll("\\s+", " ").trim();
    }

    private static void settle(HandbookScreen screen) {
        for (int tick = 0; tick < HandbookScreenTuning.current().flipTicks(); tick++) {
            screen.tick();
        }
    }

    private static AbstractWidget nextButton(HandbookScreen screen) {
        return visibleButton(screen, "deepcharter.handbook.screen.next");
    }

    private static AbstractWidget backButton(HandbookScreen screen) {
        return visibleButton(screen, "deepcharter.handbook.screen.back");
    }

    /**
     * The shown button labelled {@code key}: the Notes tab's Back shares the handbook's label and hides on this tab.
     */
    private static AbstractWidget visibleButton(HandbookScreen screen, String key) {
        String label = Component.translatable(key).getString();
        return screen.children().stream()
                .filter(AbstractWidget.class::isInstance)
                .map(AbstractWidget.class::cast)
                .filter(widget ->
                        widget.visible && widget.getMessage().getString().equals(label))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no shown button '" + label + "'"));
    }

    private static void press(HandbookScreen screen, AbstractWidget button) {
        require(button.active, "the button '" + button.getMessage().getString() + "' can be pressed");
        click(screen, button.getX() + 2, button.getY() + 2);
    }

    private static void click(HandbookScreen screen, double x, double y) {
        require(
                screen.mouseClicked(new MouseButtonEvent(x, y, new MouseButtonInfo(LEFT_MOUSE, 0)), false),
                "the click at " + x + "," + y + " is taken");
    }
}
