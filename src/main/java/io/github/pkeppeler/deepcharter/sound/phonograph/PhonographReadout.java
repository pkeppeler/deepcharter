package io.github.pkeppeler.deepcharter.sound.phonograph;

import io.github.pkeppeler.deepcharter.layer.BlockReadouts;
import java.util.Optional;
import net.minecraft.network.chat.Component;

/** The block readout's hint for the phonographs (#864): what a right-click does, which differs for the colony's rack. */
public final class PhonographReadout {
    private static final Component PLAYER = Component.translatable("hint.deepcharter.phonograph");
    private static final Component COLONY = Component.translatable("hint.deepcharter.colony_phonograph");

    private PhonographReadout() {}

    public static void init() {
        BlockReadouts.SHARED.addHint((state, level, pos, viewer) -> {
            if (!(state.getBlock() instanceof PhonographBlock phonograph)) {
                return Optional.empty();
            }
            return Optional.of(phonograph.isColony() ? COLONY : PLAYER);
        });
    }
}
