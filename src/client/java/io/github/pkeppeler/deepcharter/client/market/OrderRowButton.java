package io.github.pkeppeler.deepcharter.client.market;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

import io.github.pkeppeler.deepcharter.client.theme.PanelLook;
import io.github.pkeppeler.deepcharter.client.ui.CrtDraw;
import io.github.pkeppeler.deepcharter.client.ui.CrtText;
import io.github.pkeppeler.deepcharter.client.ui.CrtTuning;

/**
 * One work order of the ore processor screen: a phosphor-outlined button, as {@link io.github.pkeppeler.deepcharter.client.ui.CrtButton},
 * that shows the order's title on its first line and its progress on its second. Its message is the "DELIVER ORE" label, which is what
 * the narrator and GameTest's {@code clickScreenButton} read. A finished order is inactive.
 */
public final class OrderRowButton extends Button {
	/** The two lines fit in a button of the standard height. */
	static final int HEIGHT = 20;
	private static final int PADDING = 4;

	private final String title;
	private final String progress;

	OrderRowButton(int x, int y, int width, Component message, String title, String progress, boolean open, OnPress onPress) {
		super(x, y, width, HEIGHT, message, onPress, DEFAULT_NARRATION);
		this.title = title;
		this.progress = progress;
		this.active = open;
	}

	public String title() {
		return title;
	}

	public String progress() {
		return progress;
	}

	@Override
	protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		CrtTuning tuning = CrtTuning.current();
		boolean lit = isHoveredOrFocused() && active;
		PanelLook panel = PanelLook.current();
		if (panel.enabled()) {
			Font panelFont = Minecraft.getInstance().font;
			int labelX = CrtDraw.panelButtonFace(graphics, panel, getX(), getY(), getWidth(), getHeight(), active, lit,
					Math.max(CrtText.width(panelFont, title), CrtText.width(panelFont, progress)));
			int ink = !active ? panel.buttonLabelOffColor() : lit ? panel.buttonLabelHotColor() : panel.buttonLabelColor();
			graphics.text(panelFont, CrtText.of(title), labelX, getY() + 1, ink, false);
			graphics.text(panelFont, CrtText.of(progress), labelX, getY() + 2 + panelFont.lineHeight, panel.buttonLabelOffColor(), false);
			return;
		}
		graphics.fill(getX(), getY(), getRight(), getBottom(), lit ? tuning.hoverFillColor() : tuning.backgroundColor());
		CrtDraw.border(graphics, getX(), getY(), getRight(), getBottom(), lit ? tuning.phosphorColor() : tuning.dimColor());
		Font font = Minecraft.getInstance().font;
		int color = active ? tuning.phosphorColor() : tuning.dimColor();
		CrtDraw.glowText(graphics, font, title, getX() + PADDING, getY() + 1, color);
		CrtDraw.glowText(graphics, font, progress, getX() + PADDING, getY() + 2 + font.lineHeight, tuning.dimColor());
	}
}
