package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.ClientChecks.require;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.client.handbook.HandbookControls;
import io.github.pkeppeler.deepcharter.client.handbook.HandbookKeys;
import io.github.pkeppeler.deepcharter.client.handbook.HandbookMemory;
import io.github.pkeppeler.deepcharter.client.handbook.HandbookPage;
import io.github.pkeppeler.deepcharter.client.handbook.HandbookPages;
import io.github.pkeppeler.deepcharter.client.handbook.HandbookScreen;
import io.github.pkeppeler.deepcharter.client.handbook.HandbookScreenTuning;
import io.github.pkeppeler.deepcharter.handbook.HandbookChapter;
import io.github.pkeppeler.deepcharter.test.support.DrawnText;
import io.github.pkeppeler.deepcharter.test.support.DrawnText.Drawn;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Options;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * Client GameTest for #1260's controls pages: they follow the first contents page, every key a line names is the player's binding
 * read at draw time (a rebind changes what the page shows), and each page fits the default window.
 */
public class HandbookControlsClientTest implements FabricClientGameTest {
    private static final int WIDTH = 427;
    private static final int HEIGHT = 240;

    @Override
    public void runTest(ClientGameTestContext context) {
        ClientTestLog.start(this);
        context.runOnClient(client -> {
            theControlsPagesFollowTheContents();
            aRebindChangesWhatThePageShows(client.options);
            everyControlsPageFitsTheDefaultWindow();
        });
    }

    private static List<HandbookPage> pages() {
        Map<Identifier, HandbookChapter> chapters = new LinkedHashMap<>();
        Identifier id = Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "welcome");
        chapters.put(
                id,
                new HandbookChapter(
                        1,
                        Component.literal("Title 1"),
                        List.of(new HandbookChapter.Entry(
                                Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "handbook/test/d1"),
                                Component.literal("Do thing"),
                                List.of()))));
        return HandbookPages.of(chapters, Set.of());
    }

    private static int controlsPage(int part) {
        List<HandbookPage> pages = pages();
        for (int index = 0; index < pages.size(); index++) {
            if (pages.get(index) instanceof HandbookPage.Controls controls && controls.part() == part) {
                return index;
            }
        }
        throw new AssertionError("no controls page " + part);
    }

    private static HandbookScreen screenOn(int part, int width, int height) {
        HandbookScreen screen = new HandbookScreen(pages(), id -> true, id -> {}, List.of(), new HandbookMemory());
        screen.init(width, height);
        screen.goTo(controlsPage(part));
        for (int tick = 0; tick < HandbookScreenTuning.current().flipTicks(); tick++) {
            screen.tick();
        }
        return screen;
    }

    private static List<String> texts(HandbookScreen screen) {
        return DrawnText.of(screen).stream().map(Drawn::text).toList();
    }

    private static void theControlsPagesFollowTheContents() {
        List<HandbookPage> pages = pages();
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
                pages.get(contents + HandbookControls.sheets() + 1) instanceof HandbookPage.ChapterText
                        || pages.get(contents + HandbookControls.sheets() + 1) instanceof HandbookPage.Chapter,
                "chapter 1 follows the controls pages");
    }

    /** The handbook's own key, a mod key of the catalogue and a vanilla key are each rebound: the page names the new key, not the old. */
    private static void aRebindChangesWhatThePageShows(Options options) {
        rebind(HandbookKeys.OPEN, InputConstants.KEY_J, 1, "[H]", "[J]");
        rebind(options.keyUp, InputConstants.KEY_I, controlsPartOf("pod"), "[W]", "[I]");
    }

    private static int controlsPartOf(String sheet) {
        for (int part = 1; part <= HandbookControls.sheets(); part++) {
            if (HandbookControls.titleKey(part).contains("." + sheet + ".")) {
                return part;
            }
        }
        throw new AssertionError("no controls sheet " + sheet);
    }

    private static void rebind(KeyMapping mapping, int newKey, int part, String before, String after) {
        try {
            List<String> shown = texts(screenOn(part, WIDTH, HEIGHT));
            require(
                    shown.stream().anyMatch(text -> text.contains(before)),
                    mapping.getName() + " shows as " + before + " by default: " + shown);
            mapping.setKey(InputConstants.Type.KEYBOARD.getOrCreate(newKey));
            KeyMapping.resetMapping();
            List<String> rebound = texts(screenOn(part, WIDTH, HEIGHT));
            require(
                    rebound.stream().anyMatch(text -> text.contains(after)),
                    mapping.getName() + " shows as " + after + " once rebound: " + rebound);
            require(
                    rebound.stream().noneMatch(text -> text.contains(before)),
                    mapping.getName() + " no longer shows as " + before + " once rebound: " + rebound);
        } finally {
            mapping.setKey(mapping.getDefaultKey());
            KeyMapping.resetMapping();
        }
    }

    /** At the default window every line of every page is on the screen. */
    private static void everyControlsPageFitsTheDefaultWindow() {
        for (int part = 1; part <= HandbookControls.sheets(); part++) {
            HandbookScreen screen = screenOn(part, WIDTH, HEIGHT);
            for (Drawn drawn : DrawnText.of(screen)) {
                require(
                        DrawnText.leavesScreen(screen, drawn).isEmpty(),
                        "controls page " + part + ": "
                                + DrawnText.leavesScreen(screen, drawn).orElse(""));
            }
        }
    }
}
