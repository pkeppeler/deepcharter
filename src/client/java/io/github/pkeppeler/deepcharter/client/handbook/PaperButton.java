package io.github.pkeppeler.deepcharter.client.handbook;

import io.github.pkeppeler.deepcharter.client.theme.Colors;
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
    private boolean selected;
    private boolean faded;
    private boolean joinsLeft;

    PaperButton(int x, int y, int width, int height, Component label, OnPress onPress) {
        super(x, y, width, height, label, onPress, DEFAULT_NARRATION);
    }

    void setSelected(boolean selected) {
        this.selected = selected;
    }

    /** A tab on the sheet's right edge: when selected, it runs on into the sheet on its left. */
    void setJoinsLeft() {
        joinsLeft = true;
    }

    /** A faded tab is for a chapter the player has not reached: it still opens, in the ink of a button that cannot be pressed. */
    void setFaded(boolean faded) {
        this.faded = faded;
    }

    /** The base class paints the vanilla sprite first; the fill here is opaque and covers it. */
    @Override
    protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        HandbookScreenTuning t = HandbookScreenTuning.current();
        boolean lit = isHoveredOrFocused() && active;
        graphics.fill(
                getX(),
                getY(),
                getRight(),
                getBottom(),
                selected ? t.paperColor() : lit ? t.hoverFillColor() : t.paperEdgeColor());
        PaperDraw.border(graphics, getX(), getY(), getRight(), getBottom(), Colors.opaque(t.paperEdgeColor()));
        Font font = Minecraft.getInstance().font;
        if (selected && joinsLeft) {
            // the open chapter's tab runs on into the sheet beside it
            graphics.fill(getX() - 1, getY() + 1, getX() + 1, getBottom() - 1, t.paperColor());
        }
        int ink = active && !faded ? t.inkColor() : t.disabledInkColor();
        Component label = PaperDraw.ink(getMessage(), ink);
        graphics.text(
                font,
                label,
                getX() + (getWidth() - font.width(label)) / 2,
                getY() + (getHeight() - font.lineHeight) / 2 + 1,
                Colors.opaque(ink),
                false);
    }
}
