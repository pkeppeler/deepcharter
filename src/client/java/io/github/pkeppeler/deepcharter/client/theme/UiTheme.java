package io.github.pkeppeler.deepcharter.client.theme;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.client.handbook.HandbookScreenTuning;
import io.github.pkeppeler.deepcharter.client.handbook.QuestMapTuning;
import io.github.pkeppeler.deepcharter.client.ui.CrtTuning;
import io.github.pkeppeler.deepcharter.theme.ThemeData;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Every art value of the client UI, as read from the resource packs by {@link UiThemeLoader} (ADR 0032): one typed record for each area
 * of {@code assets/deepcharter/theme/<area>.json}. A resource reload builds a whole new theme and swaps it in, so a draw call reads one
 * consistent look. Draw code asks for {@code CrtTuning.current()} and the like on every frame and never keeps the record.
 *
 * @param crt the terminal screens, their widgets and the CRT drawing helpers
 * @param handbook the handbook paper
 * @param questMap the flowchart of a chapter, drawn on the handbook paper
 * @param scanner the scanner minimap
 * @param hud the pod readout, the altimeter and the account line
 * @param transmission the transmission panel
 * @param breach the fade and jitter of a crossing
 * @param pod the paint palette of the pods' hulls
 * @param panel the machine panel round the terminals, on in the mod; a pack restyles it or switches it off
 * @param cockpit the cockpit HUD of a seated pilot: the Ribbon, until a pack turns it off
 * @param tow the chain links of the tow line between two pods
 * @param beam the fake light beams of a lit pod's headlamp and the colony floodlights
 * @param catalogue the size and place of the parts catalogue and its recipe panel, beside the inventory
 * @param readout the block name and hint above the hotbar
 * @param sonar the navigator's sonar scope
 * @param suit the suit gauge: the timer of a person on foot
 * @param continuity the Continuity Event: the skull that laughs at a person who has died, and its timings
 */
public record UiTheme(
        CrtTuning crt,
        HandbookScreenTuning handbook,
        QuestMapTuning questMap,
        ScannerLook scanner,
        HudLook hud,
        TransmissionLook transmission,
        BreachLook breach,
        PodPaintLook pod,
        PanelLook panel,
        CockpitLook cockpit,
        TowChainLook tow,
        BeamLook beam,
        CatalogueLook catalogue,
        ReadoutLook readout,
        SonarLook sonar,
        SuitLook suit,
        ContinuityLook continuity) {
    /** The areas, each one a file {@code theme/<name>.json}. */
    static final Set<String> AREAS = Set.of(
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
            "continuity");

    private static volatile @Nullable UiTheme current;

    /** The theme in force. Throws before the first resource reload has loaded one. */
    public static UiTheme current() {
        UiTheme theme = current;
        if (theme == null) {
            throw new IllegalStateException("The UI theme is not loaded yet: it loads with the client's resources");
        }
        return theme;
    }

    static void install(UiTheme theme) {
        current = theme;
    }

    /** An area of a map whose keys {@link #of} has already checked against {@link #AREAS}. */
    private static ThemeData area(Map<String, ThemeData> areas, String name) {
        ThemeData data = areas.get(name);
        if (data == null) {
            throw new IllegalStateException("The UI theme has no area '" + name + "'");
        }
        return data;
    }

    /** Builds the theme from its areas, which must all be there, and logs any key that nothing read. */
    public static UiTheme of(Map<String, ThemeData> areas) {
        if (!areas.keySet().equals(AREAS)) {
            throw new IllegalStateException("The UI theme needs the areas " + AREAS + ", has " + areas.keySet());
        }
        UiTheme theme = new UiTheme(
                CrtTuning.of(area(areas, "crt")),
                HandbookScreenTuning.of(area(areas, "handbook")),
                QuestMapTuning.of(area(areas, "handbook")),
                ScannerLook.of(area(areas, "scanner")),
                HudLook.of(area(areas, "hud")),
                TransmissionLook.of(area(areas, "transmission")),
                BreachLook.of(area(areas, "breach")),
                PodPaintLook.of(area(areas, "pod")),
                PanelLook.of(area(areas, "panel")),
                CockpitLook.of(area(areas, "cockpit")),
                TowChainLook.of(area(areas, "tow")),
                BeamLook.of(area(areas, "beam")),
                CatalogueLook.of(area(areas, "catalogue")),
                ReadoutLook.of(area(areas, "readout")),
                SonarLook.of(area(areas, "sonar")),
                SuitLook.of(area(areas, "suit")),
                ContinuityLook.of(area(areas, "continuity")));
        if (theme.transmission().maxWidth() <= 2 * theme.crt().padding()) {
            throw new IllegalArgumentException(area(areas, "transmission")
                            .conflict(
                                    "maxWidth must be more than twice the padding of the crt area, "
                                            + "or the panel has no room for text",
                                    "maxWidth")
                            .getMessage()
                    + "; "
                    + area(areas, "crt")
                            .conflict("padding is " + theme.crt().padding(), "padding")
                            .getMessage());
        }
        areas.forEach((name, data) -> {
            if (!data.unread().isEmpty()) {
                DeepCharter.LOGGER.warn("UI theme area '{}' has keys nothing reads (a typo?): {}", name, data.unread());
            }
        });
        return theme;
    }
}
