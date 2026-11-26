package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.Failures.failure;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.pkeppeler.deepcharter.charter.CharterId;
import io.github.pkeppeler.deepcharter.client.handbook.HandbookScreenTuning;
import io.github.pkeppeler.deepcharter.client.theme.BeamLook;
import io.github.pkeppeler.deepcharter.client.theme.BeamStyle;
import io.github.pkeppeler.deepcharter.client.theme.BreachLook;
import io.github.pkeppeler.deepcharter.client.theme.CatalogueLook;
import io.github.pkeppeler.deepcharter.client.theme.CockpitLook;
import io.github.pkeppeler.deepcharter.client.theme.ContinuityLook;
import io.github.pkeppeler.deepcharter.client.theme.HudLook;
import io.github.pkeppeler.deepcharter.client.theme.PanelLook;
import io.github.pkeppeler.deepcharter.client.theme.PodPaintLook;
import io.github.pkeppeler.deepcharter.client.theme.ReadoutLook;
import io.github.pkeppeler.deepcharter.client.theme.ScannerLook;
import io.github.pkeppeler.deepcharter.client.theme.SonarLook;
import io.github.pkeppeler.deepcharter.client.theme.SuitLook;
import io.github.pkeppeler.deepcharter.client.theme.TowChainLook;
import io.github.pkeppeler.deepcharter.client.theme.TransmissionLook;
import io.github.pkeppeler.deepcharter.client.theme.UiTheme;
import io.github.pkeppeler.deepcharter.client.ui.CrtTuning;
import io.github.pkeppeler.deepcharter.ore.OreType;
import io.github.pkeppeler.deepcharter.theme.ThemeData;
import io.github.pkeppeler.deepcharter.theme.ThemeData.Layer;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import javax.imageio.ImageIO;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import org.jspecify.annotations.Nullable;

/**
 * Server GameTests for #226. The default theme files hold exactly the look the UI had when its values were constants in Java, and the
 * range checks that keep a pack from stalling or blacking out the HUD name the pack that broke them. Written against the records'
 * {@code of} factories, which are plain Java, so no client is needed.
 */
public class ThemeLooksTest {
    private static final String AREA_DIR = "/assets/deepcharter/theme/";

    /** The default file of an area, then {@code override} on top of it as a pack would be. */
    private static ThemeData area(String name, @Nullable String override) {
        return ThemeData.parse(
                name,
                override == null
                        ? List.of(new Layer("the mod", defaultJson(name)))
                        : List.of(new Layer("the mod", defaultJson(name)), new Layer("test pack", override)));
    }

