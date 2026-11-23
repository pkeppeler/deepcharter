package io.github.pkeppeler.deepcharter.client.outpost;

import io.github.pkeppeler.deepcharter.outpost.SpecSheet;
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.minecraft.world.item.BlockItem;

/** Registers the ratings on a block item's tooltip, the spec sheet's lines ({@link SpecSheet}). */
public final class OutpostClientRegistry {
    /** The tooltip lines come after the item's name. */
    private static final int AFTER_NAME = 1;

    private OutpostClientRegistry() {}

    public static void register() {
        ItemTooltipCallback.EVENT.register((stack, context, flag, lines) -> {
            if (stack.getItem() instanceof BlockItem item) {
                lines.addAll(AFTER_NAME, SpecSheet.lines(item.getBlock().defaultBlockState()));
            }
        });
    }
}
