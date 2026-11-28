package io.github.pkeppeler.deepcharter.client.ore;

import io.github.pkeppeler.deepcharter.market.OrePriceTooltip;
import io.github.pkeppeler.deepcharter.ore.MassTooltip;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.minecraft.network.chat.Component;

/**
 * Client entry point for the ore feature, called from DeepCharterClient. Issues: #56, #63.
 * Each part of the feature registers itself from here, one line per part.
 */
public final class OreClientInit {
    /** The tooltip lines come after the item's name. */
    private static final int AFTER_NAME = 1;

    private OreClientInit() {}

    public static void init() {
        // The mass line, then an ore's price beside it (#1669).
        ItemTooltipCallback.EVENT.register((stack, context, flag, lines) -> {
            List<Component> added = new ArrayList<>();
            MassTooltip.line(stack).ifPresent(added::add);
            OrePriceTooltip.line(stack).ifPresent(added::add);
            lines.addAll(Math.min(AFTER_NAME, lines.size()), added);
        });
    }
}
