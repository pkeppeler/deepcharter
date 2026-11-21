package io.github.pkeppeler.deepcharter.layer;

import java.util.Optional;
import net.minecraft.network.chat.Component;

/**
 * The block readout's hints for what the layer feature owns (#859): the breach crust, and the rock that only a pod drill breaks. The rock reads
 * "pod drill only" where {@link LayerRock} refuses a hand, which is below layer 1 and nowhere else.
 */
final class LayerReadouts {
    private static final Component POD_DRILL_ONLY = Component.translatable("hint.deepcharter.pod_drill_only");

    private LayerReadouts() {}

    static void init() {
        BlockReadouts.SHARED.addHint((state, level, pos) ->
                state.is(LayerBlocks.BREACH_CRUST) || LayerRock.isOutOfReachOfHands(level, pos, state)
                        ? Optional.of(POD_DRILL_ONLY)
                        : Optional.empty());
    }
}
