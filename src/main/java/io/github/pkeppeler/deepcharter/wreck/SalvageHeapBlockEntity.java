package io.github.pkeppeler.deepcharter.wreck;

import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.network.chat.Component;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * What a {@link SalvageHeapBlock} holds: {@value #SIZE} slots, saved as vanilla saves a chest's items so a pack, a command or a datapack
 * can read them. It opens as a six-row chest for anyone.
 */
public final class SalvageHeapBlockEntity extends BaseContainerBlockEntity {
    /** Six rows of nine, the largest chest screen. */
    public static final int SIZE = 54;

    private NonNullList<ItemStack> items = NonNullList.withSize(SIZE, ItemStack.EMPTY);

    public SalvageHeapBlockEntity(BlockPos pos, BlockState state) {
        super(WreckRegistry.HEAP_ENTITY, pos, state);
    }

    /** Puts as much of {@code stack} into the heap as fits, topping up stacks of the same item first. Returns what did not fit. */
    ItemStack insert(ItemStack stack) {
        ItemStack rest = stack.copy();
        for (int slot = 0; slot < SIZE && !rest.isEmpty(); slot++) {
            ItemStack held = items.get(slot);
            if (!held.isEmpty() && ItemStack.isSameItemSameComponents(held, rest)) {
                int moved = Math.min(rest.getCount(), held.getMaxStackSize() - held.getCount());
                held.grow(moved);
                rest.shrink(moved);
            }
        }
        for (int slot = 0; slot < SIZE && !rest.isEmpty(); slot++) {
            if (items.get(slot).isEmpty()) {
                int moved = Math.min(rest.getCount(), rest.getMaxStackSize());
                items.set(slot, rest.split(moved));
            }
        }
        setChanged();
        return rest;
    }

    @Override
    public int getContainerSize() {
        return SIZE;
    }

    @Override
    protected NonNullList<ItemStack> getItems() {
        return items;
    }

    @Override
    protected void setItems(NonNullList<ItemStack> newItems) {
        items = newItems;
    }

    @Override
    protected Component getDefaultName() {
        return Component.translatable("block.deepcharter.salvage_heap");
    }

    @Override
    protected AbstractContainerMenu createMenu(int containerId, Inventory inventory) {
        return ChestMenu.sixRows(containerId, inventory, this);
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        items = NonNullList.withSize(SIZE, ItemStack.EMPTY);
        ContainerHelper.loadAllItems(input, items);
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        ContainerHelper.saveAllItems(output, items);
    }
}
