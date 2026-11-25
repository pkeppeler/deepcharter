package io.github.pkeppeler.deepcharter.layer;

import io.github.pkeppeler.deepcharter.ore.HazardBlocks;
import java.util.Optional;
import net.minecraft.network.chat.Component;

/**
 * The block readout's hints for what the layer feature owns (#859): the breach crust, and the rock that only a pod
 * drill breaks. Every pod-only block ({@link HandBreak#isPodOnly}: the crust, Company rock, and rock below layer 1)
 * reads "pod drill only", and says the pick would burn out when the viewer holds a diamond or better tool, or be
 * destroyed when they hold a lesser one (#858, ADR 0069). A creative player is exempt from the rule, so they read
 * no hint. Company rock reads its own hint ("Charges, or the best drill", from the ore feature, which registers after
 * this one) to a viewer who holds no tool, since that says what does break it.
 */
final class LayerReadouts {
    private static final Component POD_DRILL_ONLY = Component.translatable("hint.deepcharter.pod_drill_only");
    private static final Component PICK_WOULD_BURN_OUT =
            Component.translatable("hint.deepcharter.pod_drill_only_burns");
    private static final Component PICK_WOULD_BE_DESTROYED =
            Component.translatable("hint.deepcharter.pod_drill_only_destroys");

    private LayerReadouts() {}

    static void init() {
        BlockReadouts.SHARED.addHint((state, level, pos, viewer) -> {
            if (!HandBreak.governs(viewer, level, pos, state)) {
                return Optional.empty();
            }
            return switch (HandBreak.wearOf(viewer.getMainHandItem())) {
                case SHARE -> Optional.of(PICK_WOULD_BURN_OUT);
                case DESTROYED -> Optional.of(PICK_WOULD_BE_DESTROYED);
                case FREE -> Optional.of(POD_DRILL_ONLY);
                case NONE -> state.is(HazardBlocks.COMPANY_ROCK) ? Optional.empty() : Optional.of(POD_DRILL_ONLY);
            };
        });
    }
}
