package io.github.pkeppeler.deepcharter.client.handbook.catalogue;

import io.github.pkeppeler.deepcharter.client.theme.CatalogueLook;
import java.util.List;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.tooltip.TooltipRenderUtil;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;
import org.jspecify.annotations.Nullable;

/**
 * R and U over a slot of a container screen with no catalogue beside it: a chest, a barrel, the creative inventory, a modded container.
 * An item the charter knows a recipe or a use of opens the {@link RecipeConsoleScreen} over the container. One it knows nothing of opens
 * nothing and moves nothing (the lookup and its history stay as they were); a short note over the cursor says so, so no unknown recipe
 * is ever shown and the press is not lost in silence. The note is about that slot's item and goes when the cursor leaves the slot. A text
 * field that has the keyboard (the creative search, an anvil's name) keeps its R and U, and while the catalogue is hidden they do nothing
 * here either, as over the inventory's slots.
 */
final class SlotLookup {
    private final AbstractContainerScreen<?> screen;
    private int ticks;
    /** What the note over the cursor says, or null while there is none. */
    private @Nullable String note;
    /** The slot the note is about. */
    private @Nullable Slot noteSlot;

    private int noteEndsAt;

    private SlotLookup(AbstractContainerScreen<?> screen) {
        this.screen = screen;
    }

    /** Lets R and U over the slots of {@code screen} look their items up. Called on each init: Fabric clears a screen's events then. */
    static void attach(AbstractContainerScreen<?> screen) {
        SlotLookup lookup = new SlotLookup(screen);
        ScreenKeyboardEvents.allowKeyPress(screen).register((s, event) -> !lookup.keyPressed(event));
        ScreenEvents.afterTick(screen).register(s -> lookup.tick());
        ScreenEvents.afterForeground(screen)
                .register((s, graphics, mouseX, mouseY, delta) -> lookup.extract(graphics, mouseX, mouseY));
    }

    /** A key, before the screen sees it. Returns whether it was R or U over an item, which is then this class's. */
    private boolean keyPressed(KeyEvent event) {
        boolean recipes = CatalogueKeys.RECIPES.matches(event);
        if ((!recipes && !CatalogueKeys.USES.matches(event))
                || screen.isInputCaptured()
                || screen.getFocused() instanceof EditBox
                || CatalogueScreens.state().hidden()) {
            return false;
        }
        // The screen sets it each frame from the mouse; it is public through deepcharter.classtweaker.
        Slot slot = screen.hoveredSlot;
        Minecraft client = Minecraft.getInstance();
        LocalPlayer player = client.player;
        if (slot == null || !slot.hasItem() || player == null || client.level == null) {
            return false;
        }
        Item item = slot.getItem().getItem();
        CatalogueState.Mode mode = recipes ? CatalogueState.Mode.RECIPES : CatalogueState.Mode.USES;
        PartsCatalogue known = PartsCatalogue.of(player.getRecipeBook(), SlotDisplayContext.fromLevel(client.level));
        List<KnownRecipe> found = recipes ? known.recipesFor(item) : known.usesOf(item);
        if (found.isEmpty()) {
            note = RecipeConsole.text(recipes ? "none.recipes" : "none.uses");
            noteSlot = slot;
            noteEndsAt = ticks + CataloguePanel.STATUS_TICKS;
            return true;
        }
        if (!RecipeConsoleScreen.fits(screen.width, screen.height)) {
            return false;
        }
        client.gui.setScreen(new RecipeConsoleScreen(screen, item, mode));
        return true;
    }

    private void tick() {
        ticks++;
        if (note != null && ticks >= noteEndsAt) {
            note = null;
            noteSlot = null;
        }
    }

    /**
     * The note in a strip over the cursor, kept inside the screen. The hovered item's tooltip, which the game draws over everything, starts
     * {@link TooltipRenderUtil#MOUSE_OFFSET} and its frame above the cursor and runs down beside it, so the note sits above that; under the
     * cursor only when there is no room above.
     */
    private void extract(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        if (screen.hoveredSlot != noteSlot) {
            // The cursor left the item the note is about.
            note = null;
            noteSlot = null;
        }
        String shown = note;
        if (shown == null) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        CatalogueLook look = CatalogueLook.current();
        int inset = look.nameStripPad() + 1;
        int width = CatalogueHardware.termWidth(client.font, shown) + 2 * inset;
        int height = client.font.lineHeight + 2 * inset;
        int x = Math.clamp(mouseX, 0, Math.max(0, screen.width - width));
        int y = mouseY - TooltipRenderUtil.MOUSE_OFFSET - TooltipRenderUtil.PADDING_TOP - look.nameStripGap() - height;
        if (y < 0) {
            y = Math.min(mouseY + CatalogueHardware.ICON, screen.height - height);
        }
        CatalogueHardware.strip(graphics, client.font, List.of(shown), x, Math.max(0, y), inset);
    }
}
