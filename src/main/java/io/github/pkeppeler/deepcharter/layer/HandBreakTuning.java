package io.github.pkeppeler.deepcharter.layer;

import io.github.pkeppeler.deepcharter.DeepCharter;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;

/**
 * What it takes to brute-force a pod-only block with a hand tool (#858, ADR 0069), the one record of it. Any item in
 * {@code tools} does it, in any layer, in {@code breakTicks}. A tool in {@code durableTools} pays {@code durableShare}
 * of its maximum durability; any other tool is destroyed by the block, and one that cannot be damaged pays nothing.
 *
 * @param breakTicks     ticks a tool needs for one block, whatever its enchantments, effects or footing
 * @param stopSlackTicks ticks short of {@code breakTicks} that a STOP may come and still break the block. The break
 *     time is a floor, and vanilla's own check would let a STOP through at 0.7 of it; the slack only covers the skew
 *     between the client's count and the server's. The integrated client was measured 5 ticks ahead under load, and a
 *     network or a lag spike adds more, so 20 ticks (1 s, under 2% of the floor) keeps a legitimate break from failing
 * @param tools          the item tag of what counts as a tool: pickaxes, axes, shovels, hoes, swords and spears
 * @param durableShare the share of a durable tool's maximum durability one block costs, in any layer
 * @param durableTools the item tag of the tools that survive a block: diamond and better, and what a pack adds
 * @param sparkTicks   ticks between two bursts of sparks at the face the player is grinding
 * @param soundTicks   ticks between two grinding sounds
 * @param messageTicks ticks between two refreshes of the action bar line that says the pick is burning out
 */
public record HandBreakTuning(
        int breakTicks,
        int stopSlackTicks,
        TagKey<Item> tools,
        double durableShare,
        TagKey<Item> durableTools,
        int sparkTicks,
        int soundTicks,
        int messageTicks) {
    public static final HandBreakTuning DEFAULT =
            new HandBreakTuning(1200, 20, tool("hand_break/tools"), 0.5, tool("hand_break/durable"), 4, 10, 40);

    public HandBreakTuning {
        if (breakTicks < 2) {
            throw new IllegalArgumentException("breakTicks must be at least 2, got " + breakTicks);
        }
        if (stopSlackTicks < 0 || stopSlackTicks >= breakTicks) {
            throw new IllegalArgumentException("stopSlackTicks must be in [0, breakTicks), got " + stopSlackTicks);
        }
        if (durableShare <= 0 || durableShare > 1) {
            throw new IllegalArgumentException("durableShare must be in (0, 1], got " + durableShare);
        }
        if (sparkTicks < 1 || soundTicks < 1 || messageTicks < 1) {
            throw new IllegalArgumentException("the effect cadences must be at least 1 tick");
        }
    }

    private static TagKey<Item> tool(String path) {
        return TagKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, path));
    }
}
