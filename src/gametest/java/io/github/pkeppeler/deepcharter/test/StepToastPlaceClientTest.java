package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.ClientChecks.require;

import io.github.pkeppeler.deepcharter.client.handbook.QuestMapTuning;
import io.github.pkeppeler.deepcharter.client.handbook.StepToast;
import io.github.pkeppeler.deepcharter.client.ui.HudKeepClear.Rect;
import java.util.List;
import java.util.Optional;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

/**
 * Where {@link StepToast#place} puts a slip, on made-up keep-clear rectangles and no world: the left column first, the right column
 * when the left has no room, and nowhere when neither has.
 */
public class StepToastPlaceClientTest implements FabricClientGameTest {
    private static final int GUI_WIDTH = 427;
    private static final int GUI_HEIGHT = 240;
    private static final int LEFT_BAND_TOP = 69;
    private static final int LEFT_BAND_HEIGHT = 106;

    @Override
    public void runTest(ClientGameTestContext context) {
        ClientTestLog.start(this);
        context.runOnClient(client -> {
            expectTheLeftColumnComesFirst();
            expectTheRightColumnWhenTheLeftHasNoRoom();
            expectTheLeftColumnEvenWhenTheRightIsHigher();
            expectNowhereWhenNeitherColumnHasRoom();
        });
    }

    private static int width() {
        return QuestMapTuning.current().toastWidth();
    }

    private static int margin() {
        return QuestMapTuning.current().toastMargin();
    }

    private static int rightX() {
        return GUI_WIDTH - margin() - width();
    }

    private static Optional<Rect> place(int height, List<Rect> avoid) {
        return StepToast.place(GUI_WIDTH, GUI_HEIGHT, width(), height, avoid);
    }

    /** A band across the left column only, like the sonar scope's. */
    private static Rect leftBand() {
        return new Rect("left band", 0, LEFT_BAND_TOP, width() + 2 * margin(), LEFT_BAND_HEIGHT);
    }

    private static void expectTheLeftColumnComesFirst() {
        Rect slip = place(40, List.of()).orElseThrow();
        require(
                slip.x() == margin() && slip.y() == margin(),
                "with nothing to avoid the slip is top left, not " + slip);
        Rect clear = place(40, List.of(leftBand())).orElseThrow();
        require(clear.x() == margin(), "a slip that fits above the left band stays in the left column: " + clear);
    }

    private static void expectTheRightColumnWhenTheLeftHasNoRoom() {
        int tall = LEFT_BAND_TOP; // above the band 63 px are left, and below it fewer: no room in the left column
        Rect slip = place(tall, List.of(leftBand()))
                .orElseThrow(
                        () -> new AssertionError("a slip too tall for the left column's gaps has room on the right"));
        require(slip.x() == rightX() && slip.y() == margin(), "it is top right, against the margin, not " + slip);
        require(!slip.overlaps(leftBand()), "and clear of the band");
    }

    private static void expectTheLeftColumnEvenWhenTheRightIsHigher() {
        // Nothing fits above this band, but 60 px fit under it, so the right column's top would be higher than the
        // left's place.
        Rect band = new Rect("high band", 0, 40, width() + 2 * margin(), 100);
        Rect slip = place(60, List.of(band)).orElseThrow();
        require(
                slip.x() == margin() && slip.y() >= band.bottom(),
                "a slip that fits under the band takes the left column, though the right is higher: " + slip);
    }

    private static void expectNowhereWhenNeitherColumnHasRoom() {
        Rect wall = new Rect("wall", 0, LEFT_BAND_TOP, GUI_WIDTH, LEFT_BAND_HEIGHT);
        require(
                place(LEFT_BAND_TOP, List.of(wall)).isEmpty(),
                "a band across both columns leaves no room for a tall slip");
    }
}
