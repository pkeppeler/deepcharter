package io.github.pkeppeler.deepcharter.client.ui;

import java.util.List;

import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.client.theme.PanelLook;

/**
 * The text of the CRT terminals in the terminal font ({@code font/terminal.json}, the id {@link #FONT}). While the machine panel is off the
 * style is empty and the text is the game's own font, so normal play does not change; with the panel on, every measure and every draw of
 * terminal text goes through this class, so a wrap, a centred label and a cursor all use the width of the font that is drawn.
 */
public final class CrtText {
	/** The font id of every terminal's text. A pack points it at a font file. */
	public static final Identifier FONT = Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "terminal");

	private CrtText() {
	}

	/** The style of terminal text: the terminal font with the panel on, nothing otherwise. */
	public static Style style() {
		return PanelLook.current().enabled() ? Style.EMPTY.withFont(new FontDescription.Resource(FONT)) : Style.EMPTY;
	}

	/** {@code text} as a component in the terminal style. */
	public static Component of(String text) {
		return Component.literal(text).withStyle(style());
	}

	/** The width of {@code text} in the terminal style. */
	public static int width(Font font, String text) {
		return font.width(of(text));
	}

	/** {@code text} wrapped to {@code width} pixels in the terminal style. */
	public static List<String> wrap(Font font, String text, int width) {
		return font.getSplitter().splitLines(FormattedText.of(text, style()), width, Style.EMPTY).stream().map(FormattedText::getString).toList();
	}
}
