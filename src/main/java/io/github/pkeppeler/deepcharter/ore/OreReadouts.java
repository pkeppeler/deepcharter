package io.github.pkeppeler.deepcharter.ore;

import io.github.pkeppeler.deepcharter.layer.BlockReadouts;
import java.util.Optional;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.Blocks;

/**
 * The block readout's words for what the ore feature owns (#859). Company rock says what breaks it (ADR 0050, 0052). A gas pocket is drawn as
 * stone, so it reads as stone: its name would warn the player of what the sounder is for.
 */
final class OreReadouts {
    private static final Component COMPANY_ROCK = Component.translatable("hint.deepcharter.company_rock");

    private OreReadouts() {}

    static void init() {
        BlockReadouts readouts = BlockReadouts.SHARED;
        readouts.disguise(HazardBlocks.GAS_POCKET, Blocks.STONE);
        readouts.addHint((state, level, pos, viewer) ->
                state.is(HazardBlocks.COMPANY_ROCK) ? Optional.of(COMPANY_ROCK) : Optional.empty());
    }
}
