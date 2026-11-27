package io.github.pkeppeler.deepcharter.test.evidence;

import io.github.pkeppeler.deepcharter.client.handbook.HandbookMemory;
import io.github.pkeppeler.deepcharter.client.handbook.HandbookPage;
import io.github.pkeppeler.deepcharter.client.handbook.HandbookScreen;
import io.github.pkeppeler.deepcharter.client.handbook.HandbookScreenTuning;
import io.github.pkeppeler.deepcharter.test.support.ClientChecks.Box;
import io.github.pkeppeler.deepcharter.test.support.ClientCursor;
import io.github.pkeppeler.deepcharter.test.support.ClientSession;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.DrawnText;
import io.github.pkeppeler.deepcharter.test.support.ShippedHandbook;
import java.util.Comparator;
import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.network.chat.Component;

/**
 * Evidence scenario "handbook-task-cue" for #1413: a new player's handbook, open on chapter 1's map in the default
 * window, with the mouse away and then on the second step. The steps a click opens carry a chevron and light under the
 * mouse; chapter 2's map, only previewed, carries neither. The screenshot does not show the system cursor, so the
 * pointing hand is not in the stills.
 */
public class HandbookTaskCueScenario extends EvidenceScenario {
    private static final int GUI_SCALE = 2;
    private static final int HOLD_FRAMES = 3;
    /** Where the mouse rests, in GUI pixels, when it is off the steps: the screen's top left corner. */
    private static final int AWAY = 0;
    /** The close-up of the hovered step: a cut this wide and tall, in GUI pixels, with the step a quarter in. */
    private static final int ZOOM_WIDTH = 220;

    private static final int ZOOM_HEIGHT = 60;

    @Override
    protected String name() {
        return "handbook-task-cue";
    }

    @Override
    protected void run(ClientGameTestContext context) {
        try (TestSingleplayerContext _ = context.worldBuilder().create()) {
            ClientWait.until(context, "the player", client -> client.player != null && client.getConnection() != null);
            context.runOnClient(client -> client.options.guiScale().set(GUI_SCALE));
            HandbookScreen screen = open(context, 1);
            ClientCursor.moveTo(context, AWAY, AWAY);
            hold(context);
            screenshot(context, "chapter-1-map");

            String open = Component.translatable("deepcharter.handbook.chapter.directive.open", "")
                    .getString();
            Box step = context.computeOnClient(client -> DrawnText.of(screen).stream()
                    .filter(drawn -> drawn.text().startsWith(open))
                    .map(DrawnText.Drawn::box)
                    .sorted(Comparator.comparingInt(Box::y).thenComparingInt(Box::x))
                    .skip(1)
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("chapter 1's map draws fewer than two open steps")));
            ClientCursor.moveTo(context, step.x() + step.width() / 2.0, step.y() + step.height() / 2.0);
            hold(context);
            screenshot(context, "chapter-1-map-hover");
            zoomStill(
                    context,
                    "chapter-1-map-hover",
                    "chapter-1-map-hover-zoom",
                    Math.max(0, step.x() - ZOOM_WIDTH / 4),
                    Math.max(0, step.y() - ZOOM_HEIGHT / 4),
                    ZOOM_WIDTH,
                    ZOOM_HEIGHT);

            open(context, 2);
            ClientCursor.moveTo(context, AWAY, AWAY);
            hold(context);
            screenshot(context, "chapter-2-preview");
            context.setScreen(() -> null);
        }
    }

    /** Opens the handbook, as the shipped chapters and the synced progress make it, on the map of {@code chapter}. */
    private static HandbookScreen open(ClientGameTestContext context, int chapter) {
        context.setScreen(() -> new HandbookScreen(
                ShippedHandbook.pages(net.minecraft.client.Minecraft.getInstance()),
                id -> true,
                id -> {},
                List.of(),
                new HandbookMemory()));
        ClientWait.screen(context, HandbookScreen.class);
        HandbookScreen screen = context.computeOnClient(client -> ClientSession.screen(client, HandbookScreen.class));
        context.runOnClient(client -> {
            List<HandbookPage> pages = ShippedHandbook.pages(client);
            for (int index = 0; index < pages.size(); index++) {
                if (pages.get(index) instanceof HandbookPage.Chapter map && map.number() == chapter) {
                    screen.goTo(index);
                    return;
                }
            }
            throw new AssertionError("the handbook has no map of chapter " + chapter);
        });
        context.waitTicks(HandbookScreenTuning.current().flipTicks() + 2);
        return screen;
    }

    private void hold(ClientGameTestContext context) {
        for (int frame = 0; frame < HOLD_FRAMES; frame++) {
            frame(context);
        }
    }
}
