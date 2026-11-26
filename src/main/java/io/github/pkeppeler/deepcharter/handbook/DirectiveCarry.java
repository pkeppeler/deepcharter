package io.github.pkeppeler.deepcharter.handbook;

import io.github.pkeppeler.deepcharter.DeepCharter;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.resources.Identifier;

/**
 * How a saved set of completed directives reaches the directives this build ships, for both stores that hold one
 * ({@link HandbookProgressData}, {@link PersonalHandbookData}): a renamed id on every load (ADR 0047), and a step added
 * to a finished chapter once, when a store of the version before it is read.
 */
final class DirectiveCarry {
    /** A directive whose id changed, by its old id: whoever completed the old one has completed the new one. */
    private static final Map<Identifier, Identifier> RENAMED =
            Map.of(welcome("mine_iron_ore"), welcome("gather_scrap"));

    /** Version 1 stores predate chapter 1's charcoal step: finishing the old chapter counts as doing it (#1423). */
    private static final Map<Identifier, Set<Identifier>> ADDED_AFTER_VERSION_1 = Map.of(
            welcome("smelt_charcoal"),
            Set.of(
                    welcome("gather_wood"),
                    welcome("craft_crafting_table"),
                    welcome("craft_stone_tools"),
                    welcome("gather_scrap"),
                    welcome("smelt_iron"),
                    welcome("craft_scanner"),
                    welcome("make_light")));

    private DirectiveCarry() {}

    /** {@code completed}, in order, with the new id of each renamed one it holds added after it. */
    static Set<Identifier> renamed(Collection<Identifier> completed) {
        Set<Identifier> carried = new LinkedHashSet<>(completed);
        RENAMED.forEach((old, renamed) -> {
            if (carried.contains(old)) {
                carried.add(renamed);
            }
        });
        return carried;
    }

    /** {@code completed} as a version 1 store saved it, read as this build's: renamed, then given the steps added since. */
    static List<Identifier> fromVersion1(List<Identifier> completed) {
        Set<Identifier> carried = renamed(completed);
        ADDED_AFTER_VERSION_1.forEach((added, chapter) -> {
            if (carried.containsAll(chapter)) {
                carried.add(added);
            }
        });
        return List.copyOf(carried);
    }

    private static Identifier welcome(String directive) {
        return Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "handbook/welcome/" + directive);
    }
}
