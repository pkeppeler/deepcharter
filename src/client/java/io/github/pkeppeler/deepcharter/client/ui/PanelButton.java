package io.github.pkeppeler.deepcharter.client.ui;

import net.minecraft.client.gui.Font;

/** A button the machine panel draws with a pip: it says how wide its widest line of label is, so a screen can tell whether every pip fits ({@link CrtDraw#pipsFit}). */
public interface PanelButton {
	/** The width of the widest line of this button's label in the terminal font. */
	int panelLabelWidth(Font font);
}
