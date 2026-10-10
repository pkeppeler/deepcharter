package io.github.pkeppeler.deepcharter.client.ui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import io.github.pkeppeler.deepcharter.client.theme.Colors;
import io.github.pkeppeler.deepcharter.client.theme.PanelLook;

/** Drawing helpers of the CRT look, shared by {@link CrtScreen} and the widgets. All colours come from {@link CrtTuning}. */
public final class CrtDraw {
	private CrtDraw() {
	}

	/** Near-black background with a faint phosphor bloom along the top and bottom edges. */
	public static void backdrop(GuiGraphicsExtractor graphics, int width, int height) {
		backdrop(graphics, 0, 0, width, height);
	}

	/** The backdrop in the rectangle from {@code left}, {@code top} to {@code right}, {@code bottom}: the CRT glass of a panel. */
	public static void backdrop(GuiGraphicsExtractor graphics, int left, int top, int right, int bottom) {
		CrtTuning tuning = CrtTuning.current();
		graphics.fill(left, top, right, bottom, tuning.backgroundColor());
		int transparent = Colors.rgb(tuning.bloomColor());
		int bloom = Math.min(tuning.bloomHeight(), (bottom - top) / 2);
		graphics.fillGradient(left, top, right, top + bloom, tuning.bloomColor(), transparent);
		graphics.fillGradient(left, bottom - bloom, right, bottom, transparent, tuning.bloomColor());
	}

	/** One dark line every {@link CrtTuning#scanlineSpacing()} pixels, drawn over everything. */
	public static void scanlines(GuiGraphicsExtractor graphics, int width, int height) {
		scanlines(graphics, 0, 0, width, height);
	}

	/** The scanlines in a rectangle: the CRT glass of a panel. */
	public static void scanlines(GuiGraphicsExtractor graphics, int left, int top, int right, int bottom) {
		CrtTuning tuning = CrtTuning.current();
		for (int y = top; y < bottom; y += tuning.scanlineSpacing()) {
			graphics.fill(left, y, right, y + 1, tuning.scanlineColor());
		}
	}

	/**
	 * What is behind the content of a screen with the machine panel on: the wall, the phosphor backdrop in the glass rectangle, the frame
	 * sprite over the whole screen and the decals. The frame's middle is see-through, so the glass shows in it.
	 */
	public static void panelBackground(GuiGraphicsExtractor graphics, PanelLook panel, int width, int height) {
		graphics.fill(0, 0, width, height, panel.wallColor());
		PanelLook.Insets glass = panel.glass();
		backdrop(graphics, glass.left(), glass.top(), width - glass.right(), height - glass.bottom());
		sprite(graphics, PanelLook.FRAME, 0, 0, width, height);
		decal(graphics, PanelLook.NAMEPLATE, panel.nameplate(), width, height);
		decal(graphics, PanelLook.DRESS_A, panel.dressA(), width, height);
		decal(graphics, PanelLook.DRESS_B, panel.dressB(), width, height);
		decal(graphics, PanelLook.DRESS_C, panel.dressC(), width, height);
		decal(graphics, PanelLook.DRESS_D, panel.dressD(), width, height);
	}

	/** What goes over the content of a screen with the machine panel on: the glass sprite, then the scanlines, both inside the glass rectangle. */
	public static void panelGlass(GuiGraphicsExtractor graphics, PanelLook panel, int width, int height) {
		PanelLook.Insets glass = panel.glass();
		int right = width - glass.right();
		int bottom = height - glass.bottom();
		sprite(graphics, PanelLook.GLASS, glass.left(), glass.top(), right - glass.left(), bottom - glass.top());
		scanlines(graphics, glass.left(), glass.top(), right, bottom);
	}

	/**
	 * The face of a button of the machine panel: the under-fill, the button sprite for its state and, when the label leaves room for it
	 * ({@link PanelLook#showsPip}), the pip. Returns the x where the label starts.
	 */
	public static int panelButtonFace(GuiGraphicsExtractor graphics, PanelLook panel, int x, int y, int width, int height, boolean active, boolean lit,
			int labelWidth) {
		graphics.fill(x, y, x + width, y + height, panel.buttonUnderColor());
		sprite(graphics, !active ? PanelLook.BUTTON_OFF : lit ? PanelLook.BUTTON_HOT : PanelLook.BUTTON, x, y, width, height);
		if (panel.showsPip(width, labelWidth)) {
			sprite(graphics, !active ? PanelLook.PIP_OFF : lit ? PanelLook.PIP_HOT : PanelLook.PIP, x + panel.pipX(),
					y + (height - panel.pipSize()) / 2 + panel.pipY(), panel.pipSize(), panel.pipSize());
		}
		return x + panel.labelStart(width, labelWidth);
	}

	private static void decal(GuiGraphicsExtractor graphics, Identifier sprite, PanelLook.Decal decal, int width, int height) {
		if (decal.on()) {
			sprite(graphics, sprite, decal.left(width), decal.top(height), decal.width(), decal.height());
		}
	}

	/** A GUI sprite stretched, or cut in nine if its metadata says so, over a rectangle. */
	public static void sprite(GuiGraphicsExtractor graphics, Identifier sprite, int x, int y, int width, int height) {
		graphics.blitSprite(RenderPipelines.GUI_TEXTURED, sprite, x, y, width, height);
	}

	/** Text with a halo: a dim copy one pixel out in each direction, then the bright text on top. */
	public static void glowText(GuiGraphicsExtractor graphics, Font font, String text, int x, int y, int color) {
		int halo = CrtTuning.current().glowColor();
		Component styled = CrtText.of(text);
		graphics.text(font, styled, x - 1, y, halo, false);
		graphics.text(font, styled, x + 1, y, halo, false);
		graphics.text(font, styled, x, y - 1, halo, false);
		graphics.text(font, styled, x, y + 1, halo, false);
		graphics.text(font, styled, x, y, color, false);
	}

	/** A one-pixel rectangle outline. */
	public static void border(GuiGraphicsExtractor graphics, int left, int top, int right, int bottom, int color) {
		graphics.fill(left, top, right, top + 1, color);
		graphics.fill(left, bottom - 1, right, bottom, color);
		graphics.fill(left, top, left + 1, bottom, color);
		graphics.fill(right - 1, top, right, bottom, color);
	}

	/**
	 * A screen's title in capitals in bright phosphor at {@code margin} (from the left and from {@code top}), and the dim rule under it,
	 * which runs from {@code margin} to {@code right}.
	 */
	public static void header(GuiGraphicsExtractor graphics, Font font, String title, int left, int top, int right) {
		CrtTuning tuning = CrtTuning.current();
		glowText(graphics, font, title, left, top, tuning.phosphorColor());
		int ruleTop = top + font.lineHeight + tuning.headerRuleGap();
		border(graphics, left - tuning.headerRuleInset(), ruleTop, right + tuning.headerRuleInset(), ruleTop + 1, tuning.dimColor());
	}
}
