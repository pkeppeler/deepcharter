package io.github.pkeppeler.deepcharter.market;

import java.util.List;
import java.util.Optional;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.charter.Charter;
import io.github.pkeppeler.deepcharter.charter.CharterRefusal;
import io.github.pkeppeler.deepcharter.charter.Charters;
import io.github.pkeppeler.deepcharter.ore.OreRegistry;
import io.github.pkeppeler.deepcharter.ore.OreType;
import io.github.pkeppeler.deepcharter.pod.PodCargo;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.terminal.TerminalAction;

/**
 * The two sales of the ore processor terminal. Each sells every ore at once, at the {@link OreType#value()} of each, and credits
 * the player's charter. A sale is all or nothing: the account is credited first, and the ore is taken only once the credit
 * went through, so a refused credit (a full account) leaves the ore where it was. The terminal has already checked the player's
 * range, the charter and the repair state.
 */
public final class OreProcessor {
	/** Sells the cargo of every pod of the player's charter parked within {@link MarketTuning#processorRadius()} of the processor. */
	public static final Identifier SELL_CARGO = Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "sell_cargo");
	/** Sells every ore the player carries. */
	public static final Identifier SELL_INVENTORY = Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "sell_inventory");

	private OreProcessor() {
	}

	/**
	 * The one place that decides whether {@code pod} may be sold from for {@code charter}. Pod ownership (#65) is not merged
	 * yet, so every pod parked at the processor counts; when #65 lands this becomes "the pod belongs to the charter".
	 */
	static boolean mayTradeFor(PodEntity pod, Charter charter) {
		return true;
	}

	public static Optional<Component> sellCargo(TerminalAction.Context context) {
		Charter charter = context.charter().orElseThrow();
		List<PodEntity> pods = podsAt(context, charter);
		if (pods.isEmpty()) {
			return Optional.of(Component.translatable("deepcharter.market.refusal.no_pod"));
		}
		if (pods.stream().anyMatch(pod -> !pod.cargo().isReadable())) {
			return Optional.of(Component.translatable("deepcharter.market.refusal.unreadable_cargo"));
		}
		long total = 0;
		int count = 0;
		for (PodEntity pod : pods) {
			for (PodCargo.Entry entry : pod.cargo().entries()) {
				total += value(entry.stack());
				count++;
			}
		}
		Optional<Component> refusal = credit(context, charter, total, count);
		if (refusal.isEmpty()) {
			pods.forEach(pod -> pod.cargo().dump(pod));
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
		Optional<Component> refusal = credit(context, charter, total, count);
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
	private static Optional<Component> credit(TerminalAction.Context context, Charter charter, long total, int count) {
		if (count == 0) {
			return Optional.of(Component.translatable("deepcharter.market.refusal.nothing_to_sell"));
		}
		Optional<CharterRefusal> refusal = Charters.deposit(context.server(), charter.id(), total);
		if (refusal.isPresent()) {
			return Optional.of(refusal.get().message());
		}
		ServerPlayer player = context.player();
		player.sendOverlayMessage(Component.translatable("deepcharter.market.sold", count, total));
		return Optional.empty();
	}

	private static long value(ItemStack stack) {
		return OreRegistry.typeOf(stack).orElseThrow().value();
	}

	private static List<PodEntity> podsAt(TerminalAction.Context context, Charter charter) {
		double radius = MarketTuning.DEFAULT.processorRadius();
		Vec3 centre = Vec3.atCenterOf(context.pos());
		return context.player().level().getEntitiesOfClass(PodEntity.class, new AABB(centre, centre).inflate(radius),
				pod -> pod.position().distanceToSqr(centre) <= radius * radius && mayTradeFor(pod, charter));
	}
}
