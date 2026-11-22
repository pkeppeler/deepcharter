package io.github.pkeppeler.deepcharter.client.ui;

import io.github.pkeppeler.deepcharter.client.theme.Colors;
import io.github.pkeppeler.deepcharter.client.theme.PanelLook;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/** Drawing helpers of the CRT look, shared by {@link CrtScreen} and the widgets. All colours come from {@link CrtTuning}. */
public final class CrtDraw {
    private CrtDraw() {}

    /** Near-black background with a faint phosphor bloom along the top and bottom edges. */
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
        roundedScanlines(graphics, 0, 0, width, height, 0);
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

    /**
     * What goes over the content of a screen with the machine panel on: the glass sprite, then the scanlines, both inside the glass rectangle.
     * The sprite is drawn in four bands round the content rectangle, never over it, so its corner shading cannot dim the first stem of a title
     * that starts near the top-left corner (#1012). The scanlines still cross the content: they are the CRT look the text is read through.
     */
    public static void panelGlass(GuiGraphicsExtractor graphics, PanelLook panel, int width, int height) {
        PanelLook.Insets glass = panel.glass();
        PanelLook.Insets content = panel.content();
        int right = width - glass.right();
        int bottom = height - glass.bottom();
        int contentLeft = Math.max(glass.left(), content.left());
        int contentTop = Math.max(glass.top(), content.top());
        int contentRight = Math.min(right, width - content.right());
        int contentBottom = Math.min(bottom, height - content.bottom());
        glassBand(graphics, glass, right, bottom, glass.left(), glass.top(), right, contentTop);
        glassBand(graphics, glass, right, bottom, glass.left(), contentBottom, right, bottom);
        glassBand(graphics, glass, right, bottom, glass.left(), contentTop, contentLeft, contentBottom);
        glassBand(graphics, glass, right, bottom, contentRight, contentTop, right, contentBottom);
        roundedScanlines(graphics, glass.left(), glass.top(), right, bottom, panel.glassRadius());
    }

    /** The glass sprite, whole, clipped to one band of its rectangle. */
    private static void glassBand(
            GuiGraphicsExtractor graphics,
            PanelLook.Insets glass,
            int right,
            int bottom,
            int bandLeft,
            int bandTop,
            int bandRight,
            int bandBottom) {
        if (bandRight <= bandLeft || bandBottom <= bandTop) {
            return;
        }
        graphics.enableScissor(bandLeft, bandTop, bandRight, bandBottom);
        sprite(graphics, PanelLook.GLASS, glass.left(), glass.top(), right - glass.left(), bottom - glass.top());
        graphics.disableScissor();
    }

    /** The scanlines in a rectangle with rounded corners: each line stops where the glass does ({@link PanelLook#scanlineInset}). */
    private static void roundedScanlines(
            GuiGraphicsExtractor graphics, int left, int top, int right, int bottom, int radius) {
        CrtTuning tuning = CrtTuning.current();
        for (int y = top; y < bottom; y += tuning.scanlineSpacing()) {
            int inset = PanelLook.scanlineInset(radius, y - top, right - left, bottom - top);
            graphics.fill(left + inset, y, right - inset, y + 1, tuning.scanlineColor());
        }
    }

    /**
     * The face of a button of the machine panel: the under-fill, the button sprite for its state and, when {@code pips} is set (the whole
     * screen has room for pips, {@link #pipsFit}), the pip. Returns the x where the label starts.
     */
    public static int panelButtonFace(
            GuiGraphicsExtractor graphics,
            PanelLook panel,
            int x,
            int y,
            int width,
            int height,
            boolean active,
            boolean lit,
            int labelWidth,
            boolean pips) {
        graphics.fill(x, y, x + width, y + height, panel.buttonUnderColor());
        sprite(
                graphics,
                !active ? PanelLook.BUTTON_OFF : lit ? PanelLook.BUTTON_HOT : PanelLook.BUTTON,
                x,
                y,
                width,
                height);
        if (pips && panel.pipSize() > 0 && panel.buttonAlign() == 1) {
            sprite(
                    graphics,
                    !active ? PanelLook.PIP_OFF : lit ? PanelLook.PIP_HOT : PanelLook.PIP,
                    x + panel.pipX(),
                    y + (height - panel.pipSize()) / 2 + panel.pipY(),
                    panel.pipSize(),
                    panel.pipSize());
        }
        return x + panel.labelStart(width, labelWidth, pips);
    }

    /**
     * Whether every button of {@code screen} has room for its pip beside its label in the terminal font. A screen draws pips on all of its
     * buttons or on none: a pip on most of them and not on one reads as a fault.
     */
    public static boolean pipsFit(PanelLook panel, Font font, Screen screen) {
        for (GuiEventListener child : screen.children()) {
            if (child instanceof PanelButton button
                    && child instanceof AbstractWidget widget
                    && widget.visible
                    && !panel.showsPip(widget.getWidth(), button.panelLabelWidth(font))) {
                return false;
            }
        }
        return true;
    }

    private static void decal(
            GuiGraphicsExtractor graphics, Identifier sprite, PanelLook.Decal decal, int width, int height) {
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
        border(
                graphics,
                left - tuning.headerRuleInset(),
                ruleTop,
                right + tuning.headerRuleInset(),
                ruleTop + 1,
                tuning.dimColor());
    }
}
