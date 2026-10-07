package io.github.pkeppeler.deepcharter.client.handbook;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;

/** Drawing helpers of the paper look. Every colour comes from {@link HandbookScreenTuning}. */
final class PaperDraw {
	private static final HandbookScreenTuning T = HandbookScreenTuning.DEFAULT;
	private static final int OPAQUE = 0xFF000000;
	private static final int RULE_SPACING = 10;
	private static final int RULE_ALPHA = 0x40000000;
	private static final int SHADOW_OFFSET = 3;

	private PaperDraw() {
	}

	/** A sheet of paper with a drop shadow, faint ruled lines and a binding band down its left edge. */
	static void sheet(GuiGraphicsExtractor graphics, int left, int top, int width, int height) {
		graphics.fill(left + SHADOW_OFFSET, top + SHADOW_OFFSET, left + width + SHADOW_OFFSET, top + height + SHADOW_OFFSET, T.shadowColor());
		graphics.fill(left, top, left + width, top + height, T.paperColor());
		for (int y = top + RULE_SPACING * 2; y < top + height - RULE_SPACING; y += RULE_SPACING) {
			graphics.fill(left + T.bindingWidth(), y, left + width - 1, y + 1, (T.paperEdgeColor() & 0x00FFFFFF) | RULE_ALPHA);
		}
		graphics.fill(left, top, left + T.bindingWidth(), top + height, T.bindingColor());
		border(graphics, left, top, left + width, top + height, T.paperEdgeColor());
	}

	/** A one-pixel rectangle outline. */
	static void border(GuiGraphicsExtractor graphics, int left, int top, int right, int bottom, int color) {
		graphics.fill(left, top, right, top + 1, color);
		graphics.fill(left, bottom - 1, right, bottom, color);
		graphics.fill(left, top, left + 1, bottom, color);
		graphics.fill(right - 1, top, right, bottom, color);
	}

	/** {@code text} in italic ink of {@code color} (RGB; alpha is ignored): the vanilla italic font, coloured. */
	static Component ink(Component text, int color) {
		return text.copy().withStyle(style -> style.withItalic(true).withColor(color & 0x00FFFFFF));
	}

	/** Draws {@code text} wrapped to {@code width}. Returns the y below the last line. */
	static int wrapped(GuiGraphicsExtractor graphics, Font font, Component text, int x, int y, int width, int color) {
		int lineY = y;
		for (FormattedCharSequence line : font.split(ink(text, color), width)) {
			graphics.text(font, line, x, lineY, OPAQUE | color, false);
			lineY += font.lineHeight + 1;
		}
		return lineY;
	}

	/** Draws {@code text} centred on {@code centerX}, enlarged {@code scale} times. Returns the y below it. */
	static int centered(GuiGraphicsExtractor graphics, Font font, Component text, int centerX, int y, float scale, int color) {
		Component styled = ink(text, color);
		graphics.pose().pushMatrix();
		graphics.pose().translate(centerX, y);
		graphics.pose().scale(scale, scale);
		graphics.text(font, styled, -font.width(styled) / 2, 0, OPAQUE | color, false);
		graphics.pose().popMatrix();
		return y + Math.round(font.lineHeight * scale) + 2;
	}

	/** A rubber stamp: bold capitals in a double frame, turned by {@code degrees}. */
	static void stamp(GuiGraphicsExtractor graphics, Font font, Component text, int centerX, int centerY, float degrees, int color) {
		Component styled = text.copy().withStyle(Style.EMPTY.withBold(true).withColor(color & 0x00FFFFFF));
		int halfWidth = font.width(styled) / 2 + 5;
		int halfHeight = font.lineHeight / 2 + 3;
		graphics.pose().pushMatrix();
		graphics.pose().translate(centerX, centerY);
		graphics.pose().rotate((float) Math.toRadians(degrees));
		border(graphics, -halfWidth, -halfHeight, halfWidth, halfHeight, OPAQUE | color);
		border(graphics, -halfWidth + 2, -halfHeight + 2, halfWidth - 2, halfHeight - 2, OPAQUE | color);
		graphics.text(font, styled, -font.width(styled) / 2, -font.lineHeight / 2 + 1, OPAQUE | color, false);
		graphics.pose().popMatrix();
	}

	/** A solid black bar, like a word crossed out of a document before it is released. */
	static void redaction(GuiGraphicsExtractor graphics, int x, int y, int width, int height) {
		graphics.fill(x, y, x + width, y + height, T.redactionColor());
	}

	/**
	 * Draws {@code text} wrapped to {@code width}, in ink, with a black bar over each part between a pair of {@code ||} marks. The
	 * marks themselves are not drawn. A redacted part is always drawn as bars, never as text. Returns the y below the last line.
	 */
	static int redacted(GuiGraphicsExtractor graphics, Font font, String text, int x, int y, int width, int color) {
		int spaceWidth = font.width(" ");
		int cursor = x;
		int lineY = y;
		boolean previousRedacted = false;
		for (RedactionText.Token token : RedactionText.parse(text)) {
			Component word = ink(Component.literal(token.text()), color);
			int wordWidth = font.width(word);
			int gap = token.spaceBefore() ? spaceWidth : 0;
			if (cursor > x && cursor + gap + wordWidth > x + width) {
				cursor = x;
				lineY += font.lineHeight + 3;
				gap = 0;
				previousRedacted = false;
			}
			if (token.redacted()) {
				int barStart = previousRedacted ? cursor : cursor + gap;
				redaction(graphics, barStart, lineY - 1, cursor + gap + wordWidth - barStart, font.lineHeight + 1);
			} else {
				graphics.text(font, word, cursor + gap, lineY, OPAQUE | color, false);
			}
			cursor += gap + wordWidth;
			previousRedacted = token.redacted();
		}
		return lineY + font.lineHeight + 3;
	}

}
