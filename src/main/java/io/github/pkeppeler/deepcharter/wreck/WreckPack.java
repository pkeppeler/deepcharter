package io.github.pkeppeler.deepcharter.wreck;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.world.item.ItemStack;

/**
 * The pack of a wreck's crew: what each pilot carried when the hull ran out, kept in the wreck instead of dropping
 * where they die (ADR 0117). The body of a {@code Versioned<WreckPack>}, saved and not synced. Only non-empty stacks are
 * kept, each with the player it came from, so it goes back to that player and to no one else.
 *
 * @param entries the stacks the crew carried, in the order they were taken
 */
public record WreckPack(List<Entry> entries) {
    /** The saved version; 1 is the first. */
    public static final int VERSION = 1;

    /**
     * One stack and the player who carried it.
     *
     * @param pilot the player's UUID
     * @param stack the stack, never empty
     */
    public record Entry(UUID pilot, ItemStack stack) {
        private static final Codec<Entry> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                        UUIDUtil.CODEC.fieldOf("pilot").forGetter(Entry::pilot),
                        ItemStack.CODEC.fieldOf("stack").forGetter(Entry::stack))
                .apply(instance, Entry::new));

        public Entry {
            if (stack.isEmpty()) {
                throw new IllegalArgumentException("a pack entry holds a stack, not nothing");
            }
            stack = stack.copy();
        }
    }

    public static final WreckPack EMPTY = new WreckPack(List.of());
    public static final MapCodec<WreckPack> BODY = RecordCodecBuilder.mapCodec(
            instance -> instance.group(Entry.CODEC.listOf().fieldOf("entries").forGetter(WreckPack::entries))
                    .apply(instance, WreckPack::new));

    public WreckPack {
        entries = List.copyOf(entries);
    }
}
