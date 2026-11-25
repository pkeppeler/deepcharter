package io.github.pkeppeler.deepcharter.ore;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponentGetter;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipProvider;

/**
 * The weight of one item that is not ore, in pod units (the unit of {@link OreType#mass}). It is a default component of
 * the item and never set on a stack, so no save holds one. {@link OreEncumbrance} counts it with the ore a player
 * carries, and a pod's lift counts what its pilot carries, so ore and the solid fuels weigh the same way.
 */
public record CargoWeight(float mass) implements TooltipProvider {
    public static final Codec<CargoWeight> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                    Codec.floatRange(0f, Float.MAX_VALUE).fieldOf("mass").forGetter(CargoWeight::mass))
            .apply(instance, CargoWeight::new));
    public static final StreamCodec<ByteBuf, CargoWeight> STREAM_CODEC =
            ByteBufCodecs.FLOAT.map(CargoWeight::new, CargoWeight::mass);

    @Override
    public void addToTooltip(
            Item.TooltipContext context,
            Consumer<Component> consumer,
            TooltipFlag flag,
            DataComponentGetter components) {
        consumer.accept(Component.translatable("deepcharter.ore.tooltip.heavy").withStyle(ChatFormatting.GRAY));
    }

    /**
     * The mass of the stack's own items, counted by this component; 0 for an item with none. Ore and containers are not
     * counted here.
     */
    public static float of(ItemStack stack) {
        CargoWeight weight = stack.get(OreRegistry.CARGO_WEIGHT);
        return weight == null ? 0f : weight.mass() * stack.getCount();
    }
}
