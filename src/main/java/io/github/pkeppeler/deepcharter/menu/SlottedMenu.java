package io.github.pkeppeler.deepcharter.menu;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * A container menu of typed slots ({@link SlotRule}) above the player's inventory (ADR 0099). The server's menu is the
 * authority: vanilla runs every click on the client first to predict it, then on the server, which corrects the client.
 *
 * <p>A click that drops or swaps a stack into a typed slot, or shift-clicks one out of the inventory, is checked
 * against the slot's rule first. A refused click moves nothing; the menu keeps the reason ({@link #refusal()}, which
 * the screen draws) and the server also tells the player. Shift-click routes an inventory stack to the first typed slot
 * it fits that takes it, and a typed slot's stack back to the inventory. A locked slot that holds something gives
 * nothing back by any click.
 *
 * <p>A subclass adds its typed slots with {@link #addRuleSlot}, then the inventory with {@link #addPlayerInventory},
 * and reacts to a stack put into a typed slot in {@link #placed}.
 */
public abstract class SlottedMenu extends AbstractContainerMenu {
    /** The player's inventory: three rows and the hotbar. */
    public static final int INVENTORY_WIDTH = 9 * SLOT_SIZE;

    public static final int INVENTORY_HEIGHT = 4 * SLOT_SIZE + 4;

    private final List<RuleSlot> ruleSlots = new ArrayList<>();
    private int inventoryStart = -1;
    private Optional<Component> refusal = Optional.empty();

    protected SlottedMenu(MenuType<?> type, int containerId) {
        super(type, containerId);
    }

    /** Adds a typed slot over {@code index} of {@code container}, at {@code x}, {@code y} in the screen's image. */
    protected final RuleSlot addRuleSlot(Container container, int index, int x, int y, SlotRule rule) {
        if (inventoryStart >= 0) {
            throw new IllegalStateException("typed slots go before the player's inventory");
        }
        RuleSlot slot = new RuleSlot(container, index, x, y, rule);
        addSlot(slot);
        ruleSlots.add(slot);
        return slot;
    }

    /** Adds the player's inventory with its top left at {@code x}, {@code y}: the rows, then the hotbar under them. */
    protected final void addPlayerInventory(Inventory inventory, int x, int y) {
        inventoryStart = slots.size();
        addStandardInventorySlots(inventory, x, y);
    }

    /** The typed slots, in the order they were added. */
    public final List<RuleSlot> ruleSlots() {
        return Collections.unmodifiableList(ruleSlots);
    }

    /** Why this side refused the last click, or empty when it did not. */
    public final Optional<Component> refusal() {
        return refusal;
    }

    /** Called on both sides once {@code stack} is in {@code slot}. The server's menu commits it here. */
    protected void placed(RuleSlot slot, ItemStack stack) {}

    @Override
    public void clicked(int index, int button, ContainerInput input, Player player) {
        refusal = Optional.empty();
        Optional<Component> why = refusalOf(index, button, input, player);
        if (why.isPresent()) {
            refusal = why;
            if (player instanceof ServerPlayer server) {
                server.sendOverlayMessage(why.get());
            }
            return;
        }
        super.clicked(index, button, input, player);
    }

    private Optional<Component> refusalOf(int index, int button, ContainerInput input, Player player) {
        if (index < 0 || index >= slots.size()) {
            return Optional.empty();
        }
        Slot slot = slots.get(index);
        if (slot instanceof RuleSlot typed) {
            ItemStack incoming = switch (input) {
                case PICKUP -> getCarried();
                case SWAP -> player.getInventory().getItem(button);
                default -> ItemStack.EMPTY;
            };
            return incoming.isEmpty() ? Optional.empty() : typed.rule().refusal(incoming);
        }
        if (input == ContainerInput.QUICK_MOVE && isInventory(index) && slot.hasItem()) {
            return quickMoveRefusal(slot.getItem());
        }
        return Optional.empty();
    }

    /**
     * Why no typed slot takes {@code stack} by shift-click: the refusal of the first slot it fits, or the first slot's
     * reason it fits none. Empty when a slot takes it, or when every slot it fits is merely full.
     */
    private Optional<Component> quickMoveRefusal(ItemStack stack) {
        Optional<Component> fitting = Optional.empty();
        for (RuleSlot slot : ruleSlots) {
            if (!slot.rule().fits().test(stack)) {
                continue;
            }
            Optional<Component> why = slot.rule().check().apply(stack);
            if (why.isEmpty()) {
                return Optional.empty();
            }
            if (fitting.isEmpty()) {
                fitting = why;
            }
        }
        if (fitting.isPresent() || ruleSlots.isEmpty()) {
            return fitting;
        }
        return Optional.of(ruleSlots.getFirst().rule().unfit().apply(stack));
    }

    private boolean isInventory(int index) {
        return inventoryStart >= 0 && index >= inventoryStart;
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot slot = slots.get(index);
        if (!slot.hasItem()) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = slot.getItem();
        ItemStack before = stack.copy();
        if (slot instanceof RuleSlot) {
            if (inventoryStart < 0 || !moveItemStackTo(stack, inventoryStart, slots.size(), true)) {
                return ItemStack.EMPTY;
            }
        } else if (!toRuleSlots(stack)) {
            return ItemStack.EMPTY;
        }
        if (stack.isEmpty()) {
            slot.setByPlayer(ItemStack.EMPTY);
        } else {
            slot.setChanged();
        }
        slot.onTake(player, stack);
        return before;
    }

    /** Moves what it can of {@code stack} into the typed slots it fits, in order. True when anything moved. */
    private boolean toRuleSlots(ItemStack stack) {
        boolean moved = false;
        for (RuleSlot slot : ruleSlots) {
            if (stack.isEmpty()) {
                break;
            }
            if (slot.rule().fits().test(stack)) {
                moved |= moveItemStackTo(stack, slot.index, slot.index + 1, false);
            }
        }
        return moved;
    }

    /** A slot that takes what its {@link SlotRule} allows, and, if the rule locks, gives back nothing once filled. */
    public final class RuleSlot extends Slot {
        private final SlotRule rule;

        private RuleSlot(Container container, int index, int x, int y, SlotRule rule) {
            super(container, index, x, y);
            this.rule = rule;
        }

        public SlotRule rule() {
            return rule;
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return rule.refusal(stack).isEmpty();
        }

        @Override
        public boolean mayPickup(Player player) {
            return !(rule.locks() && hasItem());
        }

        @Override
        public int getMaxStackSize() {
            return Math.min(rule.maxStack(), super.getMaxStackSize());
        }

        @Override
        public void setByPlayer(ItemStack stack, ItemStack previous) {
            super.setByPlayer(stack, previous);
            if (!stack.isEmpty()) {
                placed(this, stack);
            }
        }
    }
}
