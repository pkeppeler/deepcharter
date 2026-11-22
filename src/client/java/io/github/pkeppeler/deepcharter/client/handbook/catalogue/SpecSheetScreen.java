package io.github.pkeppeler.deepcharter.client.handbook.catalogue;

import io.github.pkeppeler.deepcharter.client.handbook.catalogue.CatalogueLayout.Rect;
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
 * catalogue is open to hold it (a part bought at a terminal, a window too small for the catalogue's consoles). Any click or key but a
 * modifier closes it, and the screen under it, if any, is not touched.
 */
public final class SpecSheetScreen extends Screen {
    /** Where the sheet's console is and what it says there, worked out for the window's size. */
    private record Shown(SpecSheetLayout layout, SpecSheetView.Plan plan) {}

    private final SpecSheet sheet;
    private @Nullable Shown shown;

    public SpecSheetScreen(Item item) {
        super(Component.translatable("deepcharter.catalogue.spec"));
        this.sheet = SpecSheets.of(item);
    }

    @Override
    protected void init() {
        SpecSheetLayout layout =
                SpecSheetLayout.of(width, height, CatalogueLook.current(), CrtTuning.current(), font.lineHeight);
        shown = new Shown(layout, SpecSheetView.plan(sheet, layout, CatalogueLook.current(), font));
    }

    /** The glass of the sheet's console, where all its text must lie; null before the screen has opened. */
    // test-seam: the client test holds every string of every item's sheet to this glass
    public @Nullable Rect glass() {
        Shown now = shown;
        return now == null ? null : now.layout().glass();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        Shown now = Objects.requireNonNull(shown, "<built by init() when the screen opens>");
        SpecSheetView.draw(graphics, font, now.layout(), sheet, now.plan(), mouseX, mouseY);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        onClose();
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.isEscape() || event.isConfirmation()) {
            onClose();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
