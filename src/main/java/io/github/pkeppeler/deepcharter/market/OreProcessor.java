package io.github.pkeppeler.deepcharter.market;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.WeakHashMap;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.charter.Charter;
import io.github.pkeppeler.deepcharter.charter.CharterRefusal;
import io.github.pkeppeler.deepcharter.charter.Charters;
import io.github.pkeppeler.deepcharter.ore.OreRegistry;
import io.github.pkeppeler.deepcharter.ore.OreType;
import io.github.pkeppeler.deepcharter.pod.PodCargo;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.terminal.TerminalAction;
import io.github.pkeppeler.deepcharter.terminal.Terminals;

/**
 * The two sales of the ore processor terminal. Each sells every ore at once, at the {@link OreType#value()} of each, and credits
 * the player's charter. A sale is all or nothing: the account is credited first, and the ore is taken only once the credit
 * went through, so a refused credit (a full account) leaves the ore where it was. The terminal has already checked the player's
 * range, the charter and the repair state.
 */
public final class OreProcessor {
	/** Sells the cargo of every pod the player may access parked at the processor ({@link Terminals#parkedPods}). */
	public static final Identifier SELL_CARGO = Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "sell_cargo");
	/** Sells every ore the player carries. */
	public static final Identifier SELL_INVENTORY = Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "sell_inventory");

	/** Pods whose unreadable cargo was logged, so each is logged once. */
	private static final Set<PodEntity> SKIPPED_LOGGED = Collections.newSetFromMap(new WeakHashMap<>());

	private OreProcessor() {
	}

	public static Optional<Component> sellCargo(TerminalAction.Context context) {
		Charter charter = context.charter().orElseThrow();
		List<PodEntity> pods = podsAt(context, charter);
		if (pods.isEmpty()) {
			return Optional.of(Component.translatable("deepcharter.market.refusal.no_pod"));
		}
		List<PodEntity> readable = new ArrayList<>();
		for (PodEntity pod : pods) {
			if (pod.cargo().isReadable()) {
				readable.add(pod);
			} else if (SKIPPED_LOGGED.add(pod)) {
				DeepCharter.LOGGER.error("Pod {}: cargo unreadable, not sold", pod.getUUID());
			}
		}
		int skipped = pods.size() - readable.size();
		if (readable.isEmpty()) {
			return Optional.of(Component.translatable("deepcharter.market.refusal.unreadable_cargo"));
		}
		long total = 0;
		int count = 0;
		for (PodEntity pod : readable) {
			for (PodCargo.Entry entry : pod.cargo().entries()) {
				total += value(entry.stack()) * entry.stack().getCount();
				count += entry.stack().getCount();
			}
		}
		Optional<Component> refusal = credit(context, charter, total, count, skipped);
		if (refusal.isEmpty()) {
			readable.forEach(pod -> pod.cargo().dump(pod));
		}
		return refusal;
	}

	public static Optional<Component> sellInventory(TerminalAction.Context context) {
		Charter charter = context.charter().orElseThrow();
		Inventory inventory = context.player().getInventory();
		long total = 0;
		int count = 0;
		for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
			ItemStack stack = inventory.getItem(slot);
			if (OreRegistry.typeOf(stack).isPresent()) {
				total += value(stack) * stack.getCount();
				count += stack.getCount();
			}
		}
		Optional<Component> refusal = credit(context, charter, total, count, 0);
		if (refusal.isEmpty()) {
			for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
				if (OreRegistry.typeOf(inventory.getItem(slot)).isPresent()) {
					inventory.setItem(slot, ItemStack.EMPTY);
				}
			}
		}
		return refusal;
	}

	/** Credits {@code total} dollars for {@code count} ore and tells the player, or returns why not. */
	private static Optional<Component> credit(TerminalAction.Context context, Charter charter, long total, int count, int skipped) {
		if (count == 0) {
			return Optional.of(Component.translatable("deepcharter.market.refusal.nothing_to_sell"));
		}
		Optional<Component> refusal = Charters.deposit(context.server(), charter.id(), total).map(CharterRefusal::message);
		if (refusal.isPresent()) {
			return refusal;
		}
		ServerPlayer player = context.player();
		player.sendOverlayMessage(skipped == 0 ? Component.translatable("deepcharter.market.sold", count, total)
				: Component.translatable("deepcharter.market.sold_skipped", count, total, skipped));
		return Optional.empty();
	}

	private static long value(ItemStack stack) {
		return OreRegistry.typeOf(stack).orElseThrow().value();
	}

	private static List<PodEntity> podsAt(TerminalAction.Context context, Charter charter) {
		return Terminals.parkedPods(context.player().level(), context.pos(), Optional.of(charter));
	}
}
