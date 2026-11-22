package io.github.pkeppeler.deepcharter.outpost;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The lines a block's spec sheet gives about its ratings (#989; SPEC "The world is a mystery", spec sheets): what it holds on each axis and where that holds.
 * The client adds them to the block item's tooltip, and a test reads them here. A block no tag lists says nothing, so the tooltip of a vanilla block is
 * unchanged; a block that was looked at and rated nothing says so.
 */
public final class SpecSheet {
    private SpecSheet() {}

    /** The sheet lines for {@code state}'s block: empty when no rating tag lists it. */
    public static List<Component> lines(BlockState state) {
        if (!BlockRatings.isListed(state)) {
            return List.of();
        }
        Ratings held = BlockRatings.of(state);
        List<Component> lines = new ArrayList<>();
        lines.add(Component.translatable(
                "spec.deepcharter.ratings", held.heat(), held.pressure(), held.flooding(), held.quake()));
        List<Integer> layers = DepthRatings.layersHeldBy(held);
        lines.add(layers.isEmpty() ? Component.translatable("spec.deepcharter.holds_nowhere") : holdsIn(layers));
        return lines;
    }

    private static Component holdsIn(List<Integer> layers) {
        String numbers = String.join(", ", layers.stream().map(String::valueOf).toList());
        return Component.translatable(
                layers.size() == 1 ? "spec.deepcharter.holds_in_layer" : "spec.deepcharter.holds_in_layers", numbers);
    }
}
