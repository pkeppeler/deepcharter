package io.github.pkeppeler.deepcharter.client.handbook;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

/**
 * A button or tab of the handbook: a slip of paper with an ink label. It extends the vanilla {@link Button}, so focus, keys,
 * narration and GameTest's {@code clickScreenButton} work as for any button. A tab that is {@link #selected} is drawn as part of the
 * sheet.
 */
final class PaperButton extends Button {
	private static final HandbookScreenTuning T = HandbookScreenTuning.DEFAULT;
	private static final int OPAQUE = 0xFF000000;
	private static final int HOVER_FILL = 0xFFFFF4D6;
	private static final int DISABLED_INK = 0xFF9A9078;

	private boolean selected;

	PaperButton(int x, int y, int width, int height, Component label, OnPress onPress) {
		super(x, y, width, height, label, onPress, DEFAULT_NARRATION);
	}

	void setSelected(boolean selected) {
		this.selected = selected;
	}

	/** The base class paints the vanilla sprite first; the fill here is opaque and covers it. */
	@Override
	protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		boolean lit = isHoveredOrFocused() && active;
		graphics.fill(getX(), getY(), getRight(), getBottom(), selected || lit ? (lit && !selected ? HOVER_FILL : T.paperColor()) : T.paperEdgeColor());
		PaperDraw.border(graphics, getX(), getY(), getRight(), getBottom(), T.paperEdgeColor() | OPAQUE);
		Font font = Minecraft.getInstance().font;
		Component label = PaperDraw.ink(getMessage(), active ? T.inkColor() : DISABLED_INK);
		graphics.text(font, label, getX() + (getWidth() - font.width(label)) / 2, getY() + (getHeight() - font.lineHeight) / 2 + 1,
				OPAQUE | (active ? T.inkColor() : DISABLED_INK), false);
	}
}
