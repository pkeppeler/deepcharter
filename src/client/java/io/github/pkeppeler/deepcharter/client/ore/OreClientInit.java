package io.github.pkeppeler.deepcharter.client.ore;

import io.github.pkeppeler.deepcharter.ore.MassTooltip;
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;

/**
 * Client entry point for the ore feature, called from DeepCharterClient. Issues: #56, #63.
 * Each part of the feature registers itself from here, one line per part.
 */
public final class OreClientInit {
    /** The tooltip lines come after the item's name. */
    private static final int AFTER_NAME = 1;

    private OreClientInit() {}

    public static void init() {
        ItemTooltipCallback.EVENT.register((stack, context, flag, lines) ->
                MassTooltip.line(stack).ifPresent(line -> lines.add(AFTER_NAME, line)));
    }
}
