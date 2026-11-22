package io.github.pkeppeler.deepcharter.layer;

import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The block readout's hints for what the layer feature owns (#859): the breach crust, and the rock that only a pod drill breaks. The rock reads
 * "pod drill only" where {@link LayerRock} refuses a hand, which is below layer 1 and nowhere else. A creative player is exempt from both rules, so
 * they read no hint.
 */
final class LayerReadouts {
    private static final Component POD_DRILL_ONLY = Component.translatable("hint.deepcharter.pod_drill_only");

    private LayerReadouts() {}

    static void init() {
        BlockReadouts.SHARED.addHint((state, level, pos, viewer) ->
                !viewer.isCreative() && (state.is(LayerBlocks.BREACH_CRUST) || outOfReachOfHands(level, pos, state))
                        ? Optional.of(POD_DRILL_ONLY)
                        : Optional.empty());
    }

    /**
     * {@link LayerRock#isOutOfReachOfHands}, for a level the client may have with a dimension type given inline by a data pack, which
     * {@link LayerChain#bandAt} refuses: such a level is no layer, and a readout drawn every tick must not throw.
     */
    private static boolean outOfReachOfHands(Level level, BlockPos pos, BlockState state) {
        return state.is(LayerRock.DEEP_ROCK)
                && level.dimensionTypeRegistration().unwrapKey().isPresent()
                && LayerRock.isOutOfReachOfHands(level, pos, state);
    }
}
