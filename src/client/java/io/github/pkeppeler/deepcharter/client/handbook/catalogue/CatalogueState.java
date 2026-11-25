package io.github.pkeppeler.deepcharter.client.handbook.catalogue;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.entity.player.StackedItemContents;
import net.minecraft.world.item.Item;
import org.jspecify.annotations.Nullable;

/**
 * What the catalogue is set to, for the client session: the search, the craftable-now filter, the page, the favourites row's scroll and the recipe lookup.
 * No drawing and no input here, so the rules of the filter and the lookup are testable on their own.
 */
public final class CatalogueState {
    /** Which recipes a lookup lists: the ones that make the item (R) or the ones that take it (U). */
    public enum Mode {
        RECIPES,
        USES
    }

    /** The item being looked up, in a mode, at the recipe shown out of those found (from 0). */
    public record Lookup(Item item, Mode mode, int index) {}

    private String query = "";
    private boolean craftableOnly;
    private int page;
    private int favouriteOffset;
    private @Nullable Lookup lookup;

    public String query() {
        return query;
    }

    /** Sets the search and goes back to the first page. */
    public void setQuery(String query) {
        if (!query.equals(this.query)) {
            this.query = query;
            this.page = 0;
        }
    }

    public boolean craftableOnly() {
        return craftableOnly;
    }

    /** Flips the filter. The page stays; {@link #page(int)} brings it inside the new page count. */
    public void toggleCraftableOnly() {
        craftableOnly = !craftableOnly;
    }

    /** The known items that match the search and, with the filter on, can be made at {@code bench} from {@code contents} now. */
    public List<Item> shown(PartsCatalogue catalogue, StackedItemContents contents, Bench bench) {
        List<Item> shown = new ArrayList<>();
        for (Item item : catalogue.items()) {
            if (CatalogueSearch.matches(query, item)
                    && (!craftableOnly || catalogue.craftable(item, contents, bench))) {
                shown.add(item);
            }
        }
        return shown;
    }

    /** How many known items can be made at {@code bench} from {@code contents} now, whatever the search and the filter say. */
    public int craftableCount(PartsCatalogue catalogue, StackedItemContents contents, Bench bench) {
        int count = 0;
        for (Item item : catalogue.items()) {
            if (catalogue.craftable(item, contents, bench)) {
                count++;
            }
        }
        return count;
    }

    /** How many pages {@code total} items make at {@code perPage} to a page; at least one. */
    public static int pages(int total, int perPage) {
        return Math.max(1, (total + perPage - 1) / perPage);
    }

    /** The page being shown, from 0, inside {@code pages}. The page kept is not changed: a list that is briefly short does not forget it. */
    public int page(int pages) {
        return Math.clamp(page, 0, pages - 1);
    }

    /** Moves {@code delta} pages from the one shown, stopping at the first and the last, and keeps that page. */
    public void turn(int delta, int pages) {
        page = Math.clamp((long) page(pages) + delta, 0, pages - 1);
    }

    /** The first favourite shown in the row, kept so that the row stays full while there are enough. */
    public int favouriteOffset(int favourites, int columns) {
        favouriteOffset = Math.clamp(favouriteOffset, 0, Math.max(0, favourites - columns));
        return favouriteOffset;
    }

    public void scrollFavourites(int delta) {
        favouriteOffset = Math.max(0, favouriteOffset + delta);
    }

    /** Puts the recipe panel away: nothing is looked up. */
    public void clear() {
        lookup = null;
    }

    public @Nullable Lookup lookup() {
        return lookup;
    }

    /**
     * R or U on {@code item}: lists its recipes (or its uses). The same key on the item already shown steps to the next recipe, as in
     * EMI, and wraps round.
     */
    public void look(PartsCatalogue catalogue, Item item, Mode mode) {
        Lookup now = lookup;
        if (now != null && now.item() == item && now.mode() == mode) {
            step(catalogue, 1);
            return;
        }
        lookup = new Lookup(item, mode, 0);
    }

    /** Shows the recipe {@code delta} on from the one shown, wrapping round. */
    public void step(PartsCatalogue catalogue, int delta) {
        Lookup now = lookup;
        if (now == null) {
            return;
        }
        int count = found(catalogue).size();
        int index = count == 0 ? 0 : Math.floorMod(now.index() + delta, count);
        lookup = new Lookup(now.item(), now.mode(), index);
    }

    /** The recipes the lookup lists; none when nothing is looked up or the charter knows none. */
    public List<KnownRecipe> found(PartsCatalogue catalogue) {
        Lookup now = lookup;
        if (now == null) {
            return List.of();
        }
        return now.mode() == Mode.RECIPES ? catalogue.recipesFor(now.item()) : catalogue.usesOf(now.item());
    }

    /** The recipe the lookup shows, or null when it lists none. */
    public @Nullable KnownRecipe shownRecipe(PartsCatalogue catalogue) {
        Lookup now = lookup;
        List<KnownRecipe> found = found(catalogue);
        if (now == null || found.isEmpty()) {
            return null;
        }
        return found.get(Math.clamp(now.index(), 0, found.size() - 1));
    }
}
