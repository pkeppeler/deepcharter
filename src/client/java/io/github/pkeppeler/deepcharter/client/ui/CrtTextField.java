package io.github.pkeppeler.deepcharter.client.ui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

/**
 * A single-line text field in phosphor green on black. Typing, selection and the cursor are the vanilla
 * {@link EditBox}'s; this class only draws the frame, which is brighter while the field has focus.
 */
public final class CrtTextField extends EditBox {
	/** Pixels the frame sits outside the widget rectangle, so the borderless text does not touch it. */
	private static final int FRAME = 3;

	public CrtTextField(Font font, int x, int y, int width, int height, Component hint) {
		super(font, x, y, width, height, hint);
		setBordered(false);
		setTextColor(CrtTuning.DEFAULT.phosphorColor());
		setTextShadow(false);
		setHint(hint);
	}

	@Override
	public void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		CrtTuning tuning = CrtTuning.DEFAULT;
		int left = getX() - FRAME;
		int top = getY() - FRAME;
		int right = getRight() + FRAME;
		int bottom = getBottom() + FRAME;
		graphics.fill(left, top, right, bottom, tuning.backgroundColor());
		CrtDraw.border(graphics, left, top, right, bottom, isFocused() ? tuning.phosphorColor() : tuning.dimColor());
		super.extractWidgetRenderState(graphics, mouseX, mouseY, partialTick);
	}
}
