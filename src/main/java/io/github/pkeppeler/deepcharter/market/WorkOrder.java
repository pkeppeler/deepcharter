package io.github.pkeppeler.deepcharter.market;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

import com.mojang.serialization.Codec;

import io.netty.buffer.ByteBuf;

import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;
import net.minecraft.util.StringRepresentable;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.ore.OreRegistry;
import io.github.pkeppeler.deepcharter.ore.OreType;

/**
 * Every work order of the game: an employer's request to hand in a quantity of one ore for a reward. Each charter's progress is
 * its own ({@link WorkOrderData}). A one-shot order is done once; a {@linkplain #repeatable() repeatable} one opens its next round
 * the moment a round completes. Names are saved, so never rename one.
 */
public enum WorkOrder implements StringRepresentable {
	/** Act 1. Completing it puts the Founder statue's hands back ({@code FounderStatue}). */
	FOUNDERS_HANDS(OreType.BRONZIUM, 10, MarketTuning.DEFAULT.foundersHandsReward(), false, 0),
	/**
	 * Act 2, Personnel's "Morale Initiative" (LORE.md). The canon names the order and gives no text for it, so the title is the only
	 * canon here; the ore, quantity and reward are placeholders. The reward is 25% over what the ore fetches at the ore processor, so
	 * it follows the ore's value. It opens once the charter has reached layer 3.
	 */
	MORALE_INITIATIVE(OreType.SILVERIUM, 10, Math.round(10 * OreType.SILVERIUM.value() * 1.25), true, 3);

	public static final Codec<WorkOrder> CODEC = StringRepresentable.fromEnum(WorkOrder::values);
	public static final StreamCodec<ByteBuf, WorkOrder> STREAM_CODEC = Identifier.STREAM_CODEC.map(WorkOrder::require, WorkOrder::id);

	private final OreType ore;
	private final int quantity;
	private final long reward;
	private final boolean repeatable;
	private final int unlockLayer;

	WorkOrder(OreType ore, int quantity, long reward, boolean repeatable, int unlockLayer) {
		this.ore = ore;
		this.quantity = quantity;
		this.reward = reward;
		this.repeatable = repeatable;
		this.unlockLayer = unlockLayer;
	}

	/** True when a completed round opens the next one at once, so the charter can do the order again and again. */
	public boolean repeatable() {
		return repeatable;
	}

	/** The layer a charter must have reached before the order is offered to it; 0 for an order offered from the start. */
	public int unlockLayer() {
		return unlockLayer;
	}

	/** The ore the order asks for. */
	public OreType ore() {
		return ore;
	}

	/** The display name of {@link #ore()}. */
	public Component oreName() {
		return Component.translatable(OreRegistry.item(ore).getDescriptionId());
	}

	/** How many of that ore completes the order. */
	public int quantity() {
		return quantity;
	}

	/** Dollars paid to the charter each time it completes the order (each round, for a repeatable one). */
	public long reward() {
		return reward;
	}

	/** The lang key of the order's title. */
	public String titleKey() {
		return "deepcharter.market.work_order." + getSerializedName();
	}

	@Override
	public String getSerializedName() {
		return name().toLowerCase(Locale.ROOT);
	}

	public Identifier id() {
		return Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, getSerializedName());
	}

	/** The order with this id, or empty. */
	public static Optional<WorkOrder> find(Identifier id) {
		return Arrays.stream(values()).filter(order -> order.id().equals(id)).findFirst();
	}

	/** The order with this id; an id that no order has is a bug. */
	public static WorkOrder require(Identifier id) {
		return find(id).orElseThrow(() -> new IllegalArgumentException("No work order " + id));
	}
}
