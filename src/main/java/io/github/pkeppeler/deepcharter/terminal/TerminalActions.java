package io.github.pkeppeler.deepcharter.terminal;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import net.minecraft.resources.Identifier;

/** The actions of each terminal type. {@link Terminals#INSERT_PART} is built in and belongs to every type before it is repaired. */
public final class TerminalActions {
	private static final Map<TerminalType, Map<Identifier, TerminalAction>> ACTIONS = new HashMap<>();

	private TerminalActions() {
	}

	/** Adds {@code action} to {@code type}. An id may be registered once per type, and not as {@link Terminals#INSERT_PART}. */
	public static void register(TerminalType type, Identifier action, TerminalAction handler) {
		if (action.equals(Terminals.INSERT_PART)) {
			throw new IllegalArgumentException(action + " is built in");
		}
		if (ACTIONS.computeIfAbsent(type, key -> new HashMap<>()).putIfAbsent(action, handler) != null) {
			throw new IllegalArgumentException("terminal " + type.id() + " already has the action " + action);
		}
	}

	static Optional<TerminalAction> find(TerminalType type, Identifier action) {
		return Optional.ofNullable(ACTIONS.getOrDefault(type, Map.of()).get(action));
	}
}