    private static String defaultJson(String name) {
        try (InputStream stream = ThemeLooksTest.class.getResourceAsStream(AREA_DIR + name + ".json")) {
            if (stream == null) {
                throw new AssertionError("missing " + AREA_DIR + name + ".json");
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    // The expected values below are the literals the Java held before #226, written out, and the values added since
    // (#1265 added the ghost veil, 0xA8050A06). Do not read them from the files.

    @GameTest
    public void theDefaultCrtLookIsTheOldOne(GameTestHelper helper) {
        expect(
                helper,
                "crt",
                new CrtTuning(
                        40,
                        10,
                        2,
                        0x58000000,
                        0x307CFC9A,
                        24,
                        0x2878FF9A,
                        0xFF050A06,
                        0xFF7CFC9A,
                        0xFF2E7A45,
                        0xFF123D20,
                        0xFFFFB000,
                        0xA8050A06,
                        6,
                        2,
                        1,
                        6,
                        4),
                CrtTuning.of(area("crt", null)));
        helper.succeed();
    }

    @GameTest
    public void theDefaultHandbookLookIsTheOldOne(GameTestHelper helper) {
        expect(
                helper,
                "handbook",
                new HandbookScreenTuning(
                        320,
                        200,
                        8,
                        8,
                        70,
                        56,
                        13,
                        46,
                        13,
                        6,
                        3,
                        10,
                        0x40,
                        0x80,
                        14,
                        14,
                        5,
                        4,
                        0x66000000,
                        0xFFF1E4C3,
                        0xFFC9B48A,
                        0xFF6B2D2D,
                        0xFF1B2A4E,
                        0xFF4A6A3A,
                        0xFFB3261E,
                        0xFF111111,
                        0xFF7A7058,
                        0xFFFFF4D6,
                        0xFF9A9078),
                HandbookScreenTuning.of(area("handbook", null)));
        helper.succeed();
    }

    @GameTest
    public void theDefaultHudLooksAreTheOldOnes(GameTestHelper helper) {
        expect(
                helper,
                "scanner",
                new ScannerLook(
                        3,
                        4,
                        0xFFFFFFFF,
                        0xFF101820,
                        0xFF5C5248,
                        0xFFE8E8F0,
                        0xFFFFD21E,
                        0xFFFF2A10,
                        0xFF78463A,
                        0xFFE040E0,
                        0x20000000,
                        0x38FFFFFF,
                        0x30FFD21E,
                        0x00FFFFFF,
                        0x00FFD21E,
                        4,
                        0x99FF1800,
                        0x99000000,
                        0x99E040E0,
                        0xFF38F06E,
                        0xFF000000,
                        4,
                        0x50000000,
                        Map.of(
                                OreType.IRONIUM,
                                0xFFF2F2FF,
                                OreType.BRONZIUM,
                                0xFFFFE4C8,
                                OreType.SILVERIUM,
                                0xFFC8ECFF,
                                OreType.GOLDIUM,
                                0xFFFFF070,
                                OreType.PLATINIUM,
                                0xFFC8FFE8,
                                OreType.EINSTEINIUM,
                                0xFFDCCCFF,
                                OreType.CICATRIUM,
                                0xFFFFC8E4)),
                ScannerLook.of(area("scanner", null)));
        expect(
                helper,
                "hud",
                new HudLook(
                        4,
                        2,
                        0xFFFFFFFF,
                        0xFFFF5522,
                        0xFFE8C170,
                        0xFFFF9A2E,
                        0xFFC9B8F0,
                        0xFFE060E0,
                        0xFFFFB020,
                        4,
                        0xFFFFFFFF,
                        4,
                        0.5,
                        0xFF7CFC9A),
                HudLook.of(area("hud", null)));
        expect(
                helper,
                "transmission",
                new TransmissionLook(0xEA050A06, 0xFF7CFC9A, 0xFF7CFC9A, 0xFFFFC857, 0xFFFF5A4F, 320, 16, 0.4, 6, 100),
                TransmissionLook.of(area("transmission", null)));
        expect(helper, "breach", new BreachLook(20, 8, 4, 10, 3, 0xFF000000), BreachLook.of(area("breach", null)));
        expect(helper, "tow", new TowChainLook(0.28, 0.2, 1.35, 0xFFFFFFFF, 7), TowChainLook.of(area("tow", null)));
        expect(
                helper,
                "beam",
                new BeamLook(
                        14,
                        96,
                        new BeamStyle(0xFFFFF0D2, 26, 0.12, 2.6, 0.03, 0.35, 0.1, 6, 0.9, 1.5),
                        new BeamStyle(0xFFFFCF6E, 30, 0.25, 5.0, 0.015, 0.35, 0.05, 24, 0.4, 0.55)),
                BeamLook.of(area("beam", null)));
        expect(
                helper,
                "readout",
                new ReadoutLook(14, 2, 1.0, 0xFF7CFC9A, 0xFFE8C170, 4, 6, 2),
                ReadoutLook.of(area("readout", null)));
        expect(
                helper,
                "sonar",
                new SonarLook(
                        81,
                        4,
                        0xFF7CE0FF,
                        0xC0081018,
                        0xFF000000,
                        0x40FFFFFF,
                        0xFF38F06E,
                        0xFFE8E8F0,
                        0xFFE040E0,
                        0xFF7A8896,
                        0xFFFF7A2A,
                        0xFF7CE0FF,
                        0x80000000,
                        0xFFB0B0B0,
                        60,
                        0xFFFFFFFF,
                        9,
                        14),
                SonarLook.of(area("sonar", null)));
        expect(
                helper,
                "suit",
                new SuitLook(6, 20, 96, 5, 16, 1, 2, 0xFF7CFC9A, 0xFFFFB000, 0xFFFF4A3A, 0xFF2E7A45, 5),
                SuitLook.of(area("suit", null)));
        expect(
                helper,
                "continuity",
                new ContinuityLook(
                        0.35,
                        0.45,
                        0.3,
                        0.88,
                        3,
                        0.94,
                        0.08,
                        0.8,
                        0.12,
                        3.0,
                        0.5,
                        0.4,
                        47,
                        0.92,
                        0.57,
                        1.75,
                        0.35,
                        16,
                        0.06,
                        4,
                        1,
                        0x0C7CFC9A,
                        0.5,
                        9,
                        14,
                        0.5,
                        0.03,
                        90,
                        40,
                        0x1C7CFC9A,
                        0xFF53A967,
                        0x592E7A45,
                        0xFF2E7A45,
                        120,
                        20,
                        72,
                        14,
                        8,
                        10.0),
                ContinuityLook.of(area("continuity", null)));
        helper.succeed();
    }

    @GameTest
    public void everyAreaOfTheDefaultThemeIsReadAndNothingIsLeftOver(GameTestHelper helper) {
        Map<String, ThemeData> areas = defaultAreas();
        UiTheme.of(areas);
        areas.forEach((name, data) -> {
            if (!data.unread().isEmpty()) {
                throw failure(helper, "default area '%s' has keys nothing reads: %s", name, data.unread());
            }
        });
        helper.succeed();
    }

    /** The pods' paint palette (#383): six colours, written out; a pack adds one by raising the count; a charter's slot is a function of its id alone. */
    @GameTest
    public void thePodPaintPaletteIsPinnedAndACharterKeepsItsSlot(GameTestHelper helper) {
        PodPaintLook look = PodPaintLook.of(area("pod", null));
        List<Integer> expected = List.of(0xFFBAAC8E, 0xFFB5503C, 0xFF4A6FA5, 0xFF6E8B4E, 0xFFD2A03A, 0xFF7A6A9A);
        if (!look.paints().equals(expected)) {
            throw failure(helper, "the default paint palette should be %s, is %s", expected, look.paints());
        }
        CharterId charter = new CharterId(new UUID(0L, 8L));
        if (look.slotOf(charter) != 2 || look.paintOf(charter) != expected.get(2)) {
            throw failure(
                    helper,
                    "a charter's slot is its id's hash modulo the palette: expected slot 2 for 8 mod 6, got %s",
                    look.slotOf(charter));
        }
        if (look.slotOf(new CharterId(new UUID(0L, 1L << 31))) < 0) {
            throw failure(helper, "a slot is never negative, even for an id whose hash is");
        }
        PodPaintLook seven = PodPaintLook.of(area("pod", "{ \"paintCount\": 7, \"paint6\": \"#102030\" }"));
        if (seven.paints().size() != 7 || seven.paints().get(6) != 0xFF102030) {
            throw failure(
                    helper, "a pack adds a colour by raising the count and naming the key, got %s", seven.paints());
        }
        rejected(helper, () -> PodPaintLook.of(area("pod", "{ \"paintCount\": 0 }")), "paintCount");
        try {
            PodPaintLook.of(area("pod", "{ \"paintCount\": 7 }"));
            throw failure(helper, "a count of 7 with no key paint6 should be refused");
        } catch (IllegalArgumentException | IllegalStateException e) {
            requireContains(helper, String.valueOf(e.getMessage()), "paint6");
        }
        helper.succeed();
    }

    /** The machine panel (#246): the mod ships Hatch, on, with the nameplate, the four decals and the pips in place. */
    @GameTest
    public void thePanelIsOnInTheDefaultTheme(GameTestHelper helper) {
        PanelLook look = PanelLook.of(area("panel", null));
        if (!look.enabled()) {
            throw failure(helper, "the panel must be on in the mod's own theme");
        }
        for (PanelLook.Decal decal :
                List.of(look.nameplate(), look.dressA(), look.dressB(), look.dressC(), look.dressD())) {
            if (!decal.on()) {
                throw failure(helper, "every decal slot is filled in the mod's own theme, got %s", decal);
            }
        }
        if (look.pipSize() != 12) {
            throw failure(helper, "Hatch's pip is 12 pixels, got %s", look.pipSize());
        }
        helper.succeed();
    }

    /** The screens were drawn for 288 x 192 of content: a GUI that leaves less inside the insets (320 x 240 is the game's smallest) draws the plain CRT. */
    @GameTest
    public void thePanelIsDrawnOnlyWhereTheContentFits(GameTestHelper helper) {
        PanelLook look = PanelLook.of(area("panel", null));
        if (!look.fits(427, 240) || !look.fits(854, 480)) {
            throw failure(helper, "the panel fits the default GUI and larger ones");
        }
        if (look.fits(320, 240) || look.fits(427, 200)) {
            throw failure(helper, "the panel does not fit a 320 x 240 GUI, nor one 200 high");
        }
        if (PanelLook.of(area("panel", "{ \"enabled\": 0 }")).fits(854, 480)) {
            throw failure(helper, "a panel that is off fits nowhere");
        }
        helper.succeed();
    }

    /**
     * The glass is cut to the corner of the frame's porthole (#907): the mod ships radius 18, a pack can name another, and a radius out of range is
     * refused. The inset table is the one {@code tools/tests/test_terminal_concepts.py} reads off the frame sprite's own porthole, so the scanlines
     * end where the bezel's curve does.
     */
    @GameTest
    public void theGlassCornerHasTheBezelsRadius(GameTestHelper helper) {
        PanelLook look = PanelLook.of(area("panel", null));
        if (look.glassRadius() != 18) {
            throw failure(helper, "the mod's glass corner is the porthole's 18 pixels, got %s", look.glassRadius());
        }
        List<Integer> insets = new ArrayList<>();
        for (int row = 0; row < 20; row++) {
            insets.add(PanelLook.cornerInset(look.glassRadius(), row));
        }
        List<Integer> porthole = List.of(14, 11, 9, 7, 6, 5, 4, 3, 3, 2, 2, 1, 1, 1, 0, 0, 0, 0, 0, 0);
        if (!insets.equals(porthole)) {
            throw failure(helper, "the corner insets of radius 18 are %s, got %s", porthole, insets);
        }
        if (PanelLook.cornerInset(0, 0) != 0) {
            throw failure(helper, "a radius of 0 is a square corner");
        }
        if (PanelLook.of(area("panel", "{ \"glassRadius\": 24 }")).glassRadius() != 24) {
            throw failure(helper, "a pack names its own glass radius");
        }
        rejected(helper, () -> PanelLook.of(area("panel", "{ \"glassRadius\": 65 }")), "glassRadius");
        rejected(helper, () -> PanelLook.of(area("panel", "{ \"glassRadius\": -1 }")), "glassRadius");
        helper.succeed();
    }

    /** Scanlines stop where the glass does: at the top and the bottom, and on a glass too small for the radius (the radius is cut to half of it). */
    @GameTest
    public void scanlinesStopAtTheGlassCorner(GameTestHelper helper) {
        int[][] cases = {
            // radius, row, width, height, inset
            {18, 0, 300, 200, 14},
            {18, 199, 300, 200, 14},
            {18, 198, 300, 200, 11},
            {18, 100, 300, 200, 0},
            {18, 0, 300, 20, 7},
            {18, 19, 300, 20, 7},
            {18, 5, 300, 20, 1},
            {18, 5, 20, 300, 1},
            {0, 0, 300, 200, 0},
            {0, 199, 300, 200, 0},
        };
        for (int[] c : cases) {
            int inset = PanelLook.scanlineInset(c[0], c[1], c[2], c[3]);
            if (inset != c[4]) {
                throw failure(helper, "scanlineInset%s should be %s, got %s", Arrays.toString(c), c[4], inset);
            }
        }
        helper.succeed();
    }

    /**
     * The mod's own three values agree (#907): {@code glassRadius} is at most the border of the glass sprite's nine-slice, which holds the whole arc, and
     * it is the radius of the frame's porthole, read off the frame's pixels at the top-left and bottom-right corners. A pack's own mismatch is
     * documented in skins.md.
     */
    @GameTest
    public void theGlassRadiusMatchesTheShippedSprites(GameTestHelper helper) throws IOException {
        PanelLook look = PanelLook.of(area("panel", null));
        String sprites = "/assets/deepcharter/textures/gui/sprites/panel/";
        JsonObject border;
        try (InputStream stream = ThemeLooksTest.class.getResourceAsStream(sprites + "glass.png.mcmeta")) {
            border = JsonParser.parseString(new String(stream.readAllBytes(), StandardCharsets.UTF_8))
                    .getAsJsonObject()
                    .getAsJsonObject("gui")
                    .getAsJsonObject("scaling")
                    .getAsJsonObject("border");
        }
        for (String side : List.of("left", "top", "right", "bottom")) {
            if (look.glassRadius() > border.get(side).getAsInt()) {
                throw failure(
                        helper, "glassRadius %s is over the glass border %s %s", look.glassRadius(), side, border);
            }
        }
        BufferedImage frame;
        try (InputStream stream = ThemeLooksTest.class.getResourceAsStream(sprites + "frame.png")) {
            frame = ImageIO.read(stream);
        }
        PanelLook.Insets glass = look.glass();
        int bottom = frame.getHeight() - glass.bottom() - 1;
        int right = frame.getWidth() - glass.right() - 1;
        for (int row = 0; row < look.glassRadius(); row++) {
            int expected = PanelLook.cornerInset(look.glassRadius(), row);
            int topLeft = 0;
            while (frame.getRGB(glass.left() + topLeft, glass.top() + row) >>> 24 != 0) {
                topLeft++;
            }
            int bottomRight = 0;
            while (frame.getRGB(right - bottomRight, bottom - row) >>> 24 != 0) {
                bottomRight++;
            }
            if (topLeft != expected || bottomRight != expected) {
                throw failure(
                        helper,
                        "the porthole's row %s is %s in at the top left and %s at the bottom right, but glassRadius %s says %s",
                        row,
                        topLeft,
                        bottomRight,
                        look.glassRadius(),
                        expected);
            }
        }
        helper.succeed();
    }

    /** A pack switches the panel off by naming one key, and the terminals are the plain CRT again. */
    @GameTest
    public void aPackSwitchesThePanelOff(GameTestHelper helper) {
        if (PanelLook.of(area("panel", "{ \"enabled\": 0 }")).enabled()) {
            throw failure(helper, "a pack that names enabled 0 turns the panel off");
        }
        helper.succeed();
    }

    /** A pack moves the nameplate and a decal (from the right edge and the bottom edge) by naming a few keys, and keeps the mod's other keys. */
    @GameTest
    public void aPackRestylesThePanelKeyByKeyAndAnchorsDecals(GameTestHelper helper) {
        PanelLook look = PanelLook.of(area("panel", """
				{ "enabled": 1, "insetTop": 18, "nameplateW": 70, "nameplateH": 14, "nameplateAnchorX": 2, "nameplateAnchorY": 0, "nameplateX": 6, "nameplateY": 0,
				  "dressAW": 30, "dressAH": 20, "dressAAnchorX": 1, "dressAAnchorY": 2, "dressAX": -10, "dressAY": 3 }"""));
        if (!look.enabled() || look.content().top() != 18 || look.content().left() != 52) {
            throw failure(helper, "a pack names a key and keeps the rest, got %s", look);
        }
        PanelLook.Decal plate = look.nameplate();
        if (plate.left(427) != 427 - 70 - 6 || plate.top(240) != 0) {
            throw failure(
                    helper,
                    "the nameplate sits 6 from the right and at the top, got %s, %s",
                    plate.left(427),
                    plate.top(240));
        }
        PanelLook.Decal strip = look.dressA();
        if (strip.left(427) != (427 - 30) / 2 - 10 || strip.top(240) != 240 - 20 - 3) {
            throw failure(
                    helper,
                    "the strip is centred, 10 left, and 3 above the bottom, got %s, %s",
                    strip.left(427),
                    strip.top(240));
        }
        helper.succeed();
    }

    /**
     * A label has room for its pip when it would not then run past the edge. A screen draws pips on all its buttons or on none, so the label
     * starts after the pip when the screen has them and at the pad when it has not.
     */
    @GameTest
    public void aLabelFitsBesideItsPipOrTheScreenDrawsNone(GameTestHelper helper) {
        PanelLook left = PanelLook.of(area(
                "panel", "{ \"enabled\": 1, \"buttonAlign\": 1, \"buttonPad\": 4, \"pipSize\": 12, \"pipX\": 2 }"));
        if (!left.showsPip(104, 60) || left.labelStart(104, 60, true) != 18) {
            throw failure(
                    helper,
                    "a short label sits after the pip at 18, got %s and %s",
                    left.showsPip(104, 60),
                    left.labelStart(104, 60, true));
        }
        if (left.showsPip(104, 96)) {
            throw failure(helper, "a 96 pixel label does not fit beside the pip in a 104 pixel button");
        }
        if (left.labelStart(104, 60, false) != 4) {
            throw failure(
                    helper,
                    "on a screen with no pips a label starts at the pad, got %s",
                    left.labelStart(104, 60, false));
        }
        PanelLook centred = PanelLook.of(area("panel", "{ \"enabled\": 1, \"buttonAlign\": 0, \"pipSize\": 12 }"));
        if (centred.showsPip(104, 20) || centred.labelStart(104, 60, true) != 22) {
            throw failure(
                    helper,
                    "a centred label has no pip and starts in the middle, got %s and %s",
                    centred.showsPip(104, 20),
                    centred.labelStart(104, 60, true));
        }
        helper.succeed();
    }

    /** The cockpit HUD (#247): the mod ships the Ribbon and no camera, and a pack turns the Ribbon off by naming one key, keeping the mod's other keys. */
    @GameTest
    public void theRibbonIsOnInTheDefaultThemeAndAPackTurnsItOff(GameTestHelper helper) {
        CockpitLook shipped = CockpitLook.of(area("cockpit", null));
        if (!shipped.enabled() || !shipped.fits(320, 240)) {
            throw failure(
                    helper,
                    "the mod's own theme draws the Ribbon on a 320 x 240 GUI, the least vanilla's GUI scale leaves");
        }
        if (shipped.fits(319, 240) || shipped.fits(320, 239)) {
            throw failure(
                    helper,
                    "the Ribbon needs a GUI of at least %s x %s, and falls back to the plain HUD under it",
                    shipped.minGuiWidth(),
                    shipped.minGuiHeight());
        }
        if (shipped.camera().installed()) {
            throw failure(helper, "no pod has the front camera until a build sells the part");
        }
        CockpitLook off = CockpitLook.of(area("cockpit", "{ \"enabled\": 0 }"));
        if (off.enabled() || off.fits(1920, 1080)) {
            throw failure(helper, "a pack that names enabled 0 gets the plain HUD on any GUI");
        }
        if (off.fuelColor() != shipped.fuelColor()) {
            throw failure(helper, "a pack that names only enabled keeps the mod's colours");
        }
        if (!CockpitLook.of(area("cockpit", "{ \"cameraInstalled\": 1 }"))
                .camera()
                .installed()) {
            throw failure(helper, "a pack that names cameraInstalled puts the camera on the pod");
        }
        helper.succeed();
    }

    /** The Ribbon names its five gauges, its lamps and a scanner window that holds the slices of tiers 1 to 3, and finds the sprite of a slot by name. */
    @GameTest
    public void theRibbonHasItsGaugesLampsAndAScannerWindow(GameTestHelper helper) {
        CockpitLook.Layout layout = CockpitLook.of(area("cockpit", null)).ribbon();
        for (CockpitLook.Gauge gauge :
                List.of(layout.fuel(), layout.hull(), layout.depth(), layout.heat(), layout.cargo())) {
            if (!gauge.on()) {
                throw failure(helper, "the Ribbon must have its five gauges, got %s", gauge);
            }
        }
        if (!layout.lamps().on()) {
            throw failure(helper, "the Ribbon must have its warning lamps");
        }
        if (layout.scanner().width() != 98 || layout.scanner().height() != 82) {
            throw failure(
                    helper,
                    "the scanner window holds the slices of tiers 1 to 3 whole, 98 x 82, got %s",
                    layout.scanner());
        }
        if (!layout.sprite("plate").toString().equals("deepcharter:cockpit/ribbon/plate")) {
            throw failure(
                    helper, "the plate sprite is deepcharter:cockpit/ribbon/plate, got %s", layout.sprite("plate"));
        }
        helper.succeed();
    }

    @GameTest
    public void theCockpitRejectsValuesOutOfRange(GameTestHelper helper) {
        rejected(helper, () -> CockpitLook.of(area("cockpit", "{ \"enabled\": 2 }")), "enabled");
        rejected(helper, () -> CockpitLook.of(area("cockpit", "{ \"enabled\": -1 }")), "enabled");
        rejected(helper, () -> CockpitLook.of(area("cockpit", "{ \"minGuiWidth\": 0 }")), "minGuiWidth");
        rejected(helper, () -> CockpitLook.of(area("cockpit", "{ \"hullLowFraction\": 1.5 }")), "hullLowFraction");
        rejected(helper, () -> CockpitLook.of(area("cockpit", "{ \"heatRisePerTick\": 0 }")), "heatRisePerTick");
        rejected(helper, () -> CockpitLook.of(area("cockpit", "{ \"ribbonFuelSegments\": 0 }")), "ribbonFuelSegments");
        rejected(helper, () -> CockpitLook.of(area("cockpit", "{ \"ribbonHullKind\": 2 }")), "ribbonHullKind");
        rejected(helper, () -> CockpitLook.of(area("cockpit", "{ \"cameraInstalled\": 2 }")), "cameraInstalled");
        rejected(helper, () -> CockpitLook.of(area("cockpit", "{ \"cameraLensRecover\": 1 }")), "cameraLensRecover");
        rejected(
                helper,
                () -> CockpitLook.of(area("cockpit", "{ \"cameraFlickerMinTicks\": 9 }")),
                "cameraFlickerMinTicks");
        helper.succeed();
    }

    @GameTest
    public void thePanelRejectsValuesOutOfRange(GameTestHelper helper) {
        rejected(helper, () -> PanelLook.of(area("panel", "{ \"enabled\": 2 }")), "enabled");
        rejected(helper, () -> PanelLook.of(area("panel", "{ \"nameplateAnchorX\": 3 }")), "nameplateAnchorX");
        rejected(helper, () -> PanelLook.of(area("panel", "{ \"insetTop\": -1 }")), "insetTop");
        rejected(helper, () -> PanelLook.of(area("panel", "{ \"buttonAlign\": 2 }")), "buttonAlign");
        rejected(helper, () -> PanelLook.of(area("panel", "{ \"wallColor\": 5 }")), "wallColor");
        helper.succeed();
    }

    private static Map<String, ThemeData> defaultAreas() {
        Map<String, ThemeData> areas = new LinkedHashMap<>();
        for (String name : List.of(
                "crt",
                "handbook",
                "scanner",
                "hud",
                "transmission",
                "breach",
                "pod",
                "panel",
                "cockpit",
                "tow",
                "beam",
                "catalogue",
                "readout",
                "sonar",
                "suit",
                "continuity")) {
            areas.put(name, area(name, null));
        }
        return areas;
    }

    // Range checks: each message names the pack and the key.

    @GameTest
    public void crtTypingSpeedMustBePositive(GameTestHelper helper) {
        rejected(helper, () -> CrtTuning.of(area("crt", "{ \"lettersPerSecond\": 0 }")), "lettersPerSecond");
        rejected(helper, () -> CrtTuning.of(area("crt", "{ \"lettersPerSecond\": -5 }")), "lettersPerSecond");
        rejected(helper, () -> CrtTuning.of(area("crt", "{ \"scanlineSpacing\": 0 }")), "scanlineSpacing");
        helper.succeed();
    }

    @GameTest
    public void theContinuityEventKeepsItsBounds(GameTestHelper helper) {
        rejected(helper, () -> ContinuityLook.of(area("continuity", "{ \"rows\": 4 }")), "rows");
        rejected(helper, () -> ContinuityLook.of(area("continuity", "{ \"skipPasses\": 9 }")), "skipPasses");
        rejected(helper, () -> ContinuityLook.of(area("continuity", "{ \"boneColor\": 5 }")), "boneColor");
        rejected(helper, () -> ContinuityLook.of(area("continuity", "{ \"cellAspect\": 3 }")), "cellAspect");
        rejected(helper, () -> ContinuityLook.of(area("continuity", "{ \"jawSteps\": 0 }")), "jawSteps");
        rejected(helper, () -> ContinuityLook.of(area("continuity", "{ \"bodySolidTicks\": 21 }")), "bodySolidTicks");
        // Gson reads NaN and Infinity as numbers: a timing that is not one is refused, or it would hold the button.
        rejected(helper, () -> ContinuityLook.of(area("continuity", "{ \"cutSeconds\": NaN }")), "cutSeconds");
        rejected(
                helper,
                () -> ContinuityLook.of(area("continuity", "{ \"skipPeriodSeconds\": Infinity }")),
                "skipPeriodSeconds");
        rejected(
                helper,
                () -> ContinuityLook.of(
                        area("continuity", "{ \"skipPeriodSeconds\": 0.1, \"skipOverlapSeconds\": 0.2 }")),
                "skipOverlapSeconds");
        // The last pass is the shortest: 0.3 s shrunk by half four times is under 0.02 s, shorter than the overlap.
        rejected(
                helper,
                () -> ContinuityLook.of(area(
                        "continuity",
                        "{ \"skipPeriodSeconds\": 0.3, \"skipShrink\": 0.5, \"skipPasses\": 5,"
                                + " \"skipOverlapSeconds\": 0.08 }")),
                "skipShrink");
        // With no pass to overlap, the overlap is never used.
        Object _ = ContinuityLook.of(
                area("continuity", "{ \"skipPeriodSeconds\": 0.1, \"skipPasses\": 0, \"skipOverlapSeconds\": 0.2 }"));
        helper.succeed();
    }

    @GameTest
    public void theSuitGaugeStaysWithinItsBounds(GameTestHelper helper) {
        rejected(helper, () -> SuitLook.of(area("suit", "{ \"barWidth\": 10 }")), "barWidth");
        rejected(helper, () -> SuitLook.of(area("suit", "{ \"segments\": 1 }")), "segments");
        rejected(helper, () -> SuitLook.of(area("suit", "{ \"blinkTicks\": 0 }")), "blinkTicks");
        rejected(helper, () -> SuitLook.of(area("suit", "{ \"barWidth\": 20, \"segments\": 20 }")), "segments");
        rejected(helper, () -> SuitLook.of(area("suit", "{ \"redColor\": 5 }")), "redColor");
        helper.succeed();
    }

    @GameTest
    public void theCatalogueLooksAreTheMockupsSizesAndKeepTheirBounds(GameTestHelper helper) {
        CatalogueLook look = CatalogueLook.of(area("catalogue", null));
        expect(
                helper,
                "catalogue panel widths",
                List.of(123, 122),
                List.of(look.maxPanelWidth(), look.minPanelWidth()));
        expect(helper, "catalogue console height, the inventory's", List.of(166), List.of(look.consoleHeight()));
        expect(
                helper,
                "catalogue grid",
                List.of(5, 3, 19),
                List.of(look.gridColumns(), look.gridRows(), look.gridCell()));
        rejected(helper, () -> CatalogueLook.of(area("catalogue", "{ \"minPanelWidth\": 200 }")), "minPanelWidth");
        rejected(helper, () -> CatalogueLook.of(area("catalogue", "{ \"gridColumns\": 0 }")), "gridColumns");
        rejected(helper, () -> CatalogueLook.of(area("catalogue", "{ \"cycleMillis\": 5 }")), "cycleMillis");
        helper.succeed();
    }

    @GameTest
    public void theBlockReadoutStaysWithinItsBounds(GameTestHelper helper) {
        rejected(helper, () -> ReadoutLook.of(area("readout", "{ \"scale\": 3 }")), "scale");
        rejected(helper, () -> ReadoutLook.of(area("readout", "{ \"fadeInTicks\": 0 }")), "fadeInTicks");
        rejected(helper, () -> ReadoutLook.of(area("readout", "{ \"fadeOutTicks\": 41 }")), "fadeOutTicks");
        rejected(helper, () -> ReadoutLook.of(area("readout", "{ \"settleTicks\": -1 }")), "settleTicks");
        rejected(helper, () -> ReadoutLook.of(area("readout", "{ \"hintColor\": 5 }")), "hintColor");
        helper.succeed();
    }

    @GameTest
    public void theSonarScopeStaysWithinItsBounds(GameTestHelper helper) {
        rejected(helper, () -> SonarLook.of(area("sonar", "{ \"scopePixels\": 80 }")), "scopePixels");
        rejected(helper, () -> SonarLook.of(area("sonar", "{ \"scopePixels\": 9 }")), "scopePixels");
        rejected(helper, () -> SonarLook.of(area("sonar", "{ \"minAlpha\": 256 }")), "minAlpha");
        rejected(helper, () -> SonarLook.of(area("sonar", "{ \"markPixels\": 8 }")), "markPixels");
        rejected(helper, () -> SonarLook.of(area("sonar", "{ \"markMargin\": 101 }")), "markMargin");
        rejected(helper, () -> SonarLook.of(area("sonar", "{ \"oreColor\": 5 }")), "oreColor");
        helper.succeed();
    }

    @GameTest
    public void aTowChainLinkStaysWithinItsBounds(GameTestHelper helper) {
        rejected(helper, () -> TowChainLook.of(area("tow", "{ \"linkLength\": 0.01 }")), "linkLength");
        rejected(helper, () -> TowChainLook.of(area("tow", "{ \"linkWidth\": 0.6 }")), "linkWidth");
        rejected(helper, () -> TowChainLook.of(area("tow", "{ \"linkOverlap\": 0.9 }")), "linkOverlap");
        rejected(helper, () -> TowChainLook.of(area("tow", "{ \"minBlockLight\": 16 }")), "minBlockLight");
        helper.succeed();
    }

    @GameTest
    public void aBeamStaysWithinItsBounds(GameTestHelper helper) {
        rejected(helper, () -> BeamLook.of(area("beam", "{ \"sides\": 2 }")), "sides");
        rejected(helper, () -> BeamLook.of(area("beam", "{ \"drawDistance\": 0 }")), "drawDistance");
        rejected(helper, () -> BeamLook.of(area("beam", "{ \"headlampLength\": 0 }")), "headlampLength");
        rejected(helper, () -> BeamLook.of(area("beam", "{ \"floodlightLength\": 500 }")), "floodlightLength");
        rejected(helper, () -> BeamLook.of(area("beam", "{ \"headlampAlpha\": 1.5 }")), "headlampAlpha");
        rejected(helper, () -> BeamLook.of(area("beam", "{ \"headlampCoreScale\": 0 }")), "headlampCoreScale");
        rejected(helper, () -> BeamLook.of(area("beam", "{ \"floodlightPitch\": 95 }")), "floodlightPitch");
        rejected(helper, () -> BeamLook.of(area("beam", "{ \"headlampColor\": 5 }")), "headlampColor");
        helper.succeed();
    }

    @GameTest
    public void aPackRestylesOneBeamKeyAndKeepsTheRest(GameTestHelper helper) {
        BeamLook look = BeamLook.of(area("beam", "{ \"floodlightColor\": \"#FFB347\", \"floodlightLength\": 12 }"));
        if (look.floodlight().color() != 0xFFFFB347 || look.floodlight().length() != 12) {
            throw failure(helper, "the pack's floodlight colour and length should apply, got %s", look.floodlight());
        }
        if (look.headlamp().color() != 0xFFFFF0D2 || look.sides() != 14) {
            throw failure(helper, "the keys the pack did not name should keep the mod's values, got %s", look);
        }
        helper.succeed();
    }

    @GameTest
    public void handbookAlphaStopsAt255(GameTestHelper helper) {
        rejected(helper, () -> HandbookScreenTuning.of(area("handbook", "{ \"ruleAlpha\": 256 }")), "ruleAlpha");
        rejected(
                helper,
                () -> HandbookScreenTuning.of(area("handbook", "{ \"marginRuleAlpha\": -1 }")),
                "marginRuleAlpha");
        rejected(helper, () -> HandbookScreenTuning.of(area("handbook", "{ \"flipTicks\": 0 }")), "flipTicks");
        helper.succeed();
    }

    @GameTest
    public void theBreachFadeMustComeBackAndMustNotStall(GameTestHelper helper) {
        String sum = rejected(
                helper, () -> BreachLook.of(area("breach", "{ \"fadeInTicks\": 12, \"blackTicks\": 8 }")), "fadeTicks");
        requireContains(helper, sum, "fadeInTicks");
        requireContains(helper, sum, "blackTicks");
        rejected(helper, () -> BreachLook.of(area("breach", "{ \"fadeTicks\": 101 }")), "fadeTicks");
        rejected(
                helper,
                () -> BreachLook.of(area("breach", "{ \"fadeTicks\": 1000, \"fadeInTicks\": 8 }")),
                "fadeTicks");
        rejected(helper, () -> BreachLook.of(area("breach", "{ \"jitterTicks\": 101 }")), "jitterTicks");
        rejected(helper, () -> BreachLook.of(area("breach", "{ \"jitterPixels\": 33 }")), "jitterPixels");
        BreachLook.of(area("breach", "{ \"fadeTicks\": 100, \"fadeInTicks\": 40, \"blackTicks\": 20 }"));
        helper.succeed();
    }

    @GameTest
    public void aTransmissionMustNotStayUpLongAndFractionsStayOnTheScreen(GameTestHelper helper) {
        rejected(helper, () -> TransmissionLook.of(area("transmission", "{ \"holdTicks\": 1201 }")), "holdTicks");
        rejected(helper, () -> TransmissionLook.of(area("transmission", "{ \"holdTicks\": 0 }")), "holdTicks");
        rejected(helper, () -> TransmissionLook.of(area("transmission", "{ \"centerY\": 1.5 }")), "centerY");
        rejected(helper, () -> TransmissionLook.of(area("transmission", "{ \"centerY\": -0.1 }")), "centerY");
        rejected(helper, () -> HudLook.of(area("hud", "{ \"accountY\": 2 }")), "accountY");
        TransmissionLook.of(area("transmission", "{ \"holdTicks\": 1200, \"centerY\": 1 }"));
        helper.succeed();
    }

    @GameTest
    public void theTransmissionPanelMustHaveRoomForText(GameTestHelper helper) {
        Map<String, ThemeData> areas = defaultAreas();
        areas.put("crt", area("crt", "{ \"padding\": 200 }"));
        rejected(helper, () -> UiTheme.of(areas), "maxWidth");
        helper.succeed();
    }

    /** Runs {@code action}, which must throw naming {@code key} and the test pack, and returns the message. */
    private static String rejected(GameTestHelper helper, Supplier<?> action, String key) {
        try {
            Object _ = action.get();
        } catch (IllegalArgumentException | IllegalStateException e) {
            String message = String.valueOf(e.getMessage());
            requireContains(helper, message, key);
            requireContains(helper, message, "test pack");
            return message;
        }
        throw failure(helper, "a bad value for '%s' was accepted", key);
    }

    private static void requireContains(GameTestHelper helper, String message, String part) {
        if (!message.contains(part)) {
            throw failure(helper, "message '%s' should name '%s'", message, part);
        }
    }

    private static void expect(GameTestHelper helper, String what, Object expected, Object actual) {
        if (!expected.equals(actual)) {
            throw failure(helper, "%s is not the pre-#226 look:%n  expected %s%n  actual   %s", what, expected, actual);
        }
    }
}
