package io.github.pkeppeler.deepcharter.ore;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.BundleContents;
import net.minecraft.world.item.component.ItemContainerContents;

/**
 * The ore a player carries in their pack, counted for what a rider's pack costs a pod (ADR 0110): how many ore and
 * what they weigh at their {@link DrilledMass}. Ore in a shulker box or a bundle counts, as it does for
 * {@link OreEncumbrance}; fuel and other weighted items are not ore and are not counted here.
 *
 * @param ore  how many ore, never negative
 * @param mass what they weigh, never negative
 */
public record LooseOre(int ore, float mass) {
    public static final LooseOre NONE = new LooseOre(0, 0f);
    public static final StreamCodec<ByteBuf, LooseOre> STREAM = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, LooseOre::ore, ByteBufCodecs.FLOAT, LooseOre::mass, LooseOre::new);

    public LooseOre {
        // NaN fails >= 0, so the wire refuses it too.
        if (ore < 0 || !(mass >= 0f)) {
            throw new IllegalArgumentException("loose ore is never negative, got " + ore + " ore of mass " + mass);
        }
    }

    /** This and {@code other} together. */
    public LooseOre plus(LooseOre other) {
        return new LooseOre(ore + other.ore, mass + other.mass);
    }

    /** Whether there is any ore at all. */
    public boolean any() {
        return ore > 0;
    }

    /** The ore in every slot of {@code player}'s pack, the offhand and armor included, and inside their containers. */
    public static LooseOre inPack(Player player) {
        LooseOre total = NONE;
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            total = total.plus(in(player.getInventory().getItem(slot)));
        }
        return total;
    }

    /** The ore in {@code stack}, and in everything inside it. */
    public static LooseOre in(ItemStack stack) {
        LooseOre total = OreRegistry.typeOf(stack)
                .map(type -> new LooseOre(stack.getCount(), DrilledMass.each(stack, type) * stack.getCount()))
                .orElse(NONE);
        ItemContainerContents container = stack.get(DataComponents.CONTAINER);
        if (container != null) {
            total = container.nonEmptyItemCopyStream().map(LooseOre::in).reduce(total, LooseOre::plus);
        }
        BundleContents bundle = stack.get(DataComponents.BUNDLE_CONTENTS);
        if (bundle != null) {
            total = bundle.itemCopies().map(LooseOre::in).reduce(total, LooseOre::plus);
        }
        return total;
    }
}
