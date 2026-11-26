package io.github.pkeppeler.deepcharter.client.handbook;

import io.github.pkeppeler.deepcharter.client.handbook.catalogue.CatalogueKeys;
import io.github.pkeppeler.deepcharter.client.pod.LiningKeys;
import io.github.pkeppeler.deepcharter.client.pod.SonarKeys;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Options;
import net.minecraft.network.chat.Component;

/**
 * The controls pages of the handbook (#1260): one sheet each for the handbook, the parts catalogue, the pod and the
 * scanner. Every key a line names is read from the player's own binding when the line is built, so a rebind shows on
 * the next draw and no key letter is written into the language file. A key the game does not let the player rebind
 * (Ctrl+F, the arrow keys) is written out in the line.
 */
public final class HandbookControls {
    /**
     * One line of a sheet: its language key under the sheet's, and the bindings its {@code %s} slots show, in order.
     */
    record Row(String name, List<Function<Options, KeyMapping>> keys) {
        /** A line that names no key. */
        Row(String name) {
            this(name, List.of());
        }
    }

    /** One page of controls: its name, which keys its language keys, and its lines. */
    record Sheet(String name, List<Row> rows) {}

    private static final List<Sheet> SHEETS = List.of(
            new Sheet(
                    "handbook",
                    List.of(
                            new Row("open", List.of(options -> HandbookKeys.OPEN, options -> options.keyAdvancements)),
                            new Row("turn"))),
            new Sheet("cards", List.of(new Row("card"), new Row("any"))),
            new Sheet(
                    "catalogue",
                    List.of(
                            new Row("open", List.of(options -> options.keyInventory)),
                            new Row("toggle", List.of(options -> CatalogueKeys.TOGGLE)),
                            new Row("search"))),
            new Sheet(
                    "lookup",
                    List.of(
                            new Row("look", List.of(options -> CatalogueKeys.RECIPES, options -> CatalogueKeys.USES)),
                            new Row("star", List.of(options -> CatalogueKeys.FAVOURITE)))),
            new Sheet("console", List.of(new Row("star"), new Row("back", List.of(options -> CatalogueKeys.BACK)))),
            new Sheet("spec", List.of(new Row("open", List.of(options -> CatalogueKeys.SPEC_SHEET)))),
            new Sheet("shelf", List.of(new Row("craftable"))),
            new Sheet(
                    "board",
                    List.of(
                            new Row("board", List.of(options -> options.keyUse)),
                            new Row("leave", List.of(options -> options.keyShift)),
                            // The drive sheet is full at the smallest GUI, so the boost is told here (#1276).
                            new Row("sprint", List.of(options -> options.keySprint, options -> options.keyUp)))),
            new Sheet(
                    "drive",
                    List.of(
                            new Row(
                                    "drive",
                                    List.of(
                                            options -> options.keyUp,
                                            options -> options.keyDown,
                                            options -> options.keyLeft,
                                            options -> options.keyRight)),
                            new Row("camera", List.of(options -> options.keyTogglePerspective)),
                            new Row("climb", List.of(options -> options.keyJump)))),
            new Sheet(
                    "dig",
                    List.of(
                            new Row("down", List.of(options -> options.keySprint)),
                            new Row("side", List.of(options -> options.keyUp)))),
            new Sheet(
                    "crew",
                    List.of(
                            new Row("line", List.of(options -> LiningKeys.LINE)),
                            new Row("sonar", List.of(options -> SonarKeys.PING, options -> SonarKeys.MARK)))),
            new Sheet("scanner", List.of(new Row("map"), new Row("reach"))));

    private HandbookControls() {}

    /** How many controls pages the handbook binds. */
    public static int sheets() {
        return SHEETS.size();
    }

    /** The lang key of the title of controls page {@code part}, counted from 1. */
    public static String titleKey(int part) {
        return keyOf(sheet(part).name(), "title");
    }

    /** The lines of controls page {@code part}, counted from 1, with each key named as {@code options} binds it now. */
    static List<Component> lines(int part, Options options) {
        Sheet sheet = sheet(part);
        return sheet.rows().stream().map(row -> line(sheet, row, options)).toList();
    }

    /** Every binding a line of any sheet names, as {@code options} binds them now. */
    // test-seam: the keys-are-all-listed guard reads the sheets' bindings, which no draw path needs as a set
    public static Set<KeyMapping> mappings(Options options) {
        Set<KeyMapping> mappings = new LinkedHashSet<>();
        for (Sheet sheet : SHEETS) {
            for (Row row : sheet.rows()) {
                row.keys().forEach(key -> mappings.add(key.apply(options)));
            }
        }
        return mappings;
    }

    private static Component line(Sheet sheet, Row row, Options options) {
        Object[] keys = row.keys().stream()
                .map(key -> (Object) Component.literal("[")
                        .append(key.apply(options).getTranslatedKeyMessage())
                        .append("]"))
                .toArray();
        return Component.translatable(keyOf(sheet.name(), row.name()), keys);
    }

    private static Sheet sheet(int part) {
        return SHEETS.get(part - 1);
    }

    private static String keyOf(String sheet, String row) {
        return "deepcharter.handbook.controls." + sheet + "." + row;
    }
}
