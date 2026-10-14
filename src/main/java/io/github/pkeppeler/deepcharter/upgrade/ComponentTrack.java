package io.github.pkeppeler.deepcharter.upgrade;

import com.mojang.serialization.Codec;

import io.netty.buffer.ByteBuf;

import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;
import net.minecraft.util.StringRepresentable;

import io.github.pkeppeler.deepcharter.DeepCharter;

/**
 * The tracks of pod parts (SPEC section 7). Tier 0 of a track is the stock part every pod has, so a part item is
 * tier 1 or more. The scanner, lights, spoil hopper, liner, sounder, brace and heat shield have no stock part: their tier 0 is "none".
 * Tier 1 of every track is a crafted unit fitted by use ({@link PartUnits}, ADR 0048 and ADR 0053), so the upgrade terminal sells tier 2 and up.
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
	HEAT_SHIELD("heat_shield", 1, false),
	CONSOLE("console", 3, false);

	public static final Codec<ComponentTrack> CODEC = StringRepresentable.fromEnum(ComponentTrack::values);
	public static final StreamCodec<ByteBuf, ComponentTrack> STREAM_CODEC =
			ByteBufCodecs.idMapper(ordinal -> values()[ordinal], ComponentTrack::ordinal);

	private final String id;
	private final int maxTier;
	private final boolean needsFirstTier;

	ComponentTrack(String id, int maxTier, boolean needsFirstTier) {
		this.id = id;
		this.maxTier = maxTier;
		this.needsFirstTier = needsFirstTier;
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
	 * True when tier 2 and up are sold only to a pod that has the crafted tier 1 fitted: the scanner (ADR 0048), which no Mole has a stock part of.
	 * Every other track sells tier 2 over what the pod has, the stock part or none.
	 */
	public boolean needsFirstTier() {
		return needsFirstTier;
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
