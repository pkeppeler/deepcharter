package io.github.pkeppeler.deepcharter.hangar;

import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.WeakHashMap;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.charter.Charter;
import io.github.pkeppeler.deepcharter.charter.CharterRefusal;
import io.github.pkeppeler.deepcharter.charter.Charters;
import io.github.pkeppeler.deepcharter.colony.Colony;
import io.github.pkeppeler.deepcharter.colony.ColonyAnchor;
import io.github.pkeppeler.deepcharter.ore.OreRegistry;
import io.github.pkeppeler.deepcharter.pod.PodComponents;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.pod.Serials;
import io.github.pkeppeler.deepcharter.terminal.TerminalActions;
import io.github.pkeppeler.deepcharter.terminal.TerminalAction;
import io.github.pkeppeler.deepcharter.terminal.TerminalType;
import io.github.pkeppeler.deepcharter.terminal.TerminalTypes;
import io.github.pkeppeler.deepcharter.wreck.Wrecks;

/**
 * The hangar console: a terminal of its own, repaired by the four {@link HangarParts} (which repairs the founding Mole, see
 * {@link Hangar}), and then the place to buy a refurbished Mole ({@link #BUY_MOLE}) and to restore a wreck
 * ({@link #RESTORE_WRECK}). Both actions take no arguments, so the client sends nothing the server must trust.
 *
 * <p>An action does every check, and everything that can throw or refuse, before it takes money or the catalyst: a refusal
 * changes nothing. The catalyst and money go last.
 */
