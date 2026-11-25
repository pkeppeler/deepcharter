package io.github.pkeppeler.deepcharter.client.handbook.catalogue;

import io.github.pkeppeler.deepcharter.client.theme.CatalogueLook;
import java.util.List;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;
import org.jspecify.annotations.Nullable;

/**
 * R and U over a slot of a container screen with no catalogue beside it: a chest, a barrel, the creative inventory, a modded container.
 * An item the charter knows a recipe or a use of opens the {@link RecipeConsoleScreen} over the container. One it knows nothing of opens
 * nothing and moves nothing (the lookup and its history stay as they were); a short note under the cursor says so, so no unknown recipe
 * is ever shown and the press is not lost in silence. A text field that has the keyboard (the creative search, an anvil's name) keeps
 * its R and U.
 */
final class SlotLookup {
    private final AbstractContainerScreen<?> screen;
    private int ticks;
    /** What the note under the cursor says, or null while there is none. */
    private @Nullable String note;

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
        if ((!recipes && !CatalogueKeys.USES.matches(event)) || screen.isInputCaptured()) {
            return false;
        }
        Slot slot = HoveredSlot.of(screen);
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
        }
    }

    /** The note in a strip under the cursor, kept inside the screen. */
    private void extract(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
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
        int y = mouseY + CatalogueHardware.ICON;
        if (y + height > screen.height) {
            y = mouseY - CatalogueHardware.ICON - height;
        }
        CatalogueHardware.strip(graphics, client.font, List.of(shown), x, Math.max(0, y), inset);
    }
}
