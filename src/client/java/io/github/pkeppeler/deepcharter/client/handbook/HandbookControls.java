package io.github.pkeppeler.deepcharter.client.handbook;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.client.handbook.catalogue.CatalogueKeys;
import io.github.pkeppeler.deepcharter.client.pod.LiningKeys;
import io.github.pkeppeler.deepcharter.client.pod.SonarKeys;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Stream;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Options;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * The controls of the handbook: the controls pages after the contents (#1260), for the handbook and the parts
 * catalogue, and a sheet in a chapter for what that chapter brings (#1414): the Mole's in Meet the Mole, the scanner's
 * in Seeing Below. A chapter's sheet is named for the chapter's id path, so its language keys are {@code
 * deepcharter.handbook.controls.<path>.*}. Every key a line names is read from the player's own binding when the line is built, so a rebind shows on
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

    /** One sheet of controls: its name, which keys its language keys, and its lines. */
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
            new Sheet("shelf", List.of(new Row("craftable"))));

    /** The sheets bound into chapters, each named for its chapter's id path. */
    private static final List<Sheet> CHAPTER_SHEETS = List.of(
            new Sheet(
                    "meet_the_mole",
                    List.of(
                            new Row("board", List.of(options -> options.keyUse, options -> options.keyShift)),
                            new Row(
                                    "drive",
                                    List.of(
                                            options -> options.keyUp,
                                            options -> options.keyDown,
                                            options -> options.keyLeft,
                                            options -> options.keyRight,
                                            options -> options.keyJump)),
                            new Row("dig", List.of(options -> options.keySprint)),
                            new Row("view", List.of(options -> options.keyTogglePerspective)),
                            new Row(
                                    "crew",
                                    List.of(
                                            options -> LiningKeys.LINE,
                                            options -> SonarKeys.PING,
                                            options -> SonarKeys.MARK)))),
            new Sheet("seeing_below", List.of(new Row("scanner"))));

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
        return lines(sheet(part), options);
    }

    /** Whether chapter {@code chapter} has a sheet of controls bound into it. */
    static boolean inChapter(Identifier chapter) {
        return chapterSheet(chapter).isPresent();
    }

    /** The lang key of the title of the controls sheet of chapter {@code chapter}. */
    static String titleKey(Identifier chapter) {
        return keyOf(requireChapterSheet(chapter).name(), "title");
    }

    /** The lines of the controls sheet of chapter {@code chapter}, each key named as {@code options} binds it now. */
    static List<Component> lines(Identifier chapter, Options options) {
        return lines(requireChapterSheet(chapter), options);
    }

    /** Every binding a line of any sheet names, as {@code options} binds them now. */
    // test-seam: the keys-are-all-listed guard reads the sheets' bindings, which no draw path needs as a set
    public static Set<KeyMapping> mappings(Options options) {
        Set<KeyMapping> mappings = new LinkedHashSet<>();
        for (Sheet sheet : Stream.concat(SHEETS.stream(), CHAPTER_SHEETS.stream()).toList()) {
            for (Row row : sheet.rows()) {
                row.keys().forEach(key -> mappings.add(key.apply(options)));
            }
        }
        return mappings;
    }

    private static List<Component> lines(Sheet sheet, Options options) {
        return sheet.rows().stream().map(row -> line(sheet, row, options)).toList();
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

    private static Optional<Sheet> chapterSheet(Identifier chapter) {
        return CHAPTER_SHEETS.stream()
                .filter(sheet -> chapter.getNamespace().equals(DeepCharter.MOD_ID)
                        && sheet.name().equals(chapter.getPath()))
                .findFirst();
    }

    private static Sheet requireChapterSheet(Identifier chapter) {
        return chapterSheet(chapter)
                .orElseThrow(() -> new IllegalArgumentException("chapter " + chapter + " has no controls sheet"));
    }

    private static String keyOf(String sheet, String row) {
        return "deepcharter.handbook.controls." + sheet + "." + row;
    }
}