public final class HangarTerminal {
	public static final Identifier BUY_MOLE = Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "buy_mole");
	public static final Identifier RESTORE_WRECK = Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "restore_wreck");
	/**
	 * The console's id is not {@code hangar}: the colony stands a terminal on the plinth of the anchor with the type's name, and the
	 * hangar anchor is the bay, not a plinth. {@link Hangar} places the console beside the bay.
	 */
	public static final TerminalType TYPE = TerminalTypes.register(Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "hangar_console"), HangarParts.ALL);

	/** Serials whose unreadable data has been logged, so a refused purchase logs once for each and not once for each press. */
	private static final Set<Serials> UNREADABLE_SERIALS_LOGGED = Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));

	private HangarTerminal() {
	}

	static void register() {
		TerminalActions.register(TYPE, BUY_MOLE, HangarTerminal::buyMole);
		TerminalActions.register(TYPE, RESTORE_WRECK, HangarTerminal::restoreWreck);
	}

	private static Optional<Component> refuse(String reason, Object... args) {
		return Optional.of(Component.translatable("deepcharter.hangar.refusal." + reason, args));
	}

	/** Buys a refurbished Mole: $500 and $250 for each pod the charter has. It stands in a free place in the bay. */
	private static Optional<Component> buyMole(TerminalAction.Context context) {
		MinecraftServer server = context.server();
		Optional<HangarData> data = HangarData.readable(server);
		if (data.isEmpty()) {
			return refuse("unreadable");
		}
		if (!data.get().state().founded()) {
			return refuse("founding_pending");
		}
		Optional<BlockPos> anchor = Colony.anchor(server, ColonyAnchor.HANGAR);
		if (anchor.isEmpty()) {
			return refuse("no_bay");
		}
		Charter charter = context.charter().orElseThrow(() -> new IllegalStateException("the hangar console is for charters only"));
		long price = HangarTuning.DEFAULT.refurbishedPrice(Hangar.podsOf(server, data.get(), charter.id()));
		if (charter.account() < price) {
			return refuse("insufficient_funds", price);
		}
		ServerLevel level = server.overworld();
		Optional<Vec3> slot = Hangar.freeSlot(level, anchor.get());
		if (slot.isEmpty()) {
			return refuse("bay_full");
		}
		PodEntity pod = PodRegistry.POD.create(level, EntitySpawnReason.TRIGGERED);
		if (pod == null) {
			return refuse("pod_failed");
		}
		pod.setPos(slot.get());
		try {
			PodComponents.register(pod, charter.id());
		} catch (IllegalStateException unreadable) {
			// The saved serials are of a version this build cannot read, so no pod is registered: nothing has been taken yet.
			Serials serials = Serials.get(server);
			if (UNREADABLE_SERIALS_LOGGED.add(serials)) {
				DeepCharter.LOGGER.error("The hangar sells no Mole, because the saved serials cannot be read: {}", unreadable.getMessage());
			}
			return refuse("serials_unreadable");
		}
		String serial = PodComponents.registration(pod).orElseThrow().serial();
		if (!level.addFreshEntity(pod)) {
			return refuse("pod_failed");
		}
		Optional<CharterRefusal> refusal = Charters.spend(server, charter.id(), price);
		if (refusal.isPresent()) {
			pod.discard();
			throw new IllegalStateException("a purchase that passed the funds check was refused: " + refusal.get());
		}
		data.get().hold(charter.id(), pod.getUUID());
		context.player().sendOverlayMessage(Component.translatable("deepcharter.hangar.bought", pod.chassis().id().toUpperCase(Locale.ROOT), serial, price));
		return Optional.empty();
	}

	/** Restores the nearest wreck in reach of the console that the charter may access, for money and the catalyst. */
	private static Optional<Component> restoreWreck(TerminalAction.Context context) {
		MinecraftServer server = context.server();
		HangarTuning tuning = HangarTuning.DEFAULT;
		Optional<HangarData> data = HangarData.readable(server);
		if (data.isEmpty()) {
			return refuse("unreadable");
		}
		Charter charter = context.charter().orElseThrow(() -> new IllegalStateException("the hangar console is for charters only"));
		ServerPlayer player = context.player();
		Vec3 console = Vec3.atCenterOf(context.pos());
		List<PodEntity> inReach = player.level().getEntitiesOfClass(PodEntity.class, new AABB(context.pos()).inflate(tuning.wreckRadius())).stream()
				.filter(pod -> Wrecks.isWreck(pod) && !Hangar.isUnrepairedDerelict(data.get(), pod) && pod.position().distanceTo(console) <= tuning.wreckRadius())
				.sorted(Comparator.comparingDouble(pod -> pod.position().distanceToSqr(console)))
				.toList();
		if (inReach.isEmpty()) {
			return refuse("no_wreck");
		}
		Optional<PodEntity> usable = inReach.stream().filter(pod -> PodComponents.mayAccess(pod, Optional.of(charter))).findFirst();
		if (usable.isEmpty()) {
			return PodComponents.registration(inReach.getFirst()).map(registration -> refuse("not_your_wreck", registration.serial()))
					.orElseGet(() -> refuse("wreck_unreadable"));
		}
		PodEntity wreck = usable.get();
		if (charter.account() < tuning.restoreMoney()) {
			return refuse("insufficient_funds", tuning.restoreMoney());
		}
		Item catalyst = OreRegistry.item(tuning.catalyst());
		if (count(player.getInventory(), catalyst) < tuning.restoreCatalysts()) {
			return refuse("missing_catalyst", tuning.restoreCatalysts(), new ItemStack(catalyst).getHoverName().getString());
		}
		try {
			Wrecks.restore(wreck, wreck.maxHull());
		} catch (IllegalStateException unreadable) {
			DeepCharter.LOGGER.error("Could not restore pod {}: {}", wreck.getUUID(), unreadable.getMessage());
			return refuse("restore_failed");
		}
		take(player.getInventory(), catalyst, tuning.restoreCatalysts());
		Optional<CharterRefusal> refusal = Charters.spend(server, charter.id(), tuning.restoreMoney());
		if (refusal.isPresent()) {
			throw new IllegalStateException("a restore that passed the funds check was refused: " + refusal.get());
		}
		player.sendOverlayMessage(Component.translatable("deepcharter.hangar.restored", tuning.restoreMoney()));
		return Optional.empty();
	}

	private static int count(Inventory inventory, Item item) {
		int total = 0;
		for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
			if (inventory.getItem(slot).is(item)) {
				total += inventory.getItem(slot).getCount();
			}
		}
		return total;
	}

	/** Takes {@code amount} of {@code item}; the caller has checked that the inventory holds that many. */
	private static void take(Inventory inventory, Item item, int amount) {
		int left = amount;
		for (int slot = 0; slot < inventory.getContainerSize() && left > 0; slot++) {
			if (inventory.getItem(slot).is(item)) {
				left -= inventory.removeItem(slot, left).getCount();
			}
		}
		if (left > 0) {
			throw new IllegalStateException("the inventory held fewer than the " + amount + " that were counted");
		}
	}
}
