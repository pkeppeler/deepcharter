package io.github.pkeppeler.deepcharter.wreck;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.Containers;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import org.jspecify.annotations.Nullable;

/**
 * Turns a storage block into a salvage heap that keeps what it held. The one way a heap is made, so the contents are moved, never copied.
 */
public final class SalvageHeaps {
    private SalvageHeaps() {}

    /**
     * Replaces the block at {@code pos} with a salvage heap holding the contents of its container, if it is one, and {@code also}
     * (the storage block's own item, say). What does not fit goes to a heap in a free cell next to the first, and then the next, so
     * nothing is lost and nothing drops as an item; if no neighbouring cell is free the rest drops at the heap, as a broken heap's
     * contents do. Returns how many heaps now hold the items.
     */
    public static int spill(ServerLevel level, BlockPos pos, List<ItemStack> also) {
        List<ItemStack> items = new ArrayList<>();
        if (level.getBlockEntity(pos) instanceof Container container) {
            for (int slot = 0; slot < container.getContainerSize(); slot++) {
                if (!container.getItem(slot).isEmpty()) {
                    items.add(container.getItem(slot).copy());
                }
            }
            // Clear it first: replacing the block drops whatever the old container still holds, and the heap would hold
            // it too.
            container.clearContent();
        }
        also.stream().filter(stack -> !stack.isEmpty()).map(ItemStack::copy).forEach(items::add);
        int heaps = 0;
        BlockPos at = pos;
        while (true) {
            level.setBlock(at, WreckRegistry.HEAP.defaultBlockState(), Block.UPDATE_ALL);
            heaps++;
            SalvageHeapBlockEntity heap = (SalvageHeapBlockEntity) level.getBlockEntity(at);
            if (heap == null) {
                throw new IllegalStateException("the salvage heap placed at " + at + " has no block entity");
            }
            List<ItemStack> rest = new ArrayList<>();
            for (ItemStack stack : items) {
                ItemStack left = heap.insert(stack);
                if (!left.isEmpty()) {
                    rest.add(left);
                }
            }
            if (rest.isEmpty()) {
                return heaps;
            }
            items = rest;
            BlockPos next = freeNeighbour(level, at);
            if (next == null) {
                BlockPos last = at;
                items.forEach(stack -> Containers.dropItemStack(
                        level, last.getX() + 0.5, last.getY() + 1.0, last.getZ() + 0.5, stack));
                return heaps;
            }
            at = next;
        }
    }

    private static @Nullable BlockPos freeNeighbour(ServerLevel level, BlockPos pos) {
        for (Direction direction : Direction.values()) {
            BlockPos next = pos.relative(direction);
            if (level.getBlockState(next).isAir()) {
                return next;
            }
        }
        return null;
    }
}
