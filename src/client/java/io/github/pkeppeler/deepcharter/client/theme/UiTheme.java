package io.github.pkeppeler.deepcharter.client.theme;

import java.util.Map;
import java.util.Set;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.client.handbook.HandbookScreenTuning;
import io.github.pkeppeler.deepcharter.client.ui.CrtTuning;
import io.github.pkeppeler.deepcharter.theme.ThemeData;

/**
 * Every art value of the client UI, as read from the resource packs by {@link UiThemeLoader} (ADR 0032): one typed record for each area
 * of {@code assets/deepcharter/theme/<area>.json}. A resource reload builds a whole new theme and swaps it in, so a draw call reads one
 * consistent look. Draw code asks for {@code CrtTuning.current()} and the like on every frame and never keeps the record.
 *
 * @param crt the terminal screens, their widgets and the CRT drawing helpers
 * @param handbook the handbook paper
 * @param scanner the scanner minimap
 * @param hud the pod readout, the altimeter and the account line
 * @param transmission the transmission panel
 * @param breach the fade and jitter of a crossing
 * @param cargo the pod cargo screen
 */
public record UiTheme(
		CrtTuning crt,
		HandbookScreenTuning handbook,
		ScannerLook scanner,
		HudLook hud,
		TransmissionLook transmission,
		BreachLook breach,
		CargoLook cargo) {
	/** The areas, each one a file {@code theme/<name>.json}. */
	static final Set<String> AREAS = Set.of("crt", "handbook", "scanner", "hud", "transmission", "breach", "cargo");

	private static volatile UiTheme current;

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

	/** Builds the theme from its areas, which must all be there, and logs any key that nothing read. */
	public static UiTheme of(Map<String, ThemeData> areas) {
		if (!areas.keySet().equals(AREAS)) {
			throw new IllegalStateException("The UI theme needs the areas " + AREAS + ", has " + areas.keySet());
		}
		UiTheme theme = new UiTheme(
				CrtTuning.of(areas.get("crt")),
				HandbookScreenTuning.of(areas.get("handbook")),
				ScannerLook.of(areas.get("scanner")),
				HudLook.of(areas.get("hud")),
				TransmissionLook.of(areas.get("transmission")),
				BreachLook.of(areas.get("breach")),
				CargoLook.of(areas.get("cargo")));
		if (theme.transmission().maxWidth() <= 2 * theme.crt().padding()) {
			throw new IllegalArgumentException(areas.get("transmission").conflict("maxWidth must be more than twice the padding of the crt area, "
					+ "or the panel has no room for text", "maxWidth").getMessage()
					+ "; " + areas.get("crt").conflict("padding is " + theme.crt().padding(), "padding").getMessage());
		}
		areas.forEach((name, data) -> {
			if (!data.unread().isEmpty()) {
				DeepCharter.LOGGER.warn("UI theme area '{}' has keys nothing reads (a typo?): {}", name, data.unread());
			}
		});
		return theme;
	}
}
