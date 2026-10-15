package io.github.pkeppeler.deepcharter.client.upgrade;

import io.github.pkeppeler.deepcharter.client.terminal.TerminalScreens;
import io.github.pkeppeler.deepcharter.terminal.TerminalTypes;
import io.github.pkeppeler.deepcharter.upgrade.ComponentItem;
import io.github.pkeppeler.deepcharter.upgrade.ComponentItems;
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.component.TooltipDisplay;

/** Registers the upgrade terminal's online screen and the tooltip of a part that has no label. */
public final class UpgradeClientRegistry {
    /** The tooltip lines come after the item's name. */
    private static final int AFTER_NAME = 1;

    private UpgradeClientRegistry() {}

    public static void register() {
        TerminalScreens.register(TerminalTypes.UPGRADE_TERMINAL, UpgradeScreen::new);
        // A label's own lines come from the PartLabel component; a stack without one has no component to carry them.
        ItemTooltipCallback.EVENT.register((stack, context, flag, lines) -> {
            boolean hidden = stack.getOrDefault(DataComponents.TOOLTIP_DISPLAY, TooltipDisplay.DEFAULT)
                    .hideTooltip();
            if (stack.getItem() instanceof ComponentItem && !stack.has(ComponentItems.LABEL) && !hidden) {
                lines.add(AFTER_NAME, Component.translatable("item.deepcharter.part.unlabelled"));
            }
        });
    }
}
