package io.github.pkeppeler.deepcharter.ore;

import java.util.List;

import net.fabricmc.fabric.api.menu.v1.ExtendedMenuProvider;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import io.github.pkeppeler.deepcharter.pod.PodCargo;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodStats;

/**
 * A look into a pod's cargo bay: one read-only slot per bay slot, and the cargo mass. It follows the bay while open.
 * Taking ore out is not here: terminals (a later issue) are where ore leaves a pod.
 *
 * <p>The bay's size is the pod's {@link PodStats#cargoSlots} when the menu opens (or the ore it holds, if that is more),
 * and the server sends it with the open packet, so the client builds the same number of slots.
 */
public final class OreCargoMenu extends AbstractContainerMenu {
	public static final int SLOT_X = 8;
	public static final int SLOT_Y = 20;
	public static final int SLOT_SIZE = 18;
	/** The most slots in a row: a big bay wraps into more rows. */
	public static final int MAX_COLUMNS = 15;
	private static final double REACH_SQUARED = 64;

	/** The pod whose bay this shows. Null on the client, which only mirrors what the server sends. */
	private final PodEntity pod;
	private final int slotCount;
	private final SimpleContainer view;
	private final ContainerData data;

	/** The client's menu: the server fills the slots and the mass, and sent how many slots there are. */
	public OreCargoMenu(int containerId, int slotCount) {
		this(containerId, null, slotCount, new SimpleContainerData(1));
	}

	private OreCargoMenu(int containerId, PodEntity pod, int slotCount, ContainerData data) {
		super(OreRegistry.CARGO_MENU, containerId);
		this.pod = pod;
		this.slotCount = slotCount;
		this.view = new SimpleContainer(slotCount);
		this.data = data;
		int columns = columns(slotCount);
		for (int i = 0; i < slotCount; i++) {
			addSlot(new ViewSlot(view, i, SLOT_X + i % columns * SLOT_SIZE, SLOT_Y + i / columns * SLOT_SIZE));
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
		// A bay that shrank below what it holds still shows all its ore.
		int slots = Math.max(PodStats.of(pod).cargoSlots(), pod.cargo().entries().size());
		player.openMenu(new ExtendedMenuProvider<Integer>() {
			@Override
			public Integer getScreenOpeningData(ServerPlayer opener) {
				return slots;
			}

			@Override
			public Component getDisplayName() {
				return Component.translatable("container.deepcharter.cargo");
			}

			@Override
			public AbstractContainerMenu createMenu(int id, Inventory inventory, Player opener) {
				return new OreCargoMenu(id, pod, slots, mass);
			}
		});
	}

	/** Slots in each row of a bay of {@code slotCount}. */
	public static int columns(int slotCount) {
		return Math.max(1, Math.min(slotCount, MAX_COLUMNS));
	}

	/** Rows of slots in a bay of {@code slotCount}. */
	public static int rows(int slotCount) {
		return Math.max(1, (slotCount + MAX_COLUMNS - 1) / MAX_COLUMNS);
	}

	/** How many slots this menu has, one for each bay slot. */
	public int cargoSlots() {
		return slotCount;
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
		for (int i = 0; i < slotCount; i++) {
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
