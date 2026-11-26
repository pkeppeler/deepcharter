package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.ClientChecks.require;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.pkeppeler.deepcharter.client.handbook.HandbookControls;
import io.github.pkeppeler.deepcharter.client.handbook.HandbookKeys;
import io.github.pkeppeler.deepcharter.client.handbook.HandbookMemory;
import io.github.pkeppeler.deepcharter.client.handbook.HandbookPage;
import io.github.pkeppeler.deepcharter.client.handbook.HandbookScreen;
import io.github.pkeppeler.deepcharter.client.handbook.HandbookScreenTuning;
import io.github.pkeppeler.deepcharter.client.handbook.catalogue.CatalogueKeys;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.DrawnText;
import io.github.pkeppeler.deepcharter.test.support.DrawnText.Drawn;
import io.github.pkeppeler.deepcharter.test.support.ShippedHandbook;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Options;
import net.minecraft.client.input.KeyEvent;

/**
 * Client GameTest for the handbook's controls (#1260, #1414), on the shipped handbook with every chapter full. The
 * controls pages follow the contents. Every control line of the language file is drawn once in the whole book: a line
 * whose key names a chapter ({@code deepcharter.handbook.controls.<chapter path>.*}) on that chapter's pages, any other
 * on the controls pages, with each {@code %s} filled by a bound key in brackets. The Mole and scanner chapters have
 * such lines. A rebind changes the key a page names. Every controls sheet fits the default window and the smallest
 * GUI. Every key of the mod, and every vanilla key the pod's input path reads, is named on a sheet. A world is loaded
 * only for the synced chapter registry; the screens are built by hand and read from the text they draw.
 */
public class HandbookControlsClientTest implements FabricClientGameTest {
    private static final String CONTROLS = "deepcharter.handbook.controls.";
    /** The section of the book the controls pages make; a chapter's section is its id's path. */
    private static final String FRONT = "the controls pages";

    private static final int WIDTH = 427;
    private static final int HEIGHT = 240;
    private static final int SMALL_WIDTH = 213;
    private static final int SMALL_HEIGHT = 120;
    private static final Pattern COUNTER = Pattern.compile("(\\d+) / (\\d+)");

    /** A sheet the walk stopped on: the screen, the page the sheet is part of, and the text drawn. */
    private record Stop(HandbookScreen screen, HandbookPage page, List<Drawn> drawn) {}

    @Override
    public void runTest(ClientGameTestContext context) {
        ClientTestLog.start(this);
        // The world is only here for the chapter registry it syncs; the screens do not touch it.
        try (TestSingleplayerContext _ = context.worldBuilder().create()) {
            ClientWait.until(
                    context,
                    "the player and the synced chapters",
                    client -> client.player != null && client.getConnection() != null);
            context.runOnClient(client -> {
                List<HandbookPage> pages = ShippedHandbook.everyChapterFull(client);
                theControlsPagesFollowTheContents(pages);
                everyControlLineIsDrawnOnceWhereItsKeySays(pages);
                theMoleAndScannerChaptersHaveControlLines(pages);
                aRebindChangesWhatThePageShows(pages, client.options);
                everyControlsSheetFitsTheDefaultWindowAndTheSmallestGui(pages);
                everyModKeyAndEveryPodInputKeyIsOnASheet(client.options);
            });
        }
    }

    private static void theControlsPagesFollowTheContents(List<HandbookPage> pages) {
        int contents = -1;
        for (int index = 0; index < pages.size(); index++) {
            if (pages.get(index) instanceof HandbookPage.Contents) {
                contents = index;
            }
        }
        for (int part = 1; part <= HandbookControls.sheets(); part++) {
            require(
                    pages.get(contents + part) instanceof HandbookPage.Controls controls
                            && controls.part() == part
                            && controls.parts() == HandbookControls.sheets(),
                    "controls page " + part + " follows the contents, got " + pages.get(contents + part));
        }
        require(
                HandbookPage.chapterNumber(pages.get(contents + HandbookControls.sheets() + 1)) == 1,
                "chapter 1 follows the controls pages");
    }

