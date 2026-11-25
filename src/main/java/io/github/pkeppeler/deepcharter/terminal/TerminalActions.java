package io.github.pkeppeler.deepcharter.terminal;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import net.minecraft.resources.Identifier;

/** The actions of each terminal type. They run only on a repaired terminal; parts go in through its {@link RepairMenu}. */
public final class TerminalActions {
    private static final Map<TerminalType, Map<Identifier, TerminalAction>> ACTIONS = new HashMap<>();

    private TerminalActions() {}

    /** Adds {@code action} to {@code type}. An id may be registered once per type. */
    public static void register(TerminalType type, Identifier action, TerminalAction handler) {
        if (ACTIONS.computeIfAbsent(type, key -> new HashMap<>()).putIfAbsent(action, handler) != null) {
            throw new IllegalArgumentException("terminal " + type.id() + " already has the action " + action);
        }
    }

    static Optional<TerminalAction> find(TerminalType type, Identifier action) {
        return Optional.ofNullable(ACTIONS.getOrDefault(type, Map.of()).get(action));
    }
}
