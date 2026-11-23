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
            expectTheColumnIsExplicitWhenTheColumnsShareAnX();
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
        return StepToast.place(GUI_WIDTH, GUI_HEIGHT, width(), height, avoid).map(StepToast.Placement::slip);
    }

    private static boolean right(int height, List<Rect> avoid) {
        return StepToast.place(GUI_WIDTH, GUI_HEIGHT, width(), height, avoid)
                .orElseThrow()
                .right();
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
        require(right(tall, List.of(leftBand())), "and it says it is in the right column");
        require(!right(40, List.of()), "a slip in the left column says so");
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

    /** On a GUI as wide as a slip and its margins, both columns start at the same x, so the column cannot be read off the rectangle. */
    private static void expectTheColumnIsExplicitWhenTheColumnsShareAnX() {
        int guiWidth = width() + 2 * margin();
        Rect band = new Rect("band", 0, 0, guiWidth, 30);
        StepToast.Placement placed =
                StepToast.place(guiWidth, GUI_HEIGHT, width(), 40, List.of()).orElseThrow();
        require(!placed.right(), "with both columns at one x the left is still the left: " + placed);
        require(
                StepToast.place(guiWidth, GUI_HEIGHT, width(), 40, List.of(band))
                                .orElseThrow()
                                .slip()
                                .y()
                        >= band.bottom(),
                "a band over the top pushes the slip under it");
    }

    private static void expectNowhereWhenNeitherColumnHasRoom() {
        Rect wall = new Rect("wall", 0, LEFT_BAND_TOP, GUI_WIDTH, LEFT_BAND_HEIGHT);
        require(
                place(LEFT_BAND_TOP, List.of(wall)).isEmpty(),
                "a band across both columns leaves no room for a tall slip");
    }
}
