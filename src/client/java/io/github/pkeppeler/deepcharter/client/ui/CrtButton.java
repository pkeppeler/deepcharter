package io.github.pkeppeler.deepcharter.client.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.network.chat.Component;

/** A flat phosphor-outlined button. It lights up under the mouse and on keyboard focus, and runs its action once per press. */
public final class CrtButton extends AbstractButton {
	private final Runnable action;

	public CrtButton(int x, int y, int width, int height, Component label, Runnable action) {
		super(x, y, width, height, label);
		this.action = action;
	}

	@Override
	public void onPress(InputWithModifiers input) {
		action.run();
	}

	/** Draws over the vanilla button sprite, which the base class paints first: the fill is opaque. */
	@Override
	protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		CrtTuning tuning = CrtTuning.DEFAULT;
		boolean lit = isHoveredOrFocused() && active;
		int color = !active ? tuning.dimColor() : tuning.phosphorColor();
		graphics.fill(getX(), getY(), getRight(), getBottom(), lit ? tuning.hoverFillColor() : tuning.backgroundColor());
		CrtDraw.border(graphics, getX(), getY(), getRight(), getBottom(), lit ? tuning.phosphorColor() : tuning.dimColor());
		Font font = Minecraft.getInstance().font;
		String label = getMessage().getString();
		int x = getX() + (getWidth() - font.width(label)) / 2;
		int y = getY() + (getHeight() - font.lineHeight) / 2 + 1;
		CrtDraw.glowText(graphics, font, label, x, y, color);
	}

	@Override
	protected void updateWidgetNarration(NarrationElementOutput output) {
		defaultButtonNarrationText(output);
	}
}
