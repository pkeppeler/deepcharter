package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.Failures.expect;

import io.github.pkeppeler.deepcharter.test.support.MockPlayer;
import io.github.pkeppeler.deepcharter.test.support.MockPlayers;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.AABB;

/**
 * A stack of 64 coal saved before the stack size became 16 (#1280) loads as it was: vanilla does not clamp a loaded stack.
 * It can still be shift-clicked, split and dropped, and no coal is made or lost on the way.
 */
public class FuelOldStacksTest {
    private static final int OLD_STACK = 64;

    private static int coal(Container container) {
        int total = 0;
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            if (container.getItem(slot).is(Items.COAL)) {
                total += container.getItem(slot).getCount();
            }
        }
        return total;
    }

    /** All the coal there is: the player's pack, the container, the cursor and what lies on the ground. */
    private static int allCoal(MockPlayer player, AbstractContainerMenu menu, Container container) {
        int total = coal(player.player().getInventory())
                + (menu.getCarried().is(Items.COAL) ? menu.getCarried().getCount() : 0);
        if (container != player.player().getInventory()) {
            total += coal(container);
        }
        // entity-query: a test counting what it dropped at its own feet, in a chunk it keeps loaded
        for (ItemEntity dropped : player.player()
                .level()
                .getEntitiesOfClass(ItemEntity.class, new AABB(player.player().blockPosition()).inflate(4))) {
            if (dropped.getItem().is(Items.COAL)) {
                total += dropped.getItem().getCount();
            }
        }
        return total;
    }

    private static void exercise(
            GameTestHelper helper, MockPlayer player, AbstractContainerMenu menu, Container container, int firstSlot) {
        int before = allCoal(player, menu, container);
        expect(helper, before == OLD_STACK, "the old stack should hold %s coal, there are %s", OLD_STACK, before);
        menu.clicked(firstSlot, 0, ContainerInput.QUICK_MOVE, player.player());
        expect(
                helper,
                allCoal(player, menu, container) == OLD_STACK,
                "a shift-click changed the coal: %s",
                allCoal(player, menu, container));
        for (int slot = 0; slot < menu.slots.size(); slot++) {
            if (menu.getSlot(slot).getItem().is(Items.COAL)
                    && menu.getSlot(slot).getItem().getCount() > 1) {
                // Split the stack with a right click, then put the half back on an empty slot.
                menu.clicked(slot, 1, ContainerInput.PICKUP, player.player());
                expect(helper, allCoal(player, menu, container) == OLD_STACK, "a split changed the coal");
                for (int target = 0;
                        target < menu.slots.size() && !menu.getCarried().isEmpty();
                        target++) {
                    if (menu.getSlot(target).getItem().isEmpty()
                            && menu.getSlot(target).container == menu.getSlot(slot).container) {
                        menu.clicked(target, 0, ContainerInput.PICKUP, player.player());
                    }
                }
                break;
            }
        }
        expect(
                helper,
                allCoal(player, menu, container) == OLD_STACK,
                "putting the half down changed the coal: %s",
                allCoal(player, menu, container));
        for (int slot = 0; slot < menu.slots.size(); slot++) {
            if (menu.getSlot(slot).getItem().is(Items.COAL)) {
                menu.clicked(slot, 1, ContainerInput.THROW, player.player());
                break;
            }
        }
        expect(
                helper,
                allCoal(player, menu, container) == OLD_STACK,
                "a drop changed the coal: %s",
                allCoal(player, menu, container));
    }

    @GameTest
    public void anOldStackInThePackCanBeMovedSplitAndDroppedWithoutDuplication(GameTestHelper helper) {
        MockPlayer player = MockPlayers.join(helper, "old-stack-pack");
        try {
            player.player().setGameMode(GameType.SURVIVAL);
            player.player().getInventory().setItem(9, new ItemStack(Items.COAL, OLD_STACK));
            exercise(
                    helper,
                    player,
                    player.player().inventoryMenu,
                    player.player().getInventory(),
                    9);
            helper.succeed();
        } finally {
            player.leave();
        }
    }

    @GameTest
    public void anOldStackInAChestCanBeMovedSplitAndDroppedWithoutDuplication(GameTestHelper helper) {
        MockPlayer player = MockPlayers.join(helper, "old-stack-chest");
        try {
            player.player().setGameMode(GameType.SURVIVAL);
            // A container loaded from a save sets its items directly, which is how a 64-stack survives in one.
            ItemStack[] saved = new ItemStack[27];
            java.util.Arrays.fill(saved, ItemStack.EMPTY);
            saved[0] = new ItemStack(Items.COAL, OLD_STACK);
            Container chest = new SimpleContainer(saved);
            ChestMenu menu = ChestMenu.threeRows(1, player.player().getInventory(), chest);
            exercise(helper, player, menu, chest, 0);
            helper.succeed();
        } finally {
            player.leave();
        }
    }
}
