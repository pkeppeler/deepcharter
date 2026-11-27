package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.ClientChecks.require;

import com.mojang.blaze3d.platform.cursor.CursorTypes;
import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.client.handbook.ClientHandbook;
import io.github.pkeppeler.deepcharter.client.handbook.HandbookMemory;
import io.github.pkeppeler.deepcharter.client.handbook.HandbookPage;
import io.github.pkeppeler.deepcharter.client.handbook.HandbookPages;
import io.github.pkeppeler.deepcharter.client.handbook.HandbookScreen;
import io.github.pkeppeler.deepcharter.client.handbook.HandbookScreenTuning;
import io.github.pkeppeler.deepcharter.client.handbook.QuestMapTuning;
import io.github.pkeppeler.deepcharter.client.handbook.catalogue.CataloguePanel;
import io.github.pkeppeler.deepcharter.client.handbook.catalogue.CatalogueState;
import io.github.pkeppeler.deepcharter.handbook.HandbookChapter;
import io.github.pkeppeler.deepcharter.handbook.HandbookChapters;
import io.github.pkeppeler.deepcharter.handbook.HandbookVisibility;
import io.github.pkeppeler.deepcharter.test.support.CatalogueScene;
import io.github.pkeppeler.deepcharter.test.support.ClientChecks.Box;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.DrawnText;
import io.github.pkeppeler.deepcharter.test.support.DrawnText.Drawn;
import io.github.pkeppeler.deepcharter.test.support.DrawnText.Fill;
import io.github.pkeppeler.deepcharter.test.support.DrawnText.Frame;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

/**
 * Client GameTest for the quest map (#847): each chapter's directives as a flowchart on the handbook paper, the chapter tabs, the index
 * card of a step with the items it needs, the book opening where it was left, the popup of a finished step and the L key. The screens
 * are driven by hand and read from the text they draw ({@link DrawnText}); the keys and the real game are in
 * {@code HandbookScreenClientTest} and the quest map scenario. The whole test runs in one world: the cards draw item stacks, and
 * item components are bound only once a world is loaded ("Components not bound yet" before it, #1124), so the test must not
 * lean on an earlier class's world (#1574).
 */
public class QuestMapClientTest implements FabricClientGameTest {
    private static final int LEFT_MOUSE = 1;
    private static final String INTRO_TAB = "i";
    private static final int WIDTH = 427;
    private static final int HEIGHT = 240;
    private static final int MIN_WIDTH = 200;
    private static final int MIN_HEIGHT = 100;
    private static final List<String> IDS = List.of("welcome", "back_online", "meet_the_mole", "fuel_is_life");
    /** Chapter 1 has six steps; the first five are done, so the sixth is the current step. */
    private static final int FIRST_CHAPTER_STEPS = 6;

    private static final Identifier IRON = Identifier.parse("minecraft:iron_ingot");

