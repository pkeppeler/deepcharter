package io.github.pkeppeler.deepcharter.upgrade;

import com.mojang.serialization.Codec;

import io.netty.buffer.ByteBuf;

import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;
import net.minecraft.util.StringRepresentable;

import io.github.pkeppeler.deepcharter.DeepCharter;

/**
 * The eleven tracks of pod parts (SPEC section 7). Tier 0 of a track is the stock part every pod has, so a part item is
 * tier 1 or more. The scanner, lights, spoil hopper, liner and sounder have no stock part: their tier 0 is "none".
 */
public enum ComponentTrack implements StringRepresentable {
	DRILL("drill", 6),
	HULL("hull", 6),
	ENGINE("engine", 6),
	FUEL_TANK("fuel_tank", 6),
	RADIATOR("radiator", 5),
	CARGO_BAY("cargo_bay", 5),
	SCANNER("scanner", 4),
	LIGHTS("lights", 4),
	SPOIL_HOPPER("spoil_hopper", 1),
	LINER("liner", 2),
	SOUNDER("sounder", 2);

	public static final Codec<ComponentTrack> CODEC = StringRepresentable.fromEnum(ComponentTrack::values);
	public static final StreamCodec<ByteBuf, ComponentTrack> STREAM_CODEC =
			ByteBufCodecs.idMapper(ordinal -> values()[ordinal], ComponentTrack::ordinal);

	private final String id;
	private final int maxTier;

	ComponentTrack(String id, int maxTier) {
		this.id = id;
		this.maxTier = maxTier;
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
