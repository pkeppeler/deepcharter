package io.github.pkeppeler.deepcharter.wreck;

import io.github.pkeppeler.deepcharter.layer.BlockReadouts;
import java.util.Optional;
import net.minecraft.network.chat.Component;

/** The block readout's hint for a salvage heap: it opens by right-click like a chest. */
final class WreckReadout {
    private static final Component RIGHT_CLICK = Component.translatable("hint.deepcharter.right_click_salvage");

    private WreckReadout() {}

    static void init() {
        BlockReadouts.SHARED.addHint((state, level, pos, viewer) ->
                state.getBlock() instanceof SalvageHeapBlock ? Optional.of(RIGHT_CLICK) : Optional.empty());
    }
}
