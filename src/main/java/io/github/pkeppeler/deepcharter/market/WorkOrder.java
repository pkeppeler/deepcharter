package io.github.pkeppeler.deepcharter.market;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

import com.mojang.serialization.Codec;

import io.netty.buffer.ByteBuf;

import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;
import net.minecraft.util.StringRepresentable;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.ore.OreType;

/**
 * Every work order of the game: an employer's request to hand in a quantity of one ore for a reward. A charter does each order
 * once, and its progress is its own ({@link WorkOrderData}). Names are saved, so never rename one.
 */
public enum WorkOrder implements StringRepresentable {
	/** Act 1. Completing it puts the Founder statue's hands back ({@code FounderStatue}). */
	FOUNDERS_HANDS(OreType.BRONZIUM, 10);

	public static final Codec<WorkOrder> CODEC = StringRepresentable.fromEnum(WorkOrder::values);
	public static final StreamCodec<ByteBuf, WorkOrder> STREAM_CODEC = Identifier.STREAM_CODEC.map(WorkOrder::require, WorkOrder::id);

	private final OreType ore;
	private final int quantity;

	WorkOrder(OreType ore, int quantity) {
		this.ore = ore;
		this.quantity = quantity;
	}

	/** The ore the order asks for. */
	public OreType ore() {
		return ore;
	}

	/** How many of that ore completes the order. */
	public int quantity() {
		return quantity;
	}

	/** Dollars paid to the charter when it completes the order. */
	public long reward() {
		return switch (this) {
			case FOUNDERS_HANDS -> MarketTuning.DEFAULT.foundersHandsReward();
		};
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
