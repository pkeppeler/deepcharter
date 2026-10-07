package io.github.pkeppeler.deepcharter.ore;

import java.util.List;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import io.github.pkeppeler.deepcharter.pod.PodCargo;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodTuning;

/**
 * A look into a pod's cargo bay: one read-only slot per bay slot, and the cargo mass. It follows the bay while open.
 * Taking ore out is not here: terminals (a later issue) are where ore leaves a pod.
 */
public final class OreCargoMenu extends AbstractContainerMenu {
	public static final int SLOTS = PodTuning.DEFAULT.cargo().slots();
	public static final int SLOT_X = 8;
	public static final int SLOT_Y = 20;
	public static final int SLOT_SIZE = 18;
	private static final double REACH_SQUARED = 64;

	/** The pod whose bay this shows. Null on the client, which only mirrors what the server sends. */
	private final PodEntity pod;
	private final SimpleContainer view = new SimpleContainer(SLOTS);
	private final ContainerData data;

	/** The client's menu: the server fills the slots and the mass. */
	public OreCargoMenu(int containerId, Inventory inventory) {
		this(containerId, null, new SimpleContainerData(1));
	}

	private OreCargoMenu(int containerId, PodEntity pod, ContainerData data) {
		super(OreRegistry.CARGO_MENU, containerId);
		this.pod = pod;
		this.data = data;
		for (int i = 0; i < SLOTS; i++) {
			addSlot(new ViewSlot(view, i, SLOT_X + i * SLOT_SIZE, SLOT_Y));
		}
		addDataSlots(data);
		refresh();
	}

	/** Server only: opens the pod's cargo for the player. */
	public static void open(ServerPlayer player, PodEntity pod) {
		ContainerData mass = new ContainerData() {
			@Override
			public int get(int index) {
				return Math.round(pod.cargoMass());
			}

			@Override
			public void set(int index, int value) {
				throw new UnsupportedOperationException("the cargo mass is read from the pod");
			}

			@Override
			public int getCount() {
				return 1;
			}
		};
		player.openMenu(new SimpleMenuProvider((id, inventory, ignored) -> new OreCargoMenu(id, pod, mass),
				Component.translatable("container.deepcharter.cargo")));
	}

	/** The ore the bay holds, in slot order. */
	public List<ItemStack> shownOre() {
		return view.getItems().stream().filter(stack -> !stack.isEmpty()).map(ItemStack::copy).toList();
	}

	/** The cargo mass, whole units. */
	public int cargoMass() {
		return data.get(0);
	}

	@Override
	public void broadcastChanges() {
		refresh();
		super.broadcastChanges();
	}

	private void refresh() {
		if (pod == null) {
			return;
		}
		List<PodCargo.Entry> entries = pod.cargo().entries();
		for (int i = 0; i < SLOTS; i++) {
			ItemStack shown = i < entries.size() ? entries.get(i).stack().copy() : ItemStack.EMPTY;
			if (!ItemStack.matches(view.getItem(i), shown)) {
				view.setItem(i, shown);
			}
		}
	}

	@Override
	public ItemStack quickMoveStack(Player player, int index) {
		return ItemStack.EMPTY;
	}

	@Override
	public boolean stillValid(Player player) {
		return pod == null || pod.isAlive() && player.distanceToSqr(pod) <= REACH_SQUARED;
	}

	private static final class ViewSlot extends Slot {
		ViewSlot(Container container, int index, int x, int y) {
			super(container, index, x, y);
		}

		@Override
		public boolean mayPlace(ItemStack stack) {
			return false;
		}

		@Override
		public boolean mayPickup(Player player) {
			return false;
		}
	}
}