    /**
     * Each control line of the language file (a key under {@code deepcharter.handbook.controls.} that is not a title or
     * the page label) is drawn exactly once in the book, with every {@code %s} filled by a key in brackets, and in the
     * section its key names. So a line dropped from the sheets, or left on the controls pages after it moved, fails.
     */
    private static void everyControlLineIsDrawnOnceWhereItsKeySays(List<HandbookPage> pages) {
        Map<String, String> sections = sectionTexts(pages, walk(pages, WIDTH, HEIGHT));
        Map<String, String> lines = controlLines();
        require(!lines.isEmpty(), "the language file has control lines");
        for (Map.Entry<String, String> line : lines.entrySet()) {
            String section = sectionOf(line.getKey(), sections);
            Pattern drawn = filled(line.getValue());
            int count = 0;
            for (String text : sections.values()) {
                Matcher matcher = drawn.matcher(text);
                while (matcher.find()) {
                    count++;
                }
            }
            require(
                    count == 1,
                    line.getKey() + " ('" + line.getValue() + "') should be drawn once in the book, was " + count);
            require(
                    drawn.matcher(sections.getOrDefault(section, "")).find(),
                    line.getKey() + " should be drawn in " + section + ": " + sections.get(section));
        }
    }

    /** The Mole chapter and the scanner chapter each have control lines of their own. */
    private static void theMoleAndScannerChaptersHaveControlLines(List<HandbookPage> pages) {
        Set<String> chapters = sectionTexts(pages, List.of()).keySet();
        for (String chapter : List.of("meet_the_mole", "seeing_below")) {
            require(chapters.contains(chapter), "setup: the shipped handbook has the chapter " + chapter);
            require(
                    controlLines().keySet().stream().anyMatch(key -> key.startsWith(CONTROLS + chapter + ".")),
                    "the " + chapter + " chapter has control lines (" + CONTROLS + chapter + ".*)");
        }
    }

    /**
     * The handbook's own key, a mod key of the catalogue and two vanilla keys of the Mole are each rebound: the section
     * that names them shows the new key, not the old.
     */
    private static void aRebindChangesWhatThePageShows(List<HandbookPage> pages, Options options) {
        rebind(pages, HandbookKeys.OPEN, InputConstants.KEY_J, FRONT, "[H]", "[J]");
        rebind(pages, CatalogueKeys.SPEC_SHEET, InputConstants.KEY_K, FRONT, "[J]", "[K]");
        rebind(pages, options.keyUp, InputConstants.KEY_I, "meet_the_mole", "[W]", "[I]");
        rebind(pages, options.keySprint, InputConstants.KEY_O, "meet_the_mole", "[Left Control]", "[O]");
    }

    private static void rebind(
            List<HandbookPage> pages, KeyMapping mapping, int newKey, String section, String before, String after) {
        try {
            String shown = sectionTexts(pages, walk(pages, WIDTH, HEIGHT)).getOrDefault(section, "");
            require(shown.contains(before), mapping.getName() + " shows as " + before + " in " + section + ": " + shown);
            mapping.setKey(InputConstants.Type.KEYBOARD.getOrCreate(newKey));
            KeyMapping.resetMapping();
            String rebound = sectionTexts(pages, walk(pages, WIDTH, HEIGHT)).getOrDefault(section, "");
            require(
                    rebound.contains(after),
                    mapping.getName() + " shows as " + after + " in " + section + " once rebound: " + rebound);
            require(
                    !rebound.contains(before),
                    mapping.getName() + " no longer shows as " + before + " in " + section + " once rebound: "
                            + rebound);
        } finally {
            mapping.setKey(mapping.getDefaultKey());
            KeyMapping.resetMapping();
        }
    }

    /**
     * At the default window and at the smallest real GUI, 213 x 120 (854 x 480 at scale 4), every line of every sheet
     * of controls, on the controls pages or in a chapter, is on the screen.
     */
    private static void everyControlsSheetFitsTheDefaultWindowAndTheSmallestGui(List<HandbookPage> pages) {
        int[][] windows = {{WIDTH, HEIGHT}, {SMALL_WIDTH, SMALL_HEIGHT}};
        for (int[] window : windows) {
            for (Stop stop : walk(pages, window[0], window[1])) {
                if (!isControls(stop.page())) {
                    continue;
                }
                for (Drawn drawn : stop.drawn()) {
                    require(
                            DrawnText.leavesScreen(stop.screen(), drawn).isEmpty(),
                            stop.page() + " at " + window[0] + " x " + window[1] + ": "
                                    + DrawnText.leavesScreen(stop.screen(), drawn)
                                            .orElse(""));
                }
            }
        }
    }

    /** A page of controls: one of the controls pages, or a chapter's page that is neither its text nor its map. */
    private static boolean isControls(HandbookPage page) {
        return page instanceof HandbookPage.Controls
                || (HandbookPage.chapterNumber(page) > 0
                        && !(page instanceof HandbookPage.ChapterText)
                        && !(page instanceof HandbookPage.Chapter));
    }

