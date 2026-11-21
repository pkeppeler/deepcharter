package io.github.pkeppeler.deepcharter.layer;

import io.github.pkeppeler.deepcharter.DeepCharter;
import java.util.List;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;

/**
 * What it takes to brute-force a pod-only block with a hand tool (#858, ADR 0069), the one record of it. A layer's
 * entry is the list's element at {@code layer - 1}; layers past the end of a list use its last element.
 *
 * @param breakTicks   ticks a qualifying tool needs for one block, whatever its enchantments, effects or footing
 * @param shares       the share of the tool's maximum durability one block costs, by layer
 * @param tools        the item tag a tool must be in to bite, by layer
 * @param sparkTicks   ticks between two bursts of sparks at the face the player is grinding
 * @param soundTicks   ticks between two grinding sounds
 * @param messageTicks ticks between two refreshes of the action bar line that says the pick is burning out
 */
public record HandBreakTuning(
        int breakTicks,
        List<Double> shares,
        List<TagKey<Item>> tools,
        int sparkTicks,
        int soundTicks,
        int messageTicks) {
    public static final HandBreakTuning DEFAULT = new HandBreakTuning(
            1200,
            List.of(0.75, 0.9, 1.0),
            List.of(tool("hand_break/layer_1"), tool("hand_break/layer_2_and_deeper")),
            4,
            10,
            40);

    public HandBreakTuning {
        if (breakTicks < 2) {
            throw new IllegalArgumentException("breakTicks must be at least 2, got " + breakTicks);
        }
        if (shares.isEmpty() || tools.isEmpty()) {
            throw new IllegalArgumentException("shares and tools each need an entry for layer 1");
        }
        if (shares.stream().anyMatch(share -> share <= 0 || share > 1)) {
            throw new IllegalArgumentException("each share must be in (0, 1], got " + shares);
        }
        if (sparkTicks < 1 || soundTicks < 1 || messageTicks < 1) {
            throw new IllegalArgumentException("the effect cadences must be at least 1 tick");
        }
    }

    /** The share of a tool's maximum durability that one block of {@code layer} costs. */
    public double shareFor(int layer) {
        return shares.get(Math.min(layer, shares.size()) - 1);
    }

    /** The tag of the tools that bite a block of {@code layer}. */
    public TagKey<Item> toolsFor(int layer) {
        return tools.get(Math.min(layer, tools.size()) - 1);
    }

    private static TagKey<Item> tool(String path) {
        return TagKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, path));
    }
}
