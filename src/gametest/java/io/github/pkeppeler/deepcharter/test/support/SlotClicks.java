package io.github.pkeppeler.deepcharter.test.support;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;

/**
 * Clicks the slots of the open container screen with the real cursor and mouse, as a player drags and shift-clicks
 * items. The caller waits for what the click changes.
 */
public final class SlotClicks {
    private SlotClicks() {}

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
