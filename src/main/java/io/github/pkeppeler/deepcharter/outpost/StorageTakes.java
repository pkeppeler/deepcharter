package io.github.pkeppeler.deepcharter.outpost;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/**
 * What a visit takes from storage: the items of the containers in an outpost's zone, found through the loaded chunks' block entities.
 * It reads containers only, never entities (a pod's tank and cargo are not storage), and never a block in {@link OutpostSettling#NEVER_SETTLED}.
 * The containers are visited in a fixed order (position, then slot) from a random first container, so a take is the same for the same
 * random source and spreads over a base that has several.
 */
final class StorageTakes {
    private static final Comparator<BlockPos> BY_POSITION = Comparator.<BlockPos>comparingInt(BlockPos::getX)
            .thenComparingInt(BlockPos::getY)
            .thenComparingInt(BlockPos::getZ);

    private StorageTakes() {}

    /** The storage block entities in {@code zone} whose chunk is loaded, in position order. */
    static List<Container> storage(ServerLevel level, BoundingBox zone) {
        return OutpostSettling.blockEntities(level, zone).stream()
                .filter(entity ->
                        entity instanceof Container && !entity.getBlockState().is(OutpostSettling.NEVER_SETTLED))
                .sorted(Comparator.comparing(BlockEntity::getBlockPos, BY_POSITION))
                .map(entity -> (Container) entity)
                .toList();
    }

    /** How many items of {@code wanted} the zone's storage holds. */
    static int count(ServerLevel level, BoundingBox zone, Predicate<ItemStack> wanted) {
        long count = 0;
        for (Container container : storage(level, zone)) {
            for (int slot = 0; slot < container.getContainerSize(); slot++) {
                ItemStack stack = container.getItem(slot);
                if (wanted.test(stack)) {
                    count += stack.getCount();
                }
            }
        }
        return (int) Math.min(Integer.MAX_VALUE, count);
    }

    /**
     * Takes up to {@code maxItems} items of {@code wanted} from the zone's storage, those that sort first by {@code first} (ties in
     * container order from a random first container). Shrinks the stacks and marks each container changed. Returns what was taken, by item.
     */
    static Map<Item, Integer> take(
            ServerLevel level,
            BoundingBox zone,
            Predicate<ItemStack> wanted,
            Comparator<ItemStack> first,
            int maxItems,
            RandomSource random) {
        List<Container> containers = new ArrayList<>(storage(level, zone));
        if (containers.isEmpty()) {
            return Map.of();
        }
        int start = random.nextInt(containers.size());
        record Slot(Container container, int index) {
            ItemStack stack() {
                return container.getItem(index);
            }
        }
        List<Slot> slots = new ArrayList<>();
        for (int i = 0; i < containers.size(); i++) {
            Container container = containers.get((start + i) % containers.size());
            for (int index = 0; index < container.getContainerSize(); index++) {
                if (wanted.test(container.getItem(index))) {
                    slots.add(new Slot(container, index));
                }
            }
        }
        slots.sort(Comparator.comparing(Slot::stack, first));
        Map<Item, Integer> taken = new LinkedHashMap<>();
        int left = maxItems;
        for (Slot slot : slots) {
            if (left <= 0) {
                break;
            }
            ItemStack stack = slot.stack();
            int count = Math.min(left, stack.getCount());
            taken.merge(stack.getItem(), count, Integer::sum);
            stack.shrink(count);
            if (stack.isEmpty()) {
                slot.container().setItem(slot.index(), ItemStack.EMPTY);
            }
            slot.container().setChanged();
            left -= count;
        }
        return taken;
    }
}