    /**
     * Every key of the mod, and every vanilla key the pod's input path reads (driving, climbing and drilling down), is
     * named on some sheet, so a new key cannot go unlisted.
     */
    private static void everyModKeyAndEveryPodInputKeyIsOnASheet(Options options) {
        Set<KeyMapping> named = HandbookControls.mappings(options);
        for (KeyMapping mapping : options.keyMappings) {
            if (mapping.getCategory().equals(HandbookKeys.OPEN.getCategory())) {
                require(
                        named.contains(mapping),
                        mapping.getName() + " is a key of the mod but is on no controls sheet");
            }
        }
        for (KeyMapping mapping : List.of(
                options.keyUp,
                options.keyDown,
                options.keyLeft,
                options.keyRight,
                options.keyJump,
                options.keySprint)) {
            require(named.contains(mapping), mapping.getName() + " drives the pod but is on no controls sheet");
        }
    }

    /** The control lines of the language file, key to text: every key under {@link #CONTROLS} but titles and the label. */
    private static Map<String, String> controlLines() {
        Map<String, String> lines = new HashMap<>();
        HandbookChaptersOneToFiveTest.lang().entrySet().stream()
                .filter(entry -> entry.getKey().startsWith(CONTROLS)
                        && !entry.getKey().endsWith(".title")
                        && !entry.getKey().equals(CONTROLS + "label"))
                .forEach(entry -> lines.put(entry.getKey(), entry.getValue().getAsString()));
        return lines;
    }

    /** Where a control line belongs: the chapter its key's sheet is named for, or else the controls pages. */
    private static String sectionOf(String key, Map<String, String> sections) {
        String sheet = key.substring(CONTROLS.length(), key.indexOf('.', CONTROLS.length()));
        return sections.containsKey(sheet) ? sheet : FRONT;
    }

    /** {@code template} as drawn: each {@code %s} a bound key's name in brackets, any run of spaces one space. */
    private static Pattern filled(String template) {
        List<String> parts = new ArrayList<>();
        for (String part : normal(template).split("%s", -1)) {
            parts.add(Pattern.quote(part));
        }
        return Pattern.compile(String.join("\\[[^\\]]+\\]", parts));
    }

    /**
     * The text drawn in each section, sheets joined in order: {@link #FRONT} for the controls pages, a chapter's id
     * path for its pages. Every chapter of {@code pages} has an entry, empty if {@code stops} drew nothing of it.
     */
    private static Map<String, String> sectionTexts(List<HandbookPage> pages, List<Stop> stops) {
        Map<Integer, String> chapters = new HashMap<>();
        for (HandbookPage page : pages) {
            if (page instanceof HandbookPage.Chapter chapter) {
                chapters.put(chapter.number(), chapter.id().getPath());
            }
        }
        Map<String, List<String>> drawnIn = new HashMap<>();
        chapters.values().forEach(path -> drawnIn.put(path, new ArrayList<>()));
        for (Stop stop : stops) {
            String section = stop.page() instanceof HandbookPage.Controls
                    ? FRONT
                    : chapters.get(HandbookPage.chapterNumber(stop.page()));
            if (section == null) {
                continue;
            }
            stop.drawn().stream()
                    .map(Drawn::text)
                    .filter(text -> !COUNTER.matcher(text).matches())
                    .forEach(drawnIn.computeIfAbsent(section, _ -> new ArrayList<>())::add);
        }
        Map<String, String> texts = new HashMap<>();
        drawnIn.forEach((section, drawn) -> texts.put(section, normal(String.join(" ", drawn))));
        return texts;
    }

    /** From the cover, turns every sheet with the right arrow to the last, stopping on each. */
    private static List<Stop> walk(List<HandbookPage> pages, int width, int height) {
        HandbookScreen screen = new HandbookScreen(pages, id -> true, id -> {}, List.of(), new HandbookMemory());
        screen.init(width, height);
        List<Stop> stops = new ArrayList<>();
        while (true) {
            settle(screen);
            List<Drawn> drawn = DrawnText.of(screen);
            stops.add(new Stop(screen, pages.get(screen.page()), drawn));
            int[] counter = counter(drawn);
            require(stops.size() == counter[0], "the walk stops on every sheet once, at sheet " + counter[0]);
            if (counter[0] == counter[1]) {
                return stops;
            }
            require(screen.keyPressed(new KeyEvent(InputConstants.KEY_RIGHT, 0, 0)), "the right arrow is taken");
        }
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
}
