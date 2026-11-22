package io.github.pkeppeler.deepcharter.outpost;

import java.util.List;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The line a rated block's spec sheet gives about its ratings (#989; SPEC "The world is a mystery", spec sheets): what it holds on each axis. The
 * client adds it to the block item's tooltip, and a test reads it here. A block rated nothing says nothing, so the tooltip of a vanilla block is
 * unchanged. Where it holds comes with the first layer that asks for something: layers 1 and 2 ask nothing, so no line says where yet.
 */
public final class SpecSheet {
    private SpecSheet() {}

    /** The sheet lines for {@code state}'s block: empty when it is rated nothing. */
    public static List<Component> lines(BlockState state) {
        Ratings held = BlockRatings.of(state);
        if (held.equals(Ratings.NONE)) {
            return List.of();
        }
        return List.of(Component.translatable(
                "spec.deepcharter.ratings", held.heat(), held.pressure(), held.flooding(), held.quake()));
    }
}
