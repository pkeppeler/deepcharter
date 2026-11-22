package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.ClientChecks.require;

import io.github.pkeppeler.deepcharter.client.charter.ClientCharter;
import io.github.pkeppeler.deepcharter.client.theme.PanelLook;
import io.github.pkeppeler.deepcharter.client.ui.CrtTuning;
import io.github.pkeppeler.deepcharter.test.support.ClientChecks.Box;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.DrawnText;
import io.github.pkeppeler.deepcharter.test.support.TerminalPanelScene;
import io.github.pkeppeler.deepcharter.test.support.TerminalPanelScene.Scene;
import io.github.pkeppeler.deepcharter.test.support.TerminalPanelScreens;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import javax.imageio.ImageIO;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import org.jspecify.annotations.Nullable;

/**
 * Client GameTest for #1012: the glass of a CRT terminal's corner must not dim the first stem of a title that starts near the top-left
 * corner. On each of the terminal screens it takes a screenshot, finds the title (the first string the screen draws, which must sit at the
 * content's top-left corner) and compares the topmost lit pixel of its first stem, where the glass's shading is heaviest, with the brightest
 * pixel of the title in rows the scanlines treat alike, so a scanline does not read as dimming.
 *
 * <p>Expected, from the sprite's alpha table and not yet measured on a run: the stem's top pixel reads about 0.4 to 0.5 of the title's
 * brightness on main (glass alpha 125 to 160 of 255 there) and 0.95 or more with the lightened glass (alpha 4 to 11).
 */
public class CrtTitleCornerClientTest implements FabricClientGameTest {
    /** A pixel is the title's text, not its halo (a fifth of the text's brightness), when it is at least this share of the brightest in its row kind. */
    private static final double TEXT_SHARE = 0.3;
    /** The first stem's top pixel must be at least this share of the brightest text in the same kind of row: between main's 0.5 and the fix's 0.95. */
    private static final double FIRST_STEM_SHARE = 0.9;
    /** Where the cursor is parked: well outside the window, so that no button is hovered. */
    private static final double OFF_SCREEN = -10_000;

    /** What the client says about a screen: its title, and the numbers that turn GUI pixels into screenshot pixels and rows into scanline kinds. */
    private record Shot(
            DrawnText.Drawn title, int guiScale, int glassTop, int scanlineSpacing, PanelLook.Insets content) {}

    @Override
    public void runTest(ClientGameTestContext context) {
        ClientTestLog.start(this);
        require(context.computeOnClient(client -> PanelLook.current().enabled()), "the panel is on with no pack");
        List<String> problems = new ArrayList<>();
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            Scene scene = singleplayer.getServer().computeOnServer(TerminalPanelScene::setUp);
            ClientWait.until(
                    context,
                    "the charter's account",
                    client -> ClientCharter.view()
                            .map(charter -> charter.balance() == TerminalPanelScene.ACCOUNT)
                            .orElse(false),
                    client -> "charter " + ClientCharter.view());
            for (String screen : TerminalPanelScreens.SCENE_SCREENS) {
                check(context, screen, scene, null, problems);
            }
        }
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            BlockPos contract = singleplayer.getServer().computeOnServer(TerminalPanelScene::setUpContract);
            check(context, TerminalPanelScreens.CONTRACT, null, contract, problems);
        }
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            BlockPos offline = singleplayer.getServer().computeOnServer(TerminalPanelScene::setUpOffline);
            check(context, TerminalPanelScreens.GENERIC, null, offline, problems);
        }
        require(problems.isEmpty(), "titles dimmed by the glass:\n" + String.join("\n", problems));
    }

    private static void check(
            ClientGameTestContext context,
            String screen,
            @Nullable Scene scene,
            @Nullable BlockPos elsewhere,
            List<String> problems) {
        Screen open = TerminalPanelScreens.open(context, screen, scene, elsewhere);
        context.getInput().setCursorPos(OFF_SCREEN, OFF_SCREEN);
        context.waitTicks(2); // tick-wait: lets the parked cursor un-hover the buttons before the still is taken
        Shot shot = context.computeOnClient(client -> {
            DrawnText.Drawn title = DrawnText.of(open).stream()
                    .filter(drawn -> !drawn.text().isBlank())
                    .min(Comparator.<DrawnText.Drawn>comparingInt(
                                    drawn -> drawn.box().y())
                            .thenComparingInt(drawn -> drawn.box().x()))
                    .orElseThrow(() -> new AssertionError(screen + " draws no text"));
            return new Shot(
                    title,
                    client.getWindow().getGuiScale(),
                    PanelLook.current().glass().top(),
                    CrtTuning.current().scanlineSpacing(),
                    PanelLook.current().content());
        });
        BufferedImage image;
        try {
            image = ImageIO.read(
                    context.takeScreenshot("crt-title-corner-" + screen).toFile());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        firstStemProblem(screen, shot, image).ifPresent(problems::add);
        TerminalPanelScreens.close(context);
    }

    /** The problem with the title's first stem against the rest of the title, if it is dimmer. */
    private static Optional<String> firstStemProblem(String screen, Shot shot, BufferedImage image) {
        Box box = shot.title().box();
        String title = "the title '" + shot.title().shortText() + "'";
        if (box.x() != shot.content().left() || box.y() != shot.content().top()) {
            return Optional.of(
                    screen + ": " + title + " is at " + box.x() + "," + box.y() + ", not at the content's corner "
                            + shot.content().left() + "," + shot.content().top());
        }
        int kinds = shot.scanlineSpacing();
        int[] brightestOfKind = new int[kinds];
        for (int y = box.y(); y < box.bottom(); y++) {
            for (int x = box.x(); x < box.right(); x++) {
                int kind = Math.floorMod(y - shot.glassTop(), kinds);
                brightestOfKind[kind] = Math.max(brightestOfKind[kind], brightness(shot, image, x, y));
            }
        }
        int overall = 0;
        for (int brightest : brightestOfKind) {
            overall = Math.max(overall, brightest);
        }
        if (overall < 100) {
            return Optional.of(screen + ": " + title + " is not visible in the still");
        }
        for (int x = box.x(); x < box.right(); x++) {
            for (int y = box.y(); y < box.bottom(); y++) {
                int kind = Math.floorMod(y - shot.glassTop(), kinds);
                int value = brightness(shot, image, x, y);
                if (brightestOfKind[kind] < TEXT_SHARE * overall || value < TEXT_SHARE * brightestOfKind[kind]) {
                    continue;
                }
                // The first lit pixel of the first lit column, from the top: where the glass's shading is heaviest.
                if (value < FIRST_STEM_SHARE * brightestOfKind[kind]) {
                    return Optional.of(screen + ": the first stem of " + title + " (GUI column " + x + ", row " + y
                            + ") reads " + value + ", the rest of the title " + brightestOfKind[kind]
                            + " in rows of the same scanline kind");
                }
                return Optional.empty();
            }
        }
        return Optional.of(screen + ": no lit column in " + title);
    }

    /** The brightest channel of the middle of GUI pixel ({@code x}, {@code y}) in the screenshot. */
    private static int brightness(Shot shot, BufferedImage image, int x, int y) {
        int rgb = image.getRGB(x * shot.guiScale() + shot.guiScale() / 2, y * shot.guiScale() + shot.guiScale() / 2);
        return Math.max((rgb >> 16) & 0xFF, Math.max((rgb >> 8) & 0xFF, rgb & 0xFF));
    }
}
