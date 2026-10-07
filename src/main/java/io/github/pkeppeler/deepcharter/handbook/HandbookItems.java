package io.github.pkeppeler.deepcharter.handbook;

import java.util.ArrayList;
import java.util.List;

import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;

import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.allay.Allay;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.vehicle.ContainerEntity;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.BundleContents;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.LecternBlockEntity;

/**
 * The handbook item is bound to its holder: a player keeps exactly one, and a player who has none is given one. The item carries
 * no state of its own, so a new one is as good as the old. The binding is enforced with events, because the mod has no mixins, so
 * it has limits, listed last.
 *
 * <ul>
 *   <li>A dropped handbook never enters the world: Fabric's {@code ALLOW_LOAD} refuses its item entity, whatever dropped it,
 *       death included. The player then has none, and the sweep gives one back.</li>
 *   <li>A bundle or shulker box refuses it ({@link HandbookItem#canFitInsideContainerItems}).</li>
 *   <li>Each tick, {@link #sweep} takes the handbook out of any open menu's container (a chest, a crafting grid), strips it from
 *       the contents of any bundle or container item in the inventory, gives a player who holds none a new one (on join, after
 *       death, after any loss), and removes extra copies.</li>
 *   <li>Right-clicking an item frame, armor stand, allay or container entity, or a lectern or other storage block that has no
 *       menu, with the handbook is refused.</li>
 * </ul>
 *
 * <p>Limits: the sweep runs at the end of each tick, and Fabric has no hook for a menu closing. A player who puts the handbook in
 * a chest and closes the chest in the same tick leaves it in the chest; the player is given another at once. A player whose
 * inventory is full is given the handbook as soon as a slot is free.
 */
public final class HandbookItems {
	private HandbookItems() {
	}

	public static boolean isHandbook(ItemStack stack) {
		return stack.is(HandbookRegistry.HANDBOOK);
	}

	/** Binds the handbook: registers the events that enforce the rules above. */
	static void register() {
		ServerEntityEvents.ALLOW_LOAD.register((entity, level, reason, loaded) -> !(entity instanceof ItemEntity item && isHandbook(item.getItem())));
		ServerPlayerEvents.JOIN.register(HandbookItems::sweep);
		ServerTickEvents.END_SERVER_TICK.register(server -> server.getPlayerList().getPlayers().forEach(HandbookItems::sweep));
		UseEntityCallback.EVENT.register((player, level, hand, entity, hit) ->
				isHandbook(player.getItemInHand(hand)) && takesItems(entity) ? InteractionResult.FAIL : InteractionResult.PASS);
		UseBlockCallback.EVENT.register((player, level, hand, hit) ->
				isHandbook(player.getItemInHand(hand)) && storesWithoutMenu(level.getBlockEntity(hit.getBlockPos())) ? InteractionResult.FAIL : InteractionResult.PASS);
	}

	/** Enforces the binding for {@code player} once. Does nothing for a dead player: the respawned one is checked instead. */
	static void sweep(ServerPlayer player) {
		if (!player.isAlive()) {
			return;
		}
		takeOutOfContainers(player);
		stripFromContainerItems(player);
		int held = removeExtras(player);
		if (held == 0) {
			player.getInventory().add(new ItemStack(HandbookRegistry.HANDBOOK));
		}
	}

	private static void takeOutOfContainers(ServerPlayer player) {
		for (Slot slot : player.containerMenu.slots) {
			if (!(slot.container instanceof Inventory) && isHandbook(slot.getItem())) {
				slot.set(ItemStack.EMPTY);
			}
		}
	}

	/** Removes a handbook from the contents of every bundle and container item the player carries, however deep. */
	private static void stripFromContainerItems(ServerPlayer player) {
		Inventory inventory = player.getInventory();
		for (int index = 0; index < inventory.getContainerSize(); index++) {
			stripInside(inventory.getItem(index));
		}
		stripInside(player.containerMenu.getCarried());
	}

	private static boolean stripInside(ItemStack stack) {
		boolean changed = false;
		BundleContents bundle = stack.get(DataComponents.BUNDLE_CONTENTS);
		if (bundle != null) {
			List<ItemStack> kept = new ArrayList<>();
			boolean bundleChanged = false;
			for (ItemStack inner : bundle.itemCopies().toList()) {
				if (isHandbook(inner)) {
					bundleChanged = true;
				} else {
					bundleChanged |= stripInside(inner);
					kept.add(inner);
				}
			}
			if (bundleChanged) {
				stack.set(DataComponents.BUNDLE_CONTENTS, bundle.copyWithContents(kept.stream()));
				changed = true;
			}
		}
		ItemContainerContents contents = stack.get(DataComponents.CONTAINER);
		if (contents != null) {
			List<ItemStack> slots = new ArrayList<>();
			boolean contentsChanged = false;
			for (ItemStack inner : contents.itemCopies().toList()) {
				if (isHandbook(inner)) {
					contentsChanged = true;
					slots.add(ItemStack.EMPTY);
				} else {
					contentsChanged |= stripInside(inner);
					slots.add(inner);
				}
			}
			if (contentsChanged) {
				stack.set(DataComponents.CONTAINER, contents.copyWithContents(slots.stream()));
				changed = true;
			}
		}
		return changed;
	}

	/** Keeps the first handbook of the inventory and removes the rest. Returns how many handbooks the player holds, the cursor included. */
	private static int removeExtras(ServerPlayer player) {
		int held = isHandbook(player.containerMenu.getCarried()) ? 1 : 0;
		Inventory inventory = player.getInventory();
		for (int index = 0; index < inventory.getContainerSize(); index++) {
			if (!isHandbook(inventory.getItem(index))) {
				continue;
			}
			if (held == 0) {
				held = 1;
			} else {
				inventory.setItem(index, ItemStack.EMPTY);
			}
		}
		return held;
	}

	private static boolean takesItems(Entity entity) {
		return entity instanceof ItemFrame || entity instanceof ArmorStand || entity instanceof Allay || entity instanceof ContainerEntity;
	}

	/** A block that keeps an item without opening a menu, so the menu rule cannot catch it: lectern, jukebox, decorated pot and the like. */
	private static boolean storesWithoutMenu(BlockEntity block) {
		return (block instanceof Container || block instanceof LecternBlockEntity) && !(block instanceof BaseContainerBlockEntity);
	}
}
