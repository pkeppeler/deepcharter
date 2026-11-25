package io.github.pkeppeler.deepcharter.client.handbook.catalogue;

import io.github.pkeppeler.deepcharter.client.handbook.catalogue.CatalogueLayout.Rect;
import io.github.pkeppeler.deepcharter.client.theme.CatalogueLook;
import io.github.pkeppeler.deepcharter.client.ui.CrtTuning;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;

/**
 * The button that shows or hides the parts catalogue, where vanilla's recipe-book button stands on the screens the catalogue replaces it
 * on. It extends the vanilla {@link Button}, so focus, keys, narration and GameTest's {@code clickScreenButton} work as for any button.
 * Its tooltip names the catalogue and the key bound to {@link CatalogueKeys#TOGGLE} now, read each frame so a rebind shows.
 */
public final class CatalogueToggleButton extends Button {
    private final CatalogueState state;
    private String tooltipText = "";

    CatalogueToggleButton(int x, int y, CatalogueState state, OnPress onPress) {
        super(
                x,
                y,
                CatalogueLook.current().buttonWidth(),
                CatalogueLook.current().buttonHeight(),
                Component.translatable("key.deepcharter.catalogue.toggle"),
                onPress,
                DEFAULT_NARRATION);
        this.state = state;
        refreshTooltip();
    }

    // test-seam: the client test reads the tooltip's words, which a rebind must change
    public String tooltipText() {
        return tooltipText;
    }

    private void refreshTooltip() {
        String now = CatalogueKeys.TOGGLE.isUnbound()
                ? Component.translatable("deepcharter.catalogue.button.unbound").getString()
                : Component.translatable(
                                "deepcharter.catalogue.button",
                                CatalogueKeys.TOGGLE.getTranslatedKeyMessage().getString())
                        .getString();
        if (!now.equals(tooltipText)) {
            tooltipText = now;
            setTooltip(Tooltip.create(Component.literal(now)));
        }
    }

    /** The whole face: the base class draws no sprite of its own, so nothing of vanilla's button shows at the edges. */
    @Override
    protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        refreshTooltip();
        CatalogueHardware.toggleButton(
                graphics,
                CatalogueLook.current(),
                CrtTuning.current(),
                new Rect(getX(), getY(), getWidth(), getHeight()),
                !state.hidden(),
                isHoveredOrFocused());
    }
}
