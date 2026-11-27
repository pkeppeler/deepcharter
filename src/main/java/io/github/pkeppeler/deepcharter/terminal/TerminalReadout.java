package io.github.pkeppeler.deepcharter.terminal;

import io.github.pkeppeler.deepcharter.layer.BlockReadouts;
import java.util.Optional;
import net.minecraft.network.chat.Component;

/** The block readout's hint for every terminal (#859): the block of any registered {@link TerminalType} says how to use it. */
final class TerminalReadout {
    private static final Component RIGHT_CLICK = Component.translatable("hint.deepcharter.right_click_use");

    private TerminalReadout() {}

    static void init() {
        BlockReadouts.SHARED.addHint((state, level, pos, viewer) ->
                TerminalTypes.of(state.getBlock()).isPresent() ? Optional.of(RIGHT_CLICK) : Optional.empty());
    }
}
