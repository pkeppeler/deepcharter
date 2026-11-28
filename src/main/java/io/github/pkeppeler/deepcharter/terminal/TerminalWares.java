package io.github.pkeppeler.deepcharter.terminal;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * What each terminal sells: the ware, the action that buys it and the line that says what it does (#1645). A
 * terminal's screen shows the line while the player browses the row, before the purchase, and a test walks every
 * action a terminal registers: each is a ware here or is named as no sale, so a new ware cannot ship unexplained. A
 * line is a lang key: for an item it is the same {@code deepcharter.spec.item.*} key as the spec sheet, so the two
 * never drift.
 */
public final class TerminalWares {
    private static final Map<TerminalType, List<Ware>> WARES = new HashMap<>();

    /** One thing a row sells: its id on the terminal, the action that sells it and the lang key of its line. */
    public record Ware(String id, Identifier action, String aboutKey) {}

    private TerminalWares() {}

    /**
     * Declares that {@code type} sells {@code ware} through {@code action}, and says what it does in {@code
     * aboutKey}.
     */
    public static void register(TerminalType type, Identifier action, String ware, String aboutKey) {
        List<Ware> wares = WARES.computeIfAbsent(type, key -> new ArrayList<>());
        if (wares.stream().anyMatch(known -> known.id().equals(ware))) {
            throw new IllegalArgumentException("terminal " + type.id() + " already sells " + ware);
        }
        wares.add(new Ware(ware, action, aboutKey));
    }

    /** Every ware {@code type} sells, in the order registered. */
    // test-seam: the tests walk every ware of every terminal to fail one with no line; a screen asks for one by id
    public static List<Ware> of(TerminalType type) {
        return List.copyOf(WARES.getOrDefault(type, List.of()));
    }

    /**
     * The line that says what {@code ware} of {@code type} does. A ware that was never registered is a bug in the
     * screen.
     */
    public static Component about(TerminalType type, String ware) {
        return WARES.getOrDefault(type, List.of()).stream()
                .filter(known -> known.id().equals(ware))
                .findFirst()
                .map(known -> Component.translatable(known.aboutKey()))
                .orElseThrow(() -> new IllegalStateException("terminal " + type.id() + " does not sell " + ware));
    }
}
