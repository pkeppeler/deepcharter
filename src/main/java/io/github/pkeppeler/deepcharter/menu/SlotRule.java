package io.github.pkeppeler.deepcharter.menu;

import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * What one typed slot of a {@link SlottedMenu} takes, and how it shows when empty. Both sides build the same rule: the
 * client's predicts a click, the server's decides it.
 *
 * @param fits     whether a stack is the kind this slot is for (a part, a fuel); shift-click tries only these
 * @param unfit    why a stack that does not fit is refused
 * @param check    why a stack that fits is refused now (a lock, a full tank, an earlier repair), or empty if not
 * @param ghost    what an empty slot shows, or empty for a plain slot
 * @param locks    a filled slot cannot be emptied: no click, shift-click, swap or throw takes from it
 * @param maxStack the most items the slot holds
 */
public record SlotRule(
        Predicate<ItemStack> fits,
        Function<ItemStack, Component> unfit,
        Function<ItemStack, Optional<Component>> check,
        Optional<Ghost> ghost,
        boolean locks,
        int maxStack) {
    /** The icon and the name an empty slot shows: the item that goes there, drawn faded. */
    public record Ghost(ItemStack icon, Component label) {
        public Ghost {
            icon = icon.copy();
        }
    }

    public SlotRule {
        if (maxStack < 1) {
            throw new IllegalArgumentException("a slot holds at least one item, got " + maxStack);
        }
    }

    /**
     * A slot for the stacks {@code fits} accepts, refusing the rest with {@code unfit}: no check, no ghost, no lock, a
     * full stack.
     */
    public static SlotRule of(Predicate<ItemStack> fits, Function<ItemStack, Component> unfit) {
        return new SlotRule(
                fits, unfit, stack -> Optional.empty(), Optional.empty(), false, Item.ABSOLUTE_MAX_STACK_SIZE);
    }

    public SlotRule checked(Function<ItemStack, Optional<Component>> newCheck) {
        return new SlotRule(fits, unfit, newCheck, ghost, locks, maxStack);
    }

    public SlotRule ghost(ItemStack icon, Component label) {
        return new SlotRule(fits, unfit, check, Optional.of(new Ghost(icon, label)), locks, maxStack);
    }

    /** This rule with a slot that stays filled once something is in it. */
    public SlotRule locking() {
        return new SlotRule(fits, unfit, check, ghost, true, maxStack);
    }

    public SlotRule holding(int most) {
        return new SlotRule(fits, unfit, check, ghost, locks, most);
    }

    /** Why {@code stack} cannot go into this slot now, or empty when it can. */
    public Optional<Component> refusal(ItemStack stack) {
        return fits.test(stack) ? check.apply(stack) : Optional.of(unfit.apply(stack));
    }
}
