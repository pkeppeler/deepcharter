package io.github.pkeppeler.deepcharter.client.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

import io.github.pkeppeler.deepcharter.client.theme.PanelLook;

/**
 * A flat phosphor-outlined button, or with the machine panel on ({@link PanelLook}) a button drawn from the pack's button sprites. It
 * extends the vanilla {@link Button}, so focus, keys, narration and GameTest's {@code clickScreenButton} work as for any button.
 */
public final class CrtButton extends Button {
	public CrtButton(int x, int y, int width, int height, Component label, OnPress onPress) {
		super(x, y, width, height, label, onPress, DEFAULT_NARRATION);
	}

	/** The base class paints the vanilla sprite first; the fill here is opaque and covers it. */
	@Override
	protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		CrtTuning tuning = CrtTuning.current();
		boolean lit = isHoveredOrFocused() && active;
		Font font = Minecraft.getInstance().font;
		PanelLook panel = PanelLook.current();
		if (panel.enabled()) {
			int labelX = CrtDraw.panelButtonFace(graphics, panel, getX(), getY(), getWidth(), getHeight(), active, lit);
			String text = getMessage().getString();
			int x = panel.buttonAlign() == 0 ? getX() + (getWidth() - CrtText.width(font, text)) / 2 : labelX;
			int y = getY() + (getHeight() - font.lineHeight) / 2 + tuning.buttonLabelOffset();
			int ink = !active ? panel.buttonLabelOffColor() : lit ? panel.buttonLabelHotColor() : panel.buttonLabelColor();
			graphics.text(font, CrtText.of(text), x, y, ink, false);
			return;
		}
		graphics.fill(getX(), getY(), getRight(), getBottom(), lit ? tuning.hoverFillColor() : tuning.backgroundColor());
		CrtDraw.border(graphics, getX(), getY(), getRight(), getBottom(), lit ? tuning.phosphorColor() : tuning.dimColor());
		String label = getMessage().getString();
		int x = getX() + (getWidth() - font.width(label)) / 2;
		int y = getY() + (getHeight() - font.lineHeight) / 2 + tuning.buttonLabelOffset();
		CrtDraw.glowText(graphics, font, label, x, y, active ? tuning.phosphorColor() : tuning.dimColor());
	}
}
