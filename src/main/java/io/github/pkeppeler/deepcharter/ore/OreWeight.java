package io.github.pkeppeler.deepcharter.ore;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponentGetter;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipProvider;

/**
 * The mark every {@link OreItem} carries as a default component, and nothing else does: it shows the tooltip that says the ore is
 * heavy. {@link OreEncumbrance} counts the same class, so the tooltip and the slowdown cannot name different items.
 */
public record OreWeight() implements TooltipProvider {
    public static final OreWeight INSTANCE = new OreWeight();
    public static final Codec<OreWeight> CODEC = MapCodec.unit(INSTANCE).codec();
    public static final StreamCodec<Object, OreWeight> STREAM_CODEC = StreamCodec.unit(INSTANCE);

    @Override
    public void addToTooltip(
            Item.TooltipContext context,
            Consumer<Component> consumer,
            TooltipFlag flag,
            DataComponentGetter components) {
        consumer.accept(Component.translatable("deepcharter.ore.tooltip.heavy").withStyle(ChatFormatting.GRAY));
        consumer.accept(Component.translatable("deepcharter.ore.tooltip.haul").withStyle(ChatFormatting.GRAY));
    }
}
