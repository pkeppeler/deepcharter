package io.github.pkeppeler.deepcharter.test.support;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;

/**
 * Clicks the slots of the open container screen with the real cursor and mouse, as a player drags and shift-clicks
 * items. The caller waits for what the click changes.
 */
public final class SlotClicks {
    private SlotClicks() {}

    /**
     * The first slot of the player's inventory in {@code menu} that holds {@code item}, or an empty one for air. It
     * touches no client class, so a server test may call it.
     */
    public static Slot inventorySlot(AbstractContainerMenu menu, Item item) {
        return menu.slots.stream()
                .filter(slot ->
                        slot.container instanceof Inventory && slot.getItem().is(item))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no inventory slot holds " + item));
    }

    /** Puts the cursor on the middle of {@code slot} of {@code screen}. */
    public static void hover(ClientGameTestContext context, AbstractContainerScreen<?> screen, Slot slot) {
        int left = ContainerScreenFields.intField(screen, "leftPos");
        int top = ContainerScreenFields.intField(screen, "topPos");
        ClientCursor.moveTo(context, left + slot.x + 8, top + slot.y + 8);
    }

    /** A left click on {@code slot}: it picks up what is there, or puts down what the cursor holds. */
    public static void click(ClientGameTestContext context, AbstractContainerScreen<?> screen, Slot slot) {
        hover(context, screen, slot);
        RealInput.click(context, InputConstants.MOUSE_BUTTON_LEFT);
    }

    /** A shift-click on {@code slot}: it moves the stack there to wherever the menu routes it. */
    public static void shiftClick(ClientGameTestContext context, AbstractContainerScreen<?> screen, Slot slot) {
        hover(context, screen, slot);
        context.getInput().holdKey(InputConstants.KEY_LSHIFT);
        try {
            RealInput.click(context, InputConstants.MOUSE_BUTTON_LEFT, InputConstants.MOD_SHIFT);
        } finally {
            context.getInput().releaseKey(InputConstants.KEY_LSHIFT);
        }
    }
}
