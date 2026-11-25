package io.github.pkeppeler.deepcharter.client.handbook.catalogue;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.pkeppeler.deepcharter.client.handbook.catalogue.CatalogueLayout.Rect;
import io.github.pkeppeler.deepcharter.client.handbook.catalogue.SpecSheetView.Placed;
import io.github.pkeppeler.deepcharter.client.theme.CatalogueLook;
import io.github.pkeppeler.deepcharter.client.ui.CrtTuning;
import io.github.pkeppeler.deepcharter.handbook.SpecSheet;
import io.github.pkeppeler.deepcharter.handbook.SpecSheets;
import java.util.Objects;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import org.jspecify.annotations.Nullable;

/**
 * An item's spec sheet on a screen of its own, in the recipe console's look: where a sheet pops up when no inventory with the parts
 * catalogue is open to hold it (nothing open, or a window too small for the catalogue's consoles, once the screen over it has
 * closed). Any key, the close switch and a click off the console close it, so the next sheet waiting ({@link SpecSheetOpener}) can
 * follow; the Recipes link closes it and shows the item's recipes in the catalogue, over the inventory.
 */
public final class SpecSheetScreen extends Screen {
    private final SpecSheet sheet;
    private @Nullable Placed placed;

    public SpecSheetScreen(Item item) {
        super(Component.translatable("deepcharter.catalogue.spec"));
        this.sheet = SpecSheets.of(item);
    }

    @Override
    protected void init() {
        placed = SpecSheetView.place(sheet, width, height, CatalogueLook.current(), CrtTuning.current(), font);
    }

    /** The glass of the sheet's console, where all its text must lie; null before the screen has opened. */
    // test-seam: the client test holds every string of every item's sheet to this glass
    public @Nullable Rect glass() {
        Placed now = placed;
        return now == null ? null : now.layout().glass();
    }

    /** The Recipes link; null before the screen has opened. */
    // test-seam: the client test clicks the link
    public @Nullable Rect recipesLink() {
        Placed now = placed;
        return now == null ? null : now.layout().recipes();
    }

    /** Whether the Recipes link does anything: the window has room for the catalogue it opens. */
    // test-seam: the client test reads whether the link is lit
    public boolean recipesLinkActive() {
        return CatalogueScreens.canOpen();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        Placed now = Objects.requireNonNull(placed, "<built by init() when the screen opens>");
        SpecSheetView.draw(graphics, font, now.layout(), sheet, now.plan(), mouseX, mouseY, CatalogueScreens.canOpen());
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        Placed now = Objects.requireNonNull(placed, "<built by init() when the screen opens>");
        switch (SpecSheetView.clickAt(now.layout(), event.x(), event.y())) {
            case RECIPES -> {
                // A window too small for the catalogue has nothing to show, so the link is dim and a press keeps
                // the sheet.
                if (CatalogueScreens.canOpen()) {
                    onClose();
                    CatalogueScreens.open(sheet.item());
                }
            }
            case CLOSE -> onClose();
            case NONE -> {}
        }
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.isEscape() || !isModifier(event)) {
            onClose();
            return true;
        }
        return super.keyPressed(event);
    }

    /** A key that is only a modifier, which closes nothing. */
    private static boolean isModifier(KeyEvent event) {
        int key = event.key();
        return key == InputConstants.KEY_LSHIFT
                || key == InputConstants.KEY_RSHIFT
                || key == InputConstants.KEY_LCONTROL
                || key == InputConstants.KEY_RCONTROL
                || key == InputConstants.KEY_LALT
                || key == InputConstants.KEY_RALT;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
