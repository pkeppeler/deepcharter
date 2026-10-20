package io.github.pkeppeler.deepcharter.test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.pkeppeler.deepcharter.charter.CharterId;
import io.github.pkeppeler.deepcharter.client.handbook.HandbookScreenTuning;
import io.github.pkeppeler.deepcharter.client.theme.BeamLook;
import io.github.pkeppeler.deepcharter.client.theme.BeamStyle;
import io.github.pkeppeler.deepcharter.client.theme.BreachLook;
import io.github.pkeppeler.deepcharter.client.theme.CargoLook;
import io.github.pkeppeler.deepcharter.client.theme.CockpitLook;
import io.github.pkeppeler.deepcharter.client.theme.HudLook;
import io.github.pkeppeler.deepcharter.client.theme.PanelLook;
import io.github.pkeppeler.deepcharter.client.theme.PodPaintLook;
import io.github.pkeppeler.deepcharter.client.theme.ScannerLook;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import javax.imageio.ImageIO;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
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

    // The expected values below are the literals the Java held before #226, written out. Do not read them from the
    // files.

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
        expect(helper, "cargo", new CargoLook(0xFF404040), CargoLook.of(area("cargo", null)));
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
        helper.succeed();
    }

    @GameTest
    public void theCargoSpritesAreTheOldPanelAndSlot(GameTestHelper helper) throws IOException {
        sprite(helper, "panel", 0xFF373737, 0xFFC6C6C6);
        sprite(helper, "slot", 0xFF373737, 0xFF8B8B8B);
        helper.succeed();
    }

    private static void sprite(GameTestHelper helper, String name, int edge, int fill) throws IOException {
        String base = "/assets/deepcharter/textures/gui/sprites/cargo/" + name + ".png";
        BufferedImage image;
        try (InputStream stream = ThemeLooksTest.class.getResourceAsStream(base)) {
            image = ImageIO.read(stream);
        }
        if (image.getWidth() != 3 || image.getHeight() != 3) {
            throw fail(helper, name + " sprite should be 3 x 3, is " + image.getWidth() + " x " + image.getHeight());
        }
        for (int y = 0; y < 3; y++) {
            for (int x = 0; x < 3; x++) {
                int expected = x == 1 && y == 1 ? fill : edge;
                if (image.getRGB(x, y) != expected) {
                    throw fail(
                            helper,
                            "%s sprite pixel %d,%d should be 0x%08X, is 0x%08X"
                                    .formatted(name, x, y, expected, image.getRGB(x, y)));
                }
            }
        }
        try (InputStream stream = ThemeLooksTest.class.getResourceAsStream(base + ".mcmeta")) {
            JsonObject scaling = JsonParser.parseString(new String(stream.readAllBytes(), StandardCharsets.UTF_8))
                    .getAsJsonObject()
                    .getAsJsonObject("gui")
                    .getAsJsonObject("scaling");
            if (!scaling.get("type").getAsString().equals("nine_slice")
                    || scaling.get("border").getAsInt() != 1
                    || scaling.get("width").getAsInt() != 3
                    || scaling.get("height").getAsInt() != 3) {
                throw fail(helper, name + " sprite should be a 3 x 3 nine-slice with border 1, is " + scaling);
            }
        }
    }

    @GameTest
    public void everyAreaOfTheDefaultThemeIsReadAndNothingIsLeftOver(GameTestHelper helper) {
        Map<String, ThemeData> areas = defaultAreas();
        UiTheme.of(areas);
        areas.forEach((name, data) -> {
            if (!data.unread().isEmpty()) {
                throw fail(helper, "default area '%s' has keys nothing reads: %s".formatted(name, data.unread()));
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
            throw fail(helper, "the default paint palette should be " + expected + ", is " + look.paints());
        }
        CharterId charter = new CharterId(new UUID(0L, 8L));
        if (look.slotOf(charter) != 2 || look.paintOf(charter) != expected.get(2)) {
            throw fail(
                    helper,
                    "a charter's slot is its id's hash modulo the palette: expected slot 2 for 8 mod 6, got "
                            + look.slotOf(charter));
        }
        if (look.slotOf(new CharterId(new UUID(0L, 1L << 31))) < 0) {
            throw fail(helper, "a slot is never negative, even for an id whose hash is");
        }
        PodPaintLook seven = PodPaintLook.of(area("pod", "{ \"paintCount\": 7, \"paint6\": \"#102030\" }"));
        if (seven.paints().size() != 7 || seven.paints().get(6) != 0xFF102030) {
            throw fail(helper, "a pack adds a colour by raising the count and naming the key, got " + seven.paints());
        }
        rejected(helper, () -> PodPaintLook.of(area("pod", "{ \"paintCount\": 0 }")), "paintCount");
        try {
            PodPaintLook.of(area("pod", "{ \"paintCount\": 7 }"));
            throw fail(helper, "a count of 7 with no key paint6 should be refused");
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
            throw fail(helper, "the panel must be on in the mod's own theme");
        }
        for (PanelLook.Decal decal :
                List.of(look.nameplate(), look.dressA(), look.dressB(), look.dressC(), look.dressD())) {
            if (!decal.on()) {
                throw fail(helper, "every decal slot is filled in the mod's own theme, got " + decal);
            }
        }
        if (look.pipSize() != 12) {
            throw fail(helper, "Hatch's pip is 12 pixels, got " + look.pipSize());
        }
        helper.succeed();
    }

    /** The screens were drawn for 288 x 192 of content: a GUI that leaves less inside the insets (320 x 240 is the game's smallest) draws the plain CRT. */
    @GameTest
    public void thePanelIsDrawnOnlyWhereTheContentFits(GameTestHelper helper) {
        PanelLook look = PanelLook.of(area("panel", null));
        if (!look.fits(427, 240) || !look.fits(854, 480)) {
            throw fail(helper, "the panel fits the default GUI and larger ones");
        }
        if (look.fits(320, 240) || look.fits(427, 200)) {
            throw fail(helper, "the panel does not fit a 320 x 240 GUI, nor one 200 high");
        }
        if (PanelLook.of(area("panel", "{ \"enabled\": 0 }")).fits(854, 480)) {
            throw fail(helper, "a panel that is off fits nowhere");
        }
        helper.succeed();
    }

    /** A pack switches the panel off by naming one key, and the terminals are the plain CRT again. */
    @GameTest
    public void aPackSwitchesThePanelOff(GameTestHelper helper) {
        if (PanelLook.of(area("panel", "{ \"enabled\": 0 }")).enabled()) {
            throw fail(helper, "a pack that names enabled 0 turns the panel off");
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
            throw fail(helper, "a pack names a key and keeps the rest, got " + look);
        }
        PanelLook.Decal plate = look.nameplate();
        if (plate.left(427) != 427 - 70 - 6 || plate.top(240) != 0) {
            throw fail(
                    helper,
                    "the nameplate sits 6 from the right and at the top, got " + plate.left(427) + ", "
                            + plate.top(240));
        }
        PanelLook.Decal strip = look.dressA();
        if (strip.left(427) != (427 - 30) / 2 - 10 || strip.top(240) != 240 - 20 - 3) {
            throw fail(
                    helper,
                    "the strip is centred, 10 left, and 3 above the bottom, got " + strip.left(427) + ", "
                            + strip.top(240));
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
            throw fail(
                    helper,
                    "a short label sits after the pip at 18, got " + left.showsPip(104, 60) + " and "
                            + left.labelStart(104, 60, true));
        }
        if (left.showsPip(104, 96)) {
            throw fail(helper, "a 96 pixel label does not fit beside the pip in a 104 pixel button");
        }
        if (left.labelStart(104, 60, false) != 4) {
            throw fail(
                    helper,
                    "on a screen with no pips a label starts at the pad, got " + left.labelStart(104, 60, false));
        }
        PanelLook centred = PanelLook.of(area("panel", "{ \"enabled\": 1, \"buttonAlign\": 0, \"pipSize\": 12 }"));
        if (centred.showsPip(104, 20) || centred.labelStart(104, 60, true) != 22) {
            throw fail(
                    helper,
                    "a centred label has no pip and starts in the middle, got " + centred.showsPip(104, 20) + " and "
                            + centred.labelStart(104, 60, true));
        }
        helper.succeed();
    }

    /** The cockpit HUD (#247): the mod ships the Ribbon and no camera, and a pack turns the Ribbon off by naming one key, keeping the mod's other keys. */
    @GameTest
    public void theRibbonIsOnInTheDefaultThemeAndAPackTurnsItOff(GameTestHelper helper) {
        CockpitLook shipped = CockpitLook.of(area("cockpit", null));
        if (!shipped.enabled() || !shipped.fits(320, 240)) {
            throw fail(
                    helper,
                    "the mod's own theme draws the Ribbon on a 320 x 240 GUI, the least vanilla's GUI scale leaves");
        }
        if (shipped.fits(319, 240) || shipped.fits(320, 239)) {
            throw fail(
                    helper,
                    "the Ribbon needs a GUI of at least " + shipped.minGuiWidth() + " x " + shipped.minGuiHeight()
                            + ", and falls back to the plain HUD under it");
        }
        if (shipped.camera().installed()) {
            throw fail(helper, "no pod has the front camera until a build sells the part");
        }
        CockpitLook off = CockpitLook.of(area("cockpit", "{ \"enabled\": 0 }"));
        if (off.enabled() || off.fits(1920, 1080)) {
            throw fail(helper, "a pack that names enabled 0 gets the plain HUD on any GUI");
        }
        if (off.fuelColor() != shipped.fuelColor()) {
            throw fail(helper, "a pack that names only enabled keeps the mod's colours");
        }
        if (!CockpitLook.of(area("cockpit", "{ \"cameraInstalled\": 1 }"))
                .camera()
                .installed()) {
            throw fail(helper, "a pack that names cameraInstalled puts the camera on the pod");
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
                throw fail(helper, "the Ribbon must have its five gauges, got " + gauge);
            }
        }
        if (!layout.lamps().on()) {
            throw fail(helper, "the Ribbon must have its warning lamps");
        }
        if (layout.scanner().width() != 98 || layout.scanner().height() != 82) {
            throw fail(
                    helper,
                    "the scanner window holds the slices of tiers 1 to 3 whole, 98 x 82, got " + layout.scanner());
        }
        if (!layout.sprite("plate").toString().equals("deepcharter:cockpit/ribbon/plate")) {
            throw fail(helper, "the plate sprite is deepcharter:cockpit/ribbon/plate, got " + layout.sprite("plate"));
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
                "cargo",
                "pod",
                "panel",
                "cockpit",
                "tow",
                "beam")) {
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
            throw fail(helper, "the pack's floodlight colour and length should apply, got " + look.floodlight());
        }
        if (look.headlamp().color() != 0xFFFFF0D2 || look.sides() != 14) {
            throw fail(helper, "the keys the pack did not name should keep the mod's values, got " + look);
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
        throw fail(helper, "a bad value for '" + key + "' was accepted");
    }

    private static void requireContains(GameTestHelper helper, String message, String part) {
        if (!message.contains(part)) {
            throw fail(helper, "message '%s' should name '%s'".formatted(message, part));
        }
    }

    private static void expect(GameTestHelper helper, String what, Object expected, Object actual) {
        if (!expected.equals(actual)) {
            throw fail(
                    helper,
                    "%s is not the pre-#226 look:%n  expected %s%n  actual   %s".formatted(what, expected, actual));
        }
    }

    private static RuntimeException fail(GameTestHelper helper, String message) {
        return helper.assertionException(Component.literal(message));
    }
}
