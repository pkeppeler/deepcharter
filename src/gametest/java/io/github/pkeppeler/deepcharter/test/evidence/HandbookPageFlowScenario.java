package io.github.pkeppeler.deepcharter.test.evidence;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.pkeppeler.deepcharter.client.handbook.HandbookPage;
import io.github.pkeppeler.deepcharter.client.handbook.HandbookScreen;
import io.github.pkeppeler.deepcharter.client.handbook.HandbookScreenTuning;
import io.github.pkeppeler.deepcharter.test.support.ClientSession;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.ShippedHandbook;
import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.input.KeyEvent;

/**
 * Evidence scenario "handbook-page-flow" for #1429: the text of chapters 4 and 5 of the shipped handbook, every chapter full, in the default
 * window, turned with Next from the chapter's first text page to its map. Their text ran under Back and Next; it now runs on to the next
 * sheet.
 */
public class HandbookPageFlowScenario extends EvidenceScenario {
    private static final int GUI_SCALE = 2;
    private static final int HOLD_FRAMES = 3;
    /** The chapters whose text ran off the sheet in the default window. */
    private static final List<Integer> CHAPTERS = List.of(4, 5);
    /** More turns than any chapter's text takes; past it, Next is not reaching the chapter's map. */
    private static final int MAX_TURNS = 20;

    @Override
    protected String name() {
        return "handbook-page-flow";
    }

    @Override
    protected void run(ClientGameTestContext context) {
        try (TestSingleplayerContext _ = context.worldBuilder().create()) {
            ClientWait.until(context, "the player", client -> client.player != null && client.getConnection() != null);
            context.runOnClient(client -> client.options.guiScale().set(GUI_SCALE));
            List<HandbookPage> pages = context.computeOnClient(ShippedHandbook::everyChapterFull);
            context.setScreen(() -> new HandbookScreen(pages, id -> true, id -> {}, List.of()));
            ClientWait.screen(context, HandbookScreen.class);
            HandbookScreen screen =
                    context.computeOnClient(client -> ClientSession.screen(client, HandbookScreen.class));
            for (int chapter : CHAPTERS) {
                int first = firstTextPage(pages, chapter);
                context.runOnClient(client -> screen.goTo(first));
                for (int turn = 1; ; turn++) {
                    if (turn > MAX_TURNS) {
                        throw new AssertionError("Next never reaches chapter " + chapter + "'s map");
                    }
                    context.waitTicks(HandbookScreenTuning.current().flipTicks());
                    for (int frame = 0; frame < HOLD_FRAMES; frame++) {
                        frame(context);
                    }
                    if (pages.get(context.computeOnClient(client -> screen.page())) instanceof HandbookPage.Chapter) {
                        break;
                    }
                    screenshot(context, String.format("chapter-%d-turn-%02d", chapter, turn));
                    context.runOnClient(client -> screen.keyPressed(new KeyEvent(InputConstants.KEY_RIGHT, 0, 0)));
                }
            }
            context.setScreen(() -> null);
        }
    }

    private static int firstTextPage(List<HandbookPage> pages, int chapter) {
        for (int index = 0; index < pages.size(); index++) {
            if (pages.get(index) instanceof HandbookPage.ChapterText text && text.number() == chapter) {
                return index;
            }
        }
        throw new AssertionError("chapter " + chapter + " has no text page");
    }
}
