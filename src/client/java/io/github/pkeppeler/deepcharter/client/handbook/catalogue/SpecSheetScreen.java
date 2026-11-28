package io.github.pkeppeler.deepcharter.client.handbook.catalogue;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.pkeppeler.deepcharter.client.handbook.catalogue.CatalogueLayout.Rect;
import io.github.pkeppeler.deepcharter.client.handbook.catalogue.SpecSheetView.Placed;
import io.github.pkeppeler.deepcharter.client.theme.CatalogueLook;
import io.github.pkeppeler.deepcharter.client.ui.CrtTuning;
import io.github.pkeppeler.deepcharter.handbook.SpecSheet;
import io.github.pkeppeler.deepcharter.handbook.SpecSheets;
import java.util.Objects;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import org.jspecify.annotations.Nullable;

/**
 * An item's spec sheet on a screen of its own, in the recipe console's look: where a sheet pops up when no inventory
 * with the parts catalogue is open to hold it (nothing open, or a window too small for the catalogue's consoles, once
 * the screen over it has closed), and where the spec sheet key opens one over a screen with no catalogue (a chest, the
 * pod screen, a terminal), which comes back when it closes. Any key, the close switch and a click off the console close
 * it, so the next sheet waiting ({@link SpecSheetOpener}) can follow; the Recipes link closes it and shows the item's
 * recipes in the catalogue, over the inventory.
 */
public final class SpecSheetScreen extends Screen {
    private final @Nullable Screen parent;
    private final Item item;
    private final boolean latest;
    private final SpecSheet sheet;
    private @Nullable Placed placed;
    /** Whether the spec sheet key that opened the sheet has not been let go yet: its repeats close nothing. */
    private boolean keyHeld;

    public SpecSheetScreen(Item item) {
        this(null, SheetRequest.of(item));
    }

    /** The sheet {@code request} asks for over {@code parent}, which comes back when it closes (none: the game). */
    SpecSheetScreen(@Nullable Screen parent, SheetRequest request) {
        super(Component.translatable("deepcharter.catalogue.spec"));
        this.parent = parent;
        this.item = request.item();
        this.latest = request.latest();
        this.keyHeld = request.keyHeld();
        this.sheet = SpecSheets.of(item);
    }

    /** The item whose sheet this screen shows. */
    Item item() {
        return item;
    }

    @Override
    protected void init() {
        placed = SpecSheetView.place(sheet, latest, width, height, CatalogueLook.current(), CrtTuning.current(), font);
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

    /** Whether the Recipes link does anything: the window has room for the recipe console or the catalogue it opens. */
    // test-seam: the client test reads whether the link is lit
    public boolean recipesLinkActive() {
        return recipesOnAConsole() ? RecipeConsoleScreen.fits(width, height) : CatalogueScreens.canOpen();
    }

    /**
     * Whether the Recipes link shows the recipes on a console of their own over the screen under the sheet, as R over a
     * chest's slot does: under the sheet is a container (a chest, the pod screen), whose menu the inventory the
     * catalogue stands beside would close, or a recipe console.
     */
    private boolean recipesOnAConsole() {
        return parent instanceof AbstractContainerScreen<?> || parent instanceof RecipeConsoleScreen;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        Placed now = Objects.requireNonNull(placed, "<built by init() when the screen opens>");
        SpecSheetView.draw(graphics, font, now.layout(), sheet, now.plan(), mouseX, mouseY, recipesLinkActive());
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        Placed now = Objects.requireNonNull(placed, "<built by init() when the screen opens>");
        switch (SpecSheetView.clickAt(now.layout(), event.x(), event.y())) {
            case RECIPES -> {
                // A window too small for what the link opens has nothing to show, so the link is dim and a press
                // keeps the sheet.
                if (!recipesLinkActive()) {
                    return true;
                }
                if (recipesOnAConsole()) {
                    Minecraft.getInstance()
                            .gui
                            .setScreen(new RecipeConsoleScreen(parent, item, CatalogueState.Mode.RECIPES));
                } else {
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
        if (keyHeld && CatalogueKeys.SPEC_SHEET.matches(event)) {
            // The key that opened the sheet, still held: a repeat, not a press.
            return true;
        }
        if (event.isEscape() || !isModifier(event)) {
            onClose();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean keyReleased(KeyEvent event) {
        if (CatalogueKeys.SPEC_SHEET.matches(event)) {
            keyHeld = false;
        }
        return super.keyReleased(event);
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
    public void onClose() {
        Minecraft.getInstance().gui.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
