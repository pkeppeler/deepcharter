package io.github.pkeppeler.deepcharter.client.theme;

import io.github.pkeppeler.deepcharter.theme.ThemeData;

/**
 * How the pod cargo screen looks ({@code theme/cargo.json}). The panel and the slots are GUI sprites, {@link #PANEL_SPRITE} and
 * {@link #SLOT_SPRITE}, which a resource pack replaces as it does any vanilla sprite; only the text colour is data.
 *
 * @param textColor the title and the mass line
 */
public record CargoLook(int textColor) {
	/** The nine-slice sprite of the panel, outline included, {@code textures/gui/sprites/cargo/panel.png}. */
	public static final String PANEL_SPRITE = "cargo/panel";
	/** The nine-slice sprite of one slot, outline included, {@code textures/gui/sprites/cargo/slot.png}. */
	public static final String SLOT_SPRITE = "cargo/slot";

	public static CargoLook current() {
		return UiTheme.current().cargo();
	}

	public static CargoLook of(ThemeData d) {
		return new CargoLook(d.color("textColor"));
	}
}
