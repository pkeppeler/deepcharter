package io.github.pkeppeler.deepcharter.client.ui;

import net.minecraft.world.item.Item;
import org.jspecify.annotations.Nullable;

/**
 * A screen that shows items outside any slot, such as a terminal's shop rows, and says which is under the cursor, so the keys
 * that act on a hovered item (the spec sheet key, #1651) work there as over a slot.
 */
public interface HoveredItemScreen {
    /** The item under the cursor, or null when the cursor is on none. */
    @Nullable
    Item hoveredItem();
}
