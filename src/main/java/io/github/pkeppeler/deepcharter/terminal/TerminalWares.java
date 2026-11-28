package io.github.pkeppeler.deepcharter.terminal;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.network.chat.Component;

/**
 * What each terminal sells, and the line that says what each ware does. A terminal's screen shows the line while the
 * player browses the row, before the purchase, and a test fails a terminal that sells something with no line, so a
 * new ware cannot ship unexplained (#1645). A line is a lang key: for an item it is the same {@code
 * deepcharter.spec.item.*} key as the spec sheet, so the two never drift.
 */
public final class TerminalWares {
    private static final Map<TerminalType, Map<String, String>> WARES = new HashMap<>();

    private TerminalWares() {}

    /**
     * Declares that {@code type} sells {@code ware}, and that {@code aboutKey} is the lang key of the line that says
     * what it does.
     */
    public static void register(TerminalType type, String ware, String aboutKey) {
        if (WARES.computeIfAbsent(type, key -> new LinkedHashMap<>()).putIfAbsent(ware, aboutKey) != null) {
            throw new IllegalArgumentException("terminal " + type.id() + " already sells " + ware);
        }
    }

    /**
     * The lang key of every ware {@code type} sells, by ware id, in the order registered; empty for a terminal that
     * sells nothing.
     */
    // test-seam: the tests walk every ware of every terminal to fail one with no line; a screen asks for one by id
    public static Map<String, String> of(TerminalType type) {
        return Collections.unmodifiableMap(WARES.getOrDefault(type, Map.of()));
    }

    /**
     * The line that says what {@code ware} of {@code type} does. A ware that was never registered is a bug in the
     * screen.
     */
    public static Component about(TerminalType type, String ware) {
        String key = WARES.getOrDefault(type, Map.of()).get(ware);
        if (key == null) {
            throw new IllegalStateException("terminal " + type.id() + " does not sell " + ware);
        }
        return Component.translatable(key);
    }
}
