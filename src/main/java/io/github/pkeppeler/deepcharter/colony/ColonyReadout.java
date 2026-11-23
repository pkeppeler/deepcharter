package io.github.pkeppeler.deepcharter.colony;

import io.github.pkeppeler.deepcharter.layer.BlockReadouts;
import java.util.Optional;
import net.minecraft.network.chat.Component;

/**
 * The block readout's hints for the colony's right-click blocks (#892): the canteen gives a ration, and an ore car gives its tins
 * until it is looted, after which there is nothing to do and no hint.
 */
final class ColonyReadout {
    private static final Component CANTEEN = Component.translatable("hint.deepcharter.right_click_ration");
    private static final Component ORE_CAR = Component.translatable("hint.deepcharter.right_click_search");

    private ColonyReadout() {}

    static void init() {
        BlockReadouts.SHARED.addHint((state, level, pos, viewer) -> {
            if (state.getBlock() instanceof CanteenBlock) {
                return Optional.of(CANTEEN);
            }
            if (state.getBlock() instanceof OreCarBlock && !state.getValue(OreCarBlock.LOOTED)) {
                return Optional.of(ORE_CAR);
            }
            return Optional.empty();
        });
    }
}
