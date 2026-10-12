package io.github.pkeppeler.deepcharter.upgrade;

import com.mojang.serialization.Codec;

import io.netty.buffer.ByteBuf;

import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;
import net.minecraft.util.StringRepresentable;

import io.github.pkeppeler.deepcharter.DeepCharter;

/**
 * The thirteen tracks of pod parts (SPEC section 7). Tier 0 of a track is the stock part every pod has, so a part item is
 * tier 1 or more. The scanner, lights, spoil hopper, liner, sounder, brace and heat shield have no stock part: their tier 0 is "none".
 */
public enum ComponentTrack implements StringRepresentable {
	DRILL("drill", 6, false),
	HULL("hull", 6, false),
	ENGINE("engine", 6, false),
	FUEL_TANK("fuel_tank", 6, false),
	RADIATOR("radiator", 5, false),
	CARGO_BAY("cargo_bay", 5, false),
	SCANNER("scanner", 4, true),
	LIGHTS("lights", 4, false),
	SPOIL_HOPPER("spoil_hopper", 1, false),
	LINER("liner", 2, false),
	SOUNDER("sounder", 2, false),
	BRACE("brace", 1, false),
	HEAT_SHIELD("heat_shield", 1, false);

	public static final Codec<ComponentTrack> CODEC = StringRepresentable.fromEnum(ComponentTrack::values);
	public static final StreamCodec<ByteBuf, ComponentTrack> STREAM_CODEC =
			ByteBufCodecs.idMapper(ordinal -> values()[ordinal], ComponentTrack::ordinal);

	private final String id;
	private final int maxTier;
	private final boolean firstTierCrafted;

	ComponentTrack(String id, int maxTier, boolean firstTierCrafted) {
		this.id = id;
		this.maxTier = maxTier;
		this.firstTierCrafted = firstTierCrafted;
	}

	@Override
	public String getSerializedName() {
		return id;
	}

	public String id() {
		return id;
	}

	/** The best tier a part of this track comes in. */
	public int maxTier() {
		return maxTier;
	}

	/**
	 * True when tier 1 of this track is made by the player and fitted from an item, and only the tiers above it are sold at the upgrade
	 * terminal, each needing the one fitted first. The scanner: by the time a pilot needs a better one, hand mining is too slow.
	 */
	public boolean firstTierCrafted() {
		return firstTierCrafted;
	}

	/** The id of this track's part item. */
	public Identifier itemId() {
		return Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "part_" + id);
	}

	/** Fails for a tier no part has: below 1 (that is the stock part) or above {@link #maxTier}. */
	public int requirePartTier(int tier) {
		if (tier < 1 || tier > maxTier) {
			throw new IllegalArgumentException("a " + id + " part has tier 1 to " + maxTier + ", got " + tier);
		}
		return tier;
	}
}
