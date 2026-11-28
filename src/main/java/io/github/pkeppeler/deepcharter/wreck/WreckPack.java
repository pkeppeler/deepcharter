package io.github.pkeppeler.deepcharter.wreck;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.List;
import net.minecraft.world.item.ItemStack;

/**
 * The pack of a wreck's crew: what the pilot carried when the hull ran out, kept in the wreck instead of dropping where
 * they die (ADR 0117). The body of a {@code Versioned<WreckPack>}, saved and not synced. Only non-empty stacks are kept.
 *
 * @param items the stacks the crew carried, in the order they were taken
 */
public record WreckPack(List<ItemStack> items) {
    /** The saved version; 1 is the first. */
    public static final int VERSION = 1;

    public static final WreckPack EMPTY = new WreckPack(List.of());
    public static final MapCodec<WreckPack> BODY = RecordCodecBuilder.mapCodec(
            instance -> instance.group(ItemStack.CODEC.listOf().fieldOf("items").forGetter(WreckPack::items))
                    .apply(instance, WreckPack::new));

    public WreckPack {
        items = items.stream().map(ItemStack::copy).toList();
    }
}
