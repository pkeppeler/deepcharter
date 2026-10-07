package io.github.pkeppeler.deepcharter.terminal;

import java.util.Locale;

import net.minecraft.network.chat.Component;

/** Why a terminal operation was refused. A refusal changes nothing. */
public enum TerminalRefusal {
	/** No terminal block (with its block entity) at that position. */
	NO_SUCH_TERMINAL,
	/** The player is more than {@link TerminalTuning#maxDistance()} blocks away. */
	TOO_FAR,
	NOT_ON_A_CHARTER,
	/** An action that needs a working terminal, at one that is not repaired yet. */
	UNREPAIRED,
	/** A part for a terminal whose predecessor in the repair order is not repaired yet. */
	PREREQUISITE_UNREPAIRED,
	ALREADY_REPAIRED,
	NOT_A_PART,
	ALREADY_INSERTED,
	MISSING_PART,
	NO_SUCH_ACTION,
	/** The action's own handler refused: the player was told why. */
	ACTION_REFUSED;

	public String translationKey() {
		return "deepcharter.terminal.refusal." + name().toLowerCase(Locale.ROOT);
	}

	public Component message() {
		return Component.translatable(translationKey());
	}
}
