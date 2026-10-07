package io.github.pkeppeler.deepcharter.colony;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

import com.mojang.serialization.Codec;

import net.minecraft.util.StringRepresentable;

import io.github.pkeppeler.deepcharter.terminal.TerminalType;

/**
 * The places of the colony that other features look up in {@link ColonySite}. Each one is a block position in the overworld:
 * the block a player stands in, or for a terminal plinth, the block the terminal stands in. Names are saved, so never rename one.
 */
public enum ColonyAnchor implements StringRepresentable {
	FUEL_PUMP,
	ORE_PROCESSOR,
	UPGRADE_TERMINAL,
	REPAIR_STATION,
	/** The plinth of the contract terminal, which #72 registers. The block stays air until that issue puts the terminal here. */
	CONTRACT_TERMINAL,
	HANGAR,
	/** The world spawn and the respawn point of the colony. */
	CONTINUITY_OFFICE,
	STATUE,
	CHAPEL_CANDLE,
	BUNKHOUSE,
	PAY_OFFICE,
	PERSONNEL_OFFICE,
	LAMP_AND_PICK,
	/** The centre column of the Conduit. The Conduit has the same X and Z in every layer. */
	CONDUIT;

	public static final Codec<ColonyAnchor> CODEC = StringRepresentable.fromEnum(ColonyAnchor::values);

	@Override
	public String getSerializedName() {
		return name().toLowerCase(Locale.ROOT);
	}

	/** The plinth anchor of a terminal type, matched by the path of the type's id; empty for a type with no plinth. */
	public static Optional<ColonyAnchor> forTerminal(TerminalType type) {
		return Arrays.stream(values()).filter(anchor -> anchor.getSerializedName().equals(type.id().getPath())).findFirst();
	}
}
