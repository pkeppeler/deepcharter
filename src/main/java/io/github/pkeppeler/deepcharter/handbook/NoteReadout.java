package io.github.pkeppeler.deepcharter.handbook;

import io.github.pkeppeler.deepcharter.layer.BlockReadouts;
import java.util.Optional;
import net.minecraft.network.chat.Component;

/** The block readout's hint for a Note lying in the world (#859). */
final class NoteReadout {
    private static final Component RIGHT_CLICK = Component.translatable("hint.deepcharter.right_click_read");

    private NoteReadout() {}

    static void init() {
        BlockReadouts.SHARED.addHint((state, level, pos, viewer) ->
                state.is(HandbookRegistry.NOTE) ? Optional.of(RIGHT_CLICK) : Optional.empty());
    }
}
