package io.github.pkeppeler.deepcharter.handbook;

import java.util.List;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;

/**
 * An item's spec sheet, its page in the parts catalogue: what it is, and its ratings (SPEC section 5, "The world is a mystery; the
 * rules never are"). Built by {@link SpecSheets#of}, never stored: the ratings are read from the game's own tuning each time.
 *
 * @param item the item
 * @param kind whether it is Company issue or stock
 * @param blurb what it is, in the Company's voice
 * @param ratings what it is rated for, in the order they are shown
 */
public record SpecSheet(Item item, Component kind, Component blurb, List<Rating> ratings) {
    public SpecSheet {
        ratings = List.copyOf(ratings);
    }

    /**
     * One line of a sheet: a rating and its figure.
     *
     * @param label what is rated
     * @param value its figure, with the unit
     */
    public record Rating(Component label, Component value) {}
}