    @Override
    public void runTest(ClientGameTestContext context) {
        ClientTestLog.start(this);
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            ClientWait.until(context, "the player", client -> client.player != null && client.level != null);
            theScreensInTheWorld(context, singleplayer);
        }
    }

    private static void theScreensInTheWorld(ClientGameTestContext context, TestSingleplayerContext singleplayer) {
        context.runOnClient(client -> {
            theMapShowsTheStepsTheCardAndTheRoadAhead();
            nothingBeyondTheNextChapterIsReadable();
            everyPageShowsTheTabsAndSelectsItsSection();
            chapterTabsOpenTheirChapterMaps();
            aClassifiedChapterKeepsItsRedactedPage();
            pickingAStepMovesTheCard();
            theLinkOnTheCardOpensTheChapterPage();
            nothingOnAHandbookPageMoves();
            aFlowchartStepIsPickedByAClickAndNeverDragged();
            theBookOpensWhereItWasLeft();
            theMapFitsTheDefaultWindowAndTheSmallest();
            everyShippedMapFitsTheSheetWhole();
            aStepIsToastedOnlyWhenItIsNews();
            aDoneStepNeedsNothingMore();
            theBookReopensOnTheSamePageWhenProgressAddsPages();
            hoveringAStepLightsItAndAsksForTheHand();
            everyStepAClickOpensCarriesTheCueAndNoOtherDoes();
            aClickOnTheCueStillPicksTheStep();
            aCutNameStopsShortOfTheCue();
        });
        clickingAnItemOpensItsRecipeInTheCatalogue(context, singleplayer);
    }

    private static Identifier chapterId(int chapter) {
        return Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, IDS.get(chapter - 1));
    }

    private static Identifier directive(int chapter, int n) {
        return Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "handbook/test/d" + chapter + "_" + n);
    }

    private static Map<Identifier, HandbookChapter> chapters() {
        Map<Identifier, HandbookChapter> chapters = new LinkedHashMap<>();
        int[] steps = {FIRST_CHAPTER_STEPS, 3, 2, 2};
        for (int chapter = 1; chapter <= IDS.size(); chapter++) {
            List<HandbookChapter.Entry> entries = new ArrayList<>();
            for (int n = 1; n <= steps[chapter - 1]; n++) {
                boolean scanner = chapter == 1 && n == FIRST_CHAPTER_STEPS;
                entries.add(new HandbookChapter.Entry(
                        directive(chapter, n),
                        Component.literal("Do thing " + chapter + "." + n),
                        scanner
                                ? List.of(
                                        new HandbookChapter.Target(IRON, 4),
                                        new HandbookChapter.Target(Identifier.parse("minecraft:copper_ingot"), 2),
                                        new HandbookChapter.Target(Identifier.parse("minecraft:redstone"), 2))
                                : List.of()));
            }
            chapters.put(
                    chapterId(chapter), new HandbookChapter(chapter, Component.literal("Title " + chapter), entries));
        }
        return chapters;
    }

    /** Chapter 1's first five steps are done. */
    private static Set<Identifier> done() {
        Set<Identifier> done = new HashSet<>();
        for (int n = 1; n < FIRST_CHAPTER_STEPS; n++) {
            done.add(directive(1, n));
        }
        return done;
    }

    private static List<HandbookPage> pages() {
        return HandbookPages.of(chapters(), done());
    }

    private static HandbookScreen screen(HandbookMemory memory, int width, int height) {
        HandbookScreen screen = new HandbookScreen(pages(), id -> true, id -> {}, List.of(), memory);
        screen.init(width, height);
        return screen;
    }

    /** A screen on the map of {@code chapter}. */
    private static HandbookScreen mapScreen(HandbookMemory memory, int chapter) {
        HandbookScreen screen = screen(memory, WIDTH, HEIGHT);
        screen.goTo(mapPage(chapter));
        settle(screen);
        return screen;
    }

    /** Lets the page flip end, so the page is drawn flat and takes clicks. */
    private static void settle(HandbookScreen screen) {
        for (int tick = 0; tick < HandbookScreenTuning.current().flipTicks(); tick++) {
            screen.tick();
        }
    }

    private static int mapPage(int chapter) {
        List<HandbookPage> pages = pages();
        for (int index = 0; index < pages.size(); index++) {
            if (pages.get(index) instanceof HandbookPage.Chapter page && page.number() == chapter) {
                return index;
            }
        }
        throw new AssertionError("no map page for chapter " + chapter);
    }

    private static int firstPage(int chapter) {
        List<HandbookPage> pages = pages();
        for (int index = 0; index < pages.size(); index++) {
            if (HandbookPage.chapterNumber(pages.get(index)) == chapter) {
                return index;
            }
        }
        throw new AssertionError("no page for chapter " + chapter);
    }

    private static List<String> texts(HandbookScreen screen) {
        return DrawnText.of(screen).stream().map(Drawn::text).toList();
    }

    private static Drawn drawn(HandbookScreen screen, String text) {
        return DrawnText.of(screen).stream()
                .filter(entry -> entry.text().equals(text))
                .findFirst()
                .orElseThrow(() -> new AssertionError("the screen draws no '" + text + "': " + texts(screen)));
    }

    private static MouseButtonEvent mouse(double x, double y) {
        return new MouseButtonEvent(x, y, new MouseButtonInfo(LEFT_MOUSE, 0));
    }

    private static void click(HandbookScreen screen, Box box) {
        screen.mouseClicked(mouse(box.x() + 1, box.y() + 1), false);
        screen.mouseReleased(mouse(box.x() + 1, box.y() + 1));
    }

    private static String done(String line) {
        return Component.translatable("deepcharter.handbook.chapter.directive.done", line)
                .getString();
    }

    private static String open(String line) {
        return Component.translatable("deepcharter.handbook.chapter.directive.open", line)
                .getString();
    }

    private static void theMapShowsTheStepsTheCardAndTheRoadAhead() {
        HandbookScreen screen = mapScreen(new HandbookMemory(), 1);
        List<String> texts = texts(screen);
        for (int n = 1; n < FIRST_CHAPTER_STEPS; n++) {
            require(texts.contains(done("Do thing 1." + n)), "step " + n + " is a box with [X]: " + texts);
        }
        require(texts.contains(open("Do thing 1.6")), "the sixth step is a box with an empty checkbox: " + texts);
        require(texts.contains("Title 1"), "the chapter title heads the sheet");
        require(
                texts.contains(Component.translatable("deepcharter.handbook.quest.step", FIRST_CHAPTER_STEPS)
                        .getString()),
                "the card is for the current step, the sixth: " + texts);
        require(texts.contains("Do thing 1.6"), "the card carries the instruction without a checkbox");
        require(
                texts.contains(Component.translatable("deepcharter.handbook.quest.see_page", firstPage(1) + 1)
                        .getString()),
                "the card links to the chapter's first page, p. " + (firstPage(1) + 1) + ": " + texts);
        require(
                texts.containsAll(List.of("0/4", "0/2")),
                "the card counts each target item against what is held: " + texts);
        require(
                texts.stream().noneMatch(text -> text.contains("Title 2") || text.contains("Do thing 2")),
                "the map does not preview the next chapter: " + texts);
        require(
                texts.contains(Component.translatable("deepcharter.handbook.chapter.stamp.classified")
                        .getString()),
                "the redaction bars carry the CLASSIFIED stamp");
    }

    private static void nothingBeyondTheNextChapterIsReadable() {
        HandbookScreen screen = mapScreen(new HandbookMemory(), 1);
        List<String> texts = texts(screen);
        require(
                texts.stream()
                        .noneMatch(text -> text.contains("Do thing 3")
                                || text.contains("Do thing 4")
                                || text.contains("Title 3")
                                || text.contains("Title 4")),
                "a chapter after the next is only redaction bars: " + texts);
        HandbookScreen preview = mapScreen(new HandbookMemory(), 2);
        require(
                texts(preview).contains("Do thing 2.1")
                        && texts(preview).stream().noneMatch(text -> text.startsWith("[")),
                "the next chapter's steps carry no checkbox: " + texts(preview));
    }

    /** The intro tab first, then one tab for each chapter, in the order they step down the sheet's right edge. */
    private static List<AbstractWidget> tabs(HandbookScreen screen) {
        List<AbstractWidget> tabs = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        labels.add(INTRO_TAB);
        for (int chapter = 1; chapter <= IDS.size(); chapter++) {
            labels.add(Integer.toString(chapter));
        }
        for (String label : labels) {
            for (var child : screen.children()) {
                if (child instanceof AbstractWidget widget
                        && widget.getMessage().getString().equals(label)
                        && widget.getX() >= screen.width / 2) {
                    tabs.add(widget);
                }
            }
        }
        return tabs;
    }

    /** The section whose tab a page selects: 0 for the intro, a chapter's number, or -1 for the end pages, which have no tab. */
    private static int sectionOf(HandbookPage page) {
        return switch (page) {
            case HandbookPage.Cover cover -> 0;
            case HandbookPage.Slip slip -> 0;
            case HandbookPage.Letter letter -> 0;
            case HandbookPage.Contents contents -> 0;
            case HandbookPage.Controls controls -> 0;
            case HandbookPage.ChapterText text -> text.number();
            case HandbookPage.Chapter map -> map.number();
            case HandbookPage.Pencil pencil -> throw new AssertionError("these pages hold no pencil sheet");
            case HandbookPage.Appendix appendix -> -1;
            case HandbookPage.Contract contract -> -1;
        };
    }

    private static void everyPageShowsTheTabsAndSelectsItsSection() {
        HandbookScreen screen = screen(new HandbookMemory(), WIDTH, HEIGHT);
        List<HandbookPage> pages = pages();
        require(tabs(screen).size() == IDS.size() + 1, "a tab for the intro and one for each chapter");
        for (int index = 0; index < pages.size(); index++) {
            screen.goTo(index);
            List<AbstractWidget> tabs = tabs(screen);
            require(
                    tabs.stream().allMatch(tab -> tab.visible),
                    "page " + index + " (" + pages.get(index).getClass().getSimpleName() + ") shows every tab");
            require(
                    screen.section() == sectionOf(pages.get(index)),
                    "page " + index + " selects the tab of section " + sectionOf(pages.get(index)) + ", got "
                            + screen.section());
        }
    }

    private static void chapterTabsOpenTheirChapterMaps() {
        HandbookScreen screen = screen(new HandbookMemory(), WIDTH, HEIGHT);
        List<AbstractWidget> tabs = tabs(screen);
        for (int i = 1; i < tabs.size(); i++) {
            require(
                    tabs.get(i).getY() > tabs.get(i - 1).getY()
                            && tabs.get(i).getX() == tabs.get(0).getX(),
                    "the tabs step down the sheet's right edge");
        }
        // from any page, a tab jumps to its section: the intro tab to the cover, a chapter's tab to its map
        int pageCount = pages().size();
        for (int from = 0; from < pageCount; from++) {
            for (int tab = 0; tab < tabs.size(); tab++) {
                screen.goTo(from);
                AbstractWidget clicked = tabs.get(tab);
                screen.mouseClicked(mouse(clicked.getX() + 2, clicked.getY() + 2), false);
                int expected = tab == 0 ? 0 : mapPage(tab);
                require(
                        screen.page() == expected,
                        "from page " + from + ", tab " + clicked.getMessage().getString() + " opens page " + expected
                                + ", got " + screen.page());
            }
        }
    }

    private static void aClassifiedChapterKeepsItsRedactedPage() {
        HandbookScreen screen = mapScreen(new HandbookMemory(), 4);
        List<String> texts = texts(screen);
        require(
                texts.stream().noneMatch(text -> text.contains("Do thing 4") || text.contains("Title 4")),
                "a classified chapter shows no title and no step: " + texts);
        require(
                String.join(" ", texts)
                        .contains(Component.translatable("deepcharter.handbook.chapter.classified")
                                .getString()),
                "a classified chapter says so (the line wraps, so the drawn lines are joined): " + texts);
    }

    private static void pickingAStepMovesTheCard() {
        HandbookScreen screen = mapScreen(new HandbookMemory(), 1);
        click(screen, drawn(screen, done("Do thing 1.2")).box());
        List<String> texts = texts(screen);
        require(
                texts.contains(Component.translatable("deepcharter.handbook.quest.step", 2)
                        .getString()),
                "a click on step 2 puts its card up: " + texts);
        require(!texts.contains("0/4"), "step 2 needs no items, so the card shows none");
    }

    private static void theLinkOnTheCardOpensTheChapterPage() {
        HandbookScreen screen = mapScreen(new HandbookMemory(), 1);
        require(screen.page() == mapPage(1), "setup: on the map");
        String link = Component.translatable("deepcharter.handbook.quest.see_page", firstPage(1) + 1)
                .getString();
        click(screen, drawn(screen, link).box());
        require(
                screen.page() == firstPage(1) && firstPage(1) != mapPage(1),
                "the link goes to the chapter's first page " + firstPage(1) + ", got " + screen.page());
    }

    /** A click on an item of the index card opens the inventory with the parts catalogue looking that item up. */
    private static void clickingAnItemOpensItsRecipeInTheCatalogue(
            ClientGameTestContext context, TestSingleplayerContext singleplayer) {
        // An empty inventory, so the card counts 0/4 of the iron.
        singleplayer.getServer().runCommand("clear @p");
        context.runOnClient(client -> {
            HandbookScreen screen = mapScreen(new HandbookMemory(), 1);
            Box count = drawn(screen, "0/4").box();
            int iconSize = QuestMapTuning.current().itemIconSize();
            screen.mouseClicked(mouse(count.x() - iconSize / 2.0, count.y() + 2), false);
        });
        ClientWait.screen(context, InventoryScreen.class);
        context.waitTicks(3);
        CataloguePanel panel = CatalogueScene.panel(context);
        CatalogueState.Lookup lookup = context.computeOnClient(client -> panel.state().lookup());
        require(
                lookup != null && lookup.item() == Items.IRON_INGOT && lookup.mode() == CatalogueState.Mode.RECIPES,
                "a click on the iron icon opens the inventory at iron's recipes, got " + lookup);
        context.setScreen(() -> null);
        everyShippedItemStepShowsItsTargetsAndAClickOpensTheRecipe(context);
        anyOfStepIsOneSlotWhoseIconCyclesAndWhoseClickOpensTheShownRecipe(context);
    }

    /**
     * In the real game, with the shipped chapter 1: each step that asks for an item shows exactly the items it names,
     * with a count each (never the ingredients), and a click on the first of them opens that item's recipes in the
     * parts catalogue (#1260).
     */
    private static void everyShippedItemStepShowsItsTargetsAndAClickOpensTheRecipe(ClientGameTestContext context) {
        Map<Identifier, HandbookChapter> shipped = context.computeOnClient(client -> {
            Map<Identifier, HandbookChapter> chapters = new LinkedHashMap<>();
            HandbookChapters.all(Objects.requireNonNull(client.getConnection()).registryAccess()).stream()
                    .filter(entry -> !entry.key().identifier().getPath().equals("sample"))
                    .forEach(entry -> chapters.put(entry.key().identifier(), entry.value()));
            return chapters;
        });
        List<HandbookChapter.Entry> steps = shipped.values().iterator().next().directives();
        int clicked = 0;
        for (int i = 0; i < steps.size(); i++) {
            HandbookChapter.Entry step = steps.get(i);
            if (step.targets().isEmpty()) {
                continue;
            }
            Set<Identifier> before = new HashSet<>();
            steps.subList(0, i).forEach(earlier -> before.add(earlier.id()));
            context.runOnClient(client -> {
                List<HandbookPage> pages = HandbookPages.of(shipped, before);
                HandbookScreen screen =
                        new HandbookScreen(pages, id -> true, id -> {}, List.of(), new HandbookMemory());
                screen.init(WIDTH, HEIGHT);
                for (int index = 0; index < pages.size(); index++) {
                    if (pages.get(index) instanceof HandbookPage.Chapter map && map.number() == 1) {
                        screen.goTo(index);
                    }
                }
                settle(screen);
                List<Drawn> counts = DrawnText.of(screen).stream()
                        .filter(entry -> entry.text().matches("\\d+/\\d+"))
                        .toList();
                require(
                        counts.size() == step.targets().size(),
                        step.id() + " shows one counted item for each target, " + step.targets() + ", got "
                                + counts.stream().map(Drawn::text).toList());
                Box count = counts.getFirst().box();
                int iconSize = QuestMapTuning.current().itemIconSize();
                screen.mouseClicked(mouse(count.x() - iconSize / 2.0, count.y() + 2), false);
            });
            ClientWait.screen(context, InventoryScreen.class);
            context.waitTicks(3);
            CataloguePanel panel = CatalogueScene.panel(context);
            CatalogueState.Lookup lookup =
                    context.computeOnClient(client -> panel.state().lookup());
            List<Item> accepted = step.targets().getFirst().items();
            require(
                    lookup != null && accepted.contains(lookup.item()) && lookup.mode() == CatalogueState.Mode.RECIPES,
                    step.id() + ": a click on its first item opens the recipes of one of " + accepted + ", got "
                            + lookup);
            context.setScreen(() -> null);
            clicked++;
        }
        int chapterOne = HandbookChaptersOneToFiveTest.chapterOne().size();
        require(clicked >= chapterOne, "chapter 1 has at least " + chapterOne + " item steps, clicked " + clicked);
    }

    /**
     * A step that is done by any one of several items (#1260) is one slot with one shared count and an "any" cue, not a
     * row of items to collect. Its icon cycles through the alternatives once every {@code cycleTicks}, and a click
     * opens the recipes of the icon showing: the first click and the one a cycle later name different items, and the
     * third is back on the first.
     */
    private static void anyOfStepIsOneSlotWhoseIconCyclesAndWhoseClickOpensTheShownRecipe(
            ClientGameTestContext context) {
        Identifier chapterId = chapterId(1);
        HandbookChapter chapter = new HandbookChapter(
                1,
                Component.literal("Title 1"),
                List.of(new HandbookChapter.Entry(
                        directive(1, 1),
                        Component.literal("Make a light"),
                        List.of(new HandbookChapter.AnyOf(
                                List.of(
                                        new HandbookChapter.Choice(Identifier.parse("minecraft:torch"), false),
                                        new HandbookChapter.Choice(Identifier.parse("minecraft:lantern"), false)),
                                1)))));
        HandbookScreen[] shown = new HandbookScreen[1];
        context.runOnClient(client -> {
            List<HandbookPage> pages = HandbookPages.of(Map.of(chapterId, chapter), Set.of());
            HandbookScreen screen = new HandbookScreen(pages, id -> true, id -> {}, List.of(), new HandbookMemory());
            screen.init(WIDTH, HEIGHT);
            for (int index = 0; index < pages.size(); index++) {
                if (pages.get(index) instanceof HandbookPage.Chapter) {
                    screen.goTo(index);
                }
            }
            settle(screen);
            List<String> texts = texts(screen);
            require(
                    texts.stream().filter(text -> text.matches("\\d+/\\d+")).count() == 1 && texts.contains("0/1"),
                    "the any-of step shows one slot with one shared count: " + texts);
            require(
                    texts.contains(Component.translatable("deepcharter.handbook.quest.any")
                            .getString()),
                    "the slot carries the any cue: " + texts);
            shown[0] = screen;
        });
        List<Item> seen = new ArrayList<>();
        for (int click = 0; click < 3; click++) {
            context.runOnClient(client -> {
                Box count = drawn(shown[0], "0/1").box();
                int iconSize = QuestMapTuning.current().itemIconSize();
                shown[0].mouseClicked(mouse(count.x() - iconSize / 2.0, count.y() + 2), false);
            });
            ClientWait.screen(context, InventoryScreen.class);
            context.waitTicks(3);
            CataloguePanel panel = CatalogueScene.panel(context);
            CatalogueState.Lookup lookup =
                    context.computeOnClient(client -> panel.state().lookup());
            require(
                    lookup != null
                            && lookup.mode() == CatalogueState.Mode.RECIPES
                            && List.of(Items.TORCH, Items.LANTERN).contains(lookup.item()),
                    "a click opens the recipes of the torch or the lantern, got " + lookup);
            seen.add(Objects.requireNonNull(lookup, "checked above").item());
            context.setScreen(() -> null);
            context.runOnClient(client -> {
                for (int tick = 0; tick < QuestMapTuning.current().cycleTicks(); tick++) {
                    shown[0].tick();
                }
            });
        }
        require(
                seen.get(0) != seen.get(1) && seen.get(0) == seen.get(2),
                "the icon cycles between the two items, one cycle at a time, got " + seen);
    }

    /** On every page, a press, a drag and a scroll leave everything drawn where it was: nothing of the book pans, moves or zooms. */
    private static void nothingOnAHandbookPageMoves() {
        HandbookScreen screen = screen(new HandbookMemory(), WIDTH, HEIGHT);
        List<HandbookPage> pages = pages();
        for (int index = 0; index < pages.size(); index++) {
            screen.goTo(index);
            settle(screen);
            // a press on the first step of a map page, else on the sheet, left of its tabs
            double x = screen.width / 2.0 - 60;
            double y = screen.height / 2.0 - 30;
            if (pages.get(index) instanceof HandbookPage.Chapter map && map.visibility() == HandbookVisibility.FULL) {
                String first = "Do thing " + map.number() + ".1";
                int shown = index;
                Box step = DrawnText.of(screen).stream()
                        .filter(entry -> entry.text().contains(first))
                        .findFirst()
                        .orElseThrow(() -> new AssertionError("page " + shown + " draws no step '" + first + "'"))
                        .box();
                x = step.x() + 1;
                y = step.y() + 1;
            }
            screen.mouseClicked(mouse(x, y), false);
            List<Drawn> before = DrawnText.of(screen);
            require(!screen.mouseDragged(mouse(x + 7, y + 5), 7, 5), "page " + index + " takes a drag");
            screen.mouseReleased(mouse(x + 7, y + 5));
            require(!screen.mouseScrolled(x, y, 0, 1), "page " + index + " takes a scroll up");
            require(!screen.mouseScrolled(x, y, 0, -1), "page " + index + " takes a scroll down");
            require(
                    DrawnText.of(screen).equals(before),
                    "page " + index + " (" + pages.get(index).getClass().getSimpleName()
                            + ") moved under a drag or a scroll");
        }
    }

    private static void aFlowchartStepIsPickedByAClickAndNeverDragged() {
        HandbookScreen screen = mapScreen(new HandbookMemory(), 1);
        Box one = drawn(screen, done("Do thing 1.1")).box();
        Box two = drawn(screen, done("Do thing 1.2")).box();
        screen.mouseClicked(mouse(one.x() + 1, one.y() + 1), false);
        require(
                !screen.mouseDragged(mouse(one.x() + 7, one.y() + 5), 6, 4),
                "a drag that begins on a step is nobody's: the flowchart does not pan");
        screen.mouseReleased(mouse(one.x() + 7, one.y() + 5));
        require(
                drawn(screen, done("Do thing 1.1")).box().equals(one)
                        && drawn(screen, done("Do thing 1.2")).box().equals(two),
                "the steps stay where they are after a drag");
    }

    private static void theBookOpensWhereItWasLeft() {
        HandbookMemory memory = new HandbookMemory();
        HandbookScreen first = mapScreen(memory, 1);
        click(first, drawn(first, done("Do thing 1.2")).box());
        HandbookScreen second = screen(memory, WIDTH, HEIGHT);
        require(second.page() == mapPage(1), "the book opens on the page it was left on, got " + second.page());
        require(
                texts(second)
                        .contains(Component.translatable("deepcharter.handbook.quest.step", 2)
                                .getString()),
                "the book opens with the step picked as it was left: " + texts(second));
        require(screen(new HandbookMemory(), WIDTH, HEIGHT).page() == 0, "a book with no memory opens at the cover");
    }

    private static void theMapFitsTheDefaultWindowAndTheSmallest() {
        int[][] windows = {{WIDTH, HEIGHT}, {MIN_WIDTH, MIN_HEIGHT}};
        for (int[] window : windows) {
            for (int chapter = 1; chapter <= IDS.size(); chapter++) {
                HandbookScreen screen = screen(new HandbookMemory(), window[0], window[1]);
                screen.goTo(mapPage(chapter));
                settle(screen);
                for (Drawn drawn : DrawnText.of(screen)) {
                    require(
                            DrawnText.leavesScreen(screen, drawn).isEmpty(),
                            "chapter " + chapter + " at " + window[0] + " x " + window[1] + ": "
                                    + DrawnText.leavesScreen(screen, drawn).orElse(""));
                }
                List<AbstractWidget> tabs = tabs(screen);
                for (int i = 1; i < tabs.size(); i++) {
                    require(
                            tabs.get(i).getY() >= tabs.get(i - 1).getBottom(),
                            "tab " + tabs.get(i).getMessage().getString() + " overlaps the tab above it at " + window[0]
                                    + " x " + window[1]);
                }
                for (AbstractWidget tab : tabs) {
                    require(
                            tab.getX() >= 0 && tab.getRight() <= window[0] && tab.getBottom() <= window[1],
                            "chapter tab " + tab.getMessage().getString() + " is on the screen at " + window[0] + " x "
                                    + window[1]);
                }
            }
        }
    }

    private static void aStepIsToastedOnlyWhenItIsNews() {
        ClientHandbook.forget();
        try {
            Identifier a = directive(1, 1);
            Identifier b = directive(1, 2);
            require(
                    ClientHandbook.set(List.of(a)).isEmpty(),
                    "the first sync of a world is what was done before: no popup");
            require(ClientHandbook.set(List.of(a, b)).equals(List.of(b)), "a step done since the last sync is news");
            require(ClientHandbook.set(List.of(a, b)).isEmpty(), "the same set again is not news");
            List<Identifier> many = new ArrayList<>();
            for (int n = 1; n <= ClientHandbook.MOST_TOASTED_AT_ONCE + 2; n++) {
                many.add(directive(2, n));
            }
            require(ClientHandbook.set(many).isEmpty(), "a charter handing over its progress is not a deed: no popups");
            ClientHandbook.forget();
            require(ClientHandbook.completed().isEmpty(), "leaving the world forgets the progress");
            require(ClientHandbook.set(List.of(a)).isEmpty(), "and the next world's first sync is silent again");
        } finally {
            ClientHandbook.forget();
        }
    }

    /** Every directive of chapters before {@code chapter} is done, so that chapter is the current one. */
    private static Set<Identifier> doneBefore(Map<Identifier, HandbookChapter> chapters, int chapter) {
        Set<Identifier> done = new HashSet<>();
        int number = 0;
        for (HandbookChapter entry : chapters.values()) {
            if (++number < chapter) {
                entry.directives().forEach(step -> done.add(step.id()));
            }
        }
        return done;
    }

    /** The nine chapters with the step counts they ship with, under their real ids so their margin notes are real. */
    private static Map<Identifier, HandbookChapter> shippedLike() {
        List<String> ids = List.of(
                "welcome",
                "back_online",
                "meet_the_mole",
                "fuel_is_life",
                "every_sale_counts",
                "seeing_below",
                "staying_safe",
                "first_breach",
                "company_property");
        Map<Identifier, HandbookChapter> chapters = new LinkedHashMap<>();
        for (int chapter = 1; chapter <= ids.size(); chapter++) {
            List<HandbookChapter.Entry> entries = new ArrayList<>();
            for (int n = 1; n <= HandbookChaptersOneToFiveTest.shippedSteps(ids.get(chapter - 1)); n++) {
                entries.add(new HandbookChapter.Entry(
                        directive(chapter, n),
                        Component.literal("Do thing " + chapter + "." + n + " in a line long enough to need two lines"),
                        List.of()));
            }
            chapters.put(
                    Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, ids.get(chapter - 1)),
                    new HandbookChapter(chapter, Component.literal("Title " + chapter), entries));
        }
        return chapters;
    }

    /**
     * Whatever chapter the player is on, at the default window the whole map is on the sheet: the layout leaves no pixel short, the
     * stamp and every line of the margin note are drawn at full height (a string the scissor cuts is drawn shorter, or not at all), and
     * nothing sits under Back or Next.
     */
    private static void everyShippedMapFitsTheSheetWhole() {
        Map<Identifier, HandbookChapter> chapters = shippedLike();
        net.minecraft.client.gui.Font font = Minecraft.getInstance().font;
        for (int chapter = 1; chapter <= chapters.size(); chapter++) {
            List<HandbookPage> pages = HandbookPages.of(chapters, doneBefore(chapters, chapter));
            HandbookScreen screen = new HandbookScreen(pages, id -> true, id -> {}, List.of(), new HandbookMemory());
            screen.init(WIDTH, HEIGHT);
            for (int index = 0; index < pages.size(); index++) {
                if (pages.get(index) instanceof HandbookPage.Chapter map && map.number() == chapter) {
                    screen.goTo(index);
                }
            }
            settle(screen);
            String where = "chapter " + chapter + "'s map at " + WIDTH + " x " + HEIGHT;
            require(
                    screen.mapSpareHeight(chapter) >= 0,
                    where + " is " + -screen.mapSpareHeight(chapter) + " px too tall");
            List<Drawn> drawn = DrawnText.of(screen);
            String back =
                    Component.translatable("deepcharter.handbook.screen.back").getString();
            String next =
                    Component.translatable("deepcharter.handbook.screen.next").getString();
            List<AbstractWidget> buttons = new ArrayList<>();
            for (var child : screen.children()) {
                if (child instanceof AbstractWidget widget
                        && widget.visible
                        && (widget.getMessage().getString().equals(back)
                                || widget.getMessage().getString().equals(next))) {
                    buttons.add(widget);
                }
            }
            for (Drawn text : drawn) {
                require(
                        DrawnText.underWidget(text, buttons).isEmpty(),
                        where + ": " + DrawnText.underWidget(text, buttons).orElse(""));
            }
            Identifier id = Identifier.fromNamespaceAndPath(
                    DeepCharter.MOD_ID, chapterIds().get(chapter - 1));
            List<String> whole = new ArrayList<>();
            for (var line : font.split(
                    Component.translatable(HandbookScreen.marginKey(id)),
                    QuestMapTuning.current().noteWidth())) {
                whole.add(DrawnText.plain(line));
            }
            for (String line : whole) {
                require(
                        drawn.stream()
                                .anyMatch(entry ->
                                        entry.text().equals(line) && entry.box().height() >= font.lineHeight),
                        where + " draws the margin note line '" + line + "' whole: " + texts(screen));
            }
            List<Drawn> noteLines =
                    drawn.stream().filter(entry -> whole.contains(entry.text())).toList();
            String stampText = Component.translatable("deepcharter.handbook.chapter.stamp.classified")
                    .getString();
            for (Drawn line : noteLines) {
                for (Drawn other : drawn) {
                    if (other != line
                            && !noteLines.contains(other)
                            && !other.text().equals(stampText)) {
                        require(
                                !io.github.pkeppeler.deepcharter.test.support.ClientChecks.overlaps(
                                        line.box(), other.box()),
                                where + ": the margin note line '" + line.shortText() + "' overlaps '"
                                        + other.shortText() + "'");
                    }
                }
            }
            for (Drawn mark : drawn) {
                if (!mark.text().equals(stampText)) {
                    continue;
                }
                for (Drawn other : drawn) {
                    if (other != mark) {
                        require(
                                !stampFrameHits(font, mark.box(), stampText, other.box()),
                                where + ": the CLASSIFIED stamp frame, centred at " + mark.box()
                                        + ", overlaps the text '" + other.shortText() + "' at " + other.box());
                    }
                }
            }
            String stamp = Component.translatable("deepcharter.handbook.chapter.stamp.classified")
                    .getString();
            if (chapter + 1 < chapters.size()) {
                require(
                        drawn.stream()
                                .anyMatch(entry -> entry.text().equals(stamp)
                                        && entry.box().height() >= font.lineHeight),
                        where + " draws the CLASSIFIED stamp whole: " + texts(screen));
            }
        }
    }

    /**
     * Whether the double frame of a stamp whose turned lettering was drawn in {@code lettering} touches {@code other}. The frame
     * stands 5 px out from the lettering across and 3 up and down, turned about its centre by the tuned angle; it is tested as
     * the turned rectangle it is (separating axes), not as the box around it, whose empty corners are no part of the stamp.
     */
    private static boolean stampFrameHits(net.minecraft.client.gui.Font font, Box lettering, String text, Box other) {
        double halfWidth = font.width(Component.literal(text).withStyle(net.minecraft.ChatFormatting.BOLD)) / 2.0 + 5;
        double halfHeight = font.lineHeight / 2.0 + 3;
        double angle = Math.toRadians(QuestMapTuning.current().classifiedStampDegrees());
        double centerX = lettering.x() + lettering.width() / 2.0;
        double centerY = lettering.y() + lettering.height() / 2.0;
        double cos = Math.cos(angle);
        double sin = Math.sin(angle);
        double[][] axes = {{1, 0}, {0, 1}, {cos, sin}, {-sin, cos}};
        double[][] frame = new double[4][];
        double[][] box = {
            {other.x(), other.y()},
            {other.x() + other.width(), other.y()},
            {other.x() + other.width(), other.y() + other.height()},
            {other.x(), other.y() + other.height()}
        };
        int corner = 0;
        for (int sx = -1; sx <= 1; sx += 2) {
            for (int sy = -1; sy <= 1; sy += 2) {
                double dx = sx * halfWidth;
                double dy = sy * halfHeight;
                frame[corner++] = new double[] {centerX + dx * cos - dy * sin, centerY + dx * sin + dy * cos};
            }
        }
        for (double[] axis : axes) {
            double[] frameRange = project(frame, axis);
            double[] boxRange = project(box, axis);
            if (frameRange[1] <= boxRange[0] || boxRange[1] <= frameRange[0]) {
                return false;
            }
        }
        return true;
    }

    private static double[] project(double[][] points, double[] axis) {
        double min = Double.MAX_VALUE;
        double max = -Double.MAX_VALUE;
        for (double[] point : points) {
            double along = point[0] * axis[0] + point[1] * axis[1];
            min = Math.min(min, along);
            max = Math.max(max, along);
        }
        return new double[] {min, max};
    }

    private static List<String> chapterIds() {
        return shippedLike().keySet().stream().map(Identifier::getPath).toList();
    }

    /** The scanner step is done and its iron is gone from the pack: the card still shows every need met, not 0/4. */
    private static void aDoneStepNeedsNothingMore() {
        Set<Identifier> all = done();
        all.add(directive(1, FIRST_CHAPTER_STEPS));
        HandbookScreen screen = new HandbookScreen(
                HandbookPages.of(chapters(), all), id -> true, id -> {}, List.of(), new HandbookMemory());
        screen.init(WIDTH, HEIGHT);
        screen.goTo(mapPage(1));
        settle(screen);
        click(screen, drawn(screen, done("Do thing 1.6")).box());
        List<String> texts = texts(screen);
        require(texts.contains("4/4") && texts.contains("2/2"), "a done step's items show as met: " + texts);
        require(!texts.contains("0/4"), "a done step's items are not shown as missing: " + texts);
    }

    /** Chapter 2 unlocking its text pages puts pages before its map: the book reopens on the map, not on a page that moved there. */
    private static void theBookReopensOnTheSamePageWhenProgressAddsPages() {
        HandbookMemory memory = new HandbookMemory();
        Map<Identifier, HandbookChapter> chapters = chapters();
        List<HandbookPage> before = HandbookPages.of(chapters, done());
        HandbookScreen first = new HandbookScreen(before, id -> true, id -> {}, List.of(), memory);
        first.init(WIDTH, HEIGHT);
        int mapTwo = -1;
        for (int index = 0; index < before.size(); index++) {
            if (before.get(index) instanceof HandbookPage.Chapter map && map.number() == 2) {
                mapTwo = index;
            }
        }
        first.goTo(mapTwo);
        Set<Identifier> more = done();
        more.add(directive(1, FIRST_CHAPTER_STEPS));
        List<HandbookPage> after = HandbookPages.of(chapters, more);
        require(after.size() > before.size(), "setup: finishing chapter 1 adds text pages before chapter 2's map");
        HandbookScreen second = new HandbookScreen(after, id -> true, id -> {}, List.of(), memory);
        require(
                after.get(second.page()) instanceof HandbookPage.Chapter map && map.number() == 2,
                "the book reopens on chapter 2's map, page " + second.page() + " of " + after.size()
                        + ", not on the page" + " number it was at before");
    }

    /** The fills of {@code on} that {@code off} does not draw: what the mouse alone adds to a frame. */
    private static List<Fill> added(Frame on, Frame off) {
        return on.fills().stream().filter(fill -> !off.fills().contains(fill)).toList();
    }

    /**
     * #1413: the mouse over a step a click picks fills the step's box behind its name and asks for the pointing hand;
     * the mouse over a step of a chapter only previewed, which a click does nothing to, adds nothing.
     */
    private static void hoveringAStepLightsItAndAsksForTheHand() {
        HandbookScreen screen = mapScreen(new HandbookMemory(), 1);
        Box name = drawn(screen, done("Do thing 1.2")).box();
        Frame off = DrawnText.frame(screen, -1, -1);
        Frame on = DrawnText.frame(screen, name.x() + 1, name.y() + 1);
        require(
                added(on, off).stream().anyMatch(fill -> contains(fill.box(), name)),
                "the hovered step is filled behind its name " + name + ", added fills: " + added(on, off));
        require(on.cursor() == CursorTypes.POINTING_HAND, "the hovered step asks for the hand, got " + on.cursor());
        require(off.cursor() != CursorTypes.POINTING_HAND, "with the mouse away nothing asks for the hand");
        HandbookScreen preview = mapScreen(new HandbookMemory(), 2);
        Box previewName = drawn(preview, "Do thing 2.1").box();
        Frame previewOff = DrawnText.frame(preview, -1, -1);
        Frame previewOn = DrawnText.frame(preview, previewName.x() + 1, previewName.y() + 1);
        require(
                added(previewOn, previewOff).isEmpty(),
                "a previewed step is not lit under the mouse: " + added(previewOn, previewOff));
        require(
                previewOn.cursor() != CursorTypes.POINTING_HAND,
                "a previewed step does not ask for the hand, got " + previewOn.cursor());
    }

    private static boolean contains(Box outer, Box inner) {
        return outer.x() <= inner.x()
                && outer.y() <= inner.y()
                && outer.right() >= inner.right()
                && outer.bottom() >= inner.bottom();
    }

    /**
     * The inside of the box of the step whose first line is {@code name}: between its two sides, the nearest fills left
     * and right of the name that are one pixel wide and as tall as a step box.
     */
    private static Box stepInside(Frame frame, Drawn name) {
        Box text = name.box();
        int stepHeight = QuestMapTuning.current().stepHeight();
        List<Box> sides = frame.fills().stream()
                .map(Fill::box)
                .filter(box -> box.width() == 1
                        && box.height() == stepHeight
                        && box.y() <= text.y()
                        && box.bottom() >= text.bottom())
                .toList();
        Box left = sides.stream()
                .filter(box -> box.right() <= text.x())
                .max(Comparator.comparingInt(Box::x))
                .orElseThrow(() -> new AssertionError("no box side left of the step '" + name.text() + "'"));
        Box right = sides.stream()
                .filter(box -> box.x() >= text.right())
                .min(Comparator.comparingInt(Box::x))
                .orElseThrow(() -> new AssertionError("no box side right of the step '" + name.text() + "'"));
        return new Box(left.right(), left.y(), right.x() - left.right(), left.height());
    }

    /**
     * The step's click cue: the single pixels of ink a frame draws inside the box of the step whose first line is
     * {@code name}. Text is not a fill, and the box's sides and the loop round the current step are not inside it.
     */
    private static List<Fill> cueInk(Frame frame, Drawn name) {
        Box inside = stepInside(frame, name);
        return frame.fills().stream()
                .filter(fill -> fill.box().width() == 1 && fill.box().height() == 1 && contains(inside, fill.box()))
                .toList();
    }

    /**
     * #1413: every step of a chapter in full carries ink between its name and its box's right side, the chevron that
     * says a click opens it; no step of a previewed chapter does.
     */
    private static void everyStepAClickOpensCarriesTheCueAndNoOtherDoes() {
        HandbookScreen screen = mapScreen(new HandbookMemory(), 1);
        Frame frame = DrawnText.frame(screen, -1, -1);
        for (int n = 1; n <= FIRST_CHAPTER_STEPS; n++) {
            String step = n < FIRST_CHAPTER_STEPS ? done("Do thing 1." + n) : open("Do thing 1." + n);
            Drawn name = drawn(screen, step);
            require(!cueInk(frame, name).isEmpty(), "step 1." + n + " carries the click cue right of its name");
        }
        HandbookScreen preview = mapScreen(new HandbookMemory(), 2);
        Frame previewFrame = DrawnText.frame(preview, -1, -1);
        for (int n = 1; n <= 3; n++) {
            Drawn name = drawn(preview, "Do thing 2." + n);
            require(
                    cueInk(previewFrame, name).isEmpty(),
                    "previewed step 2." + n + " carries no click cue: " + cueInk(previewFrame, name));
        }
    }

    /** #1413: a click on the cue itself, not only on the name, picks the step and puts its card up. */
    private static void aClickOnTheCueStillPicksTheStep() {
        HandbookScreen screen = mapScreen(new HandbookMemory(), 1);
        String stepTwo =
                Component.translatable("deepcharter.handbook.quest.step", 2).getString();
        require(!texts(screen).contains(stepTwo), "setup: step 2's card is not up before the click: " + texts(screen));
        List<Fill> cue = cueInk(DrawnText.frame(screen, -1, -1), drawn(screen, done("Do thing 1.2")));
        require(!cue.isEmpty(), "step 1.2 carries the click cue");
        Box mark = cue.getFirst().box();
        screen.mouseClicked(mouse(mark.x(), mark.y()), false);
        screen.mouseReleased(mouse(mark.x(), mark.y()));
        require(texts(screen).contains(stepTwo), "a click on step 2's cue puts its card up: " + texts(screen));
    }

    /**
     * #1413: a step's name too long for its box is cut with dots short of the cue, never drawn over it. The name is of
     * the narrowest letter in one word, so its checkbox is a line of its own and the cut line runs within a letter
     * of the room it is given.
     */
    private static void aCutNameStopsShortOfTheCue() {
        Identifier chapterId = chapterId(1);
        HandbookChapter chapter = new HandbookChapter(
                1,
                Component.literal("Title 1"),
                List.of(new HandbookChapter.Entry(directive(1, 1), Component.literal("i".repeat(300)), List.of())));
        List<HandbookPage> pages = HandbookPages.of(Map.of(chapterId, chapter), Set.of());
        HandbookScreen screen = new HandbookScreen(pages, id -> true, id -> {}, List.of(), new HandbookMemory());
        screen.init(WIDTH, HEIGHT);
        for (int index = 0; index < pages.size(); index++) {
            if (pages.get(index) instanceof HandbookPage.Chapter) {
                screen.goTo(index);
            }
        }
        settle(screen);
        Frame frame = DrawnText.frame(screen, -1, -1);
        Drawn first = frame.text().stream()
                .filter(drawn -> drawn.text().equals(open("").strip()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("the long step is not drawn: " + texts(screen)));
        Box inside = stepInside(frame, first);
        List<Drawn> lines = frame.text().stream()
                .filter(drawn -> contains(inside, drawn.box()))
                .toList();
        require(
                lines.stream().anyMatch(line -> line.text().endsWith("...")),
                "the long name is cut with dots: " + lines);
        int cueLeft = cueInk(frame, first).stream()
                .mapToInt(fill -> fill.box().x())
                .min()
                .orElseThrow(() -> new AssertionError("the long step carries no click cue"));
        for (Drawn line : lines) {
            require(
                    line.box().right() <= cueLeft,
                    "the name's line '" + line.shortText() + "' ends at x "
                            + line.box().right() + ", over the cue at x " + cueLeft);
        }
    }
}
