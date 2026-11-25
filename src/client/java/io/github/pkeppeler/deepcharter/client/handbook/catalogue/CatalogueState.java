package io.github.pkeppeler.deepcharter.client.handbook.catalogue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import net.minecraft.world.entity.player.StackedItemContents;
import net.minecraft.world.item.Item;
import org.jspecify.annotations.Nullable;

/**
 * What the catalogue is set to, for the client session: the search, the craftable-now filter, the page, the favourites row's scroll, the recipe lookup and its history, whether the console shows the recipe or its total materials, and whether the panels are put away.
 * No drawing and no input here, so the rules of the filter and the lookup are testable on their own.
 *
 * <p>The history is EMI's: each lookup the player leaves (for another item, for the other mode, or put away) is remembered with the recipe
 * it showed, and {@link #back} walks them newest first. Stepping through one item's recipes is one page, not several. It lives as long as
 * this state, so it outlives a screen closing and is gone when the player leaves the world.
 */
public final class CatalogueState {
    /** Which recipes a lookup lists: the ones that make the item (R) or the ones that take it (U). */
    public enum Mode {
        RECIPES,
        USES
    }

    /** The item being looked up, in a mode, at the recipe shown out of those found (from 0). */
    public record Lookup(Item item, Mode mode, int index) {}

    /** How many lookups the history keeps: the oldest goes when another is added past it. */
    public static final int HISTORY_DEPTH = 32;

    private String query = "";
    private boolean craftableOnly;
    private int page;
    private int favouriteOffset;
    private boolean hidden;
    private @Nullable Lookup lookup;
    /** Whether the console shows the total materials of its recipe instead of the recipe; any other lookup turns it off. */
    private boolean materials;
    /** The first row of the total materials shown. */
    private int materialsOffset;
    /** The lookups left, newest first. */
    private final Deque<Lookup> history = new ArrayDeque<>();

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

    /** Whether the player has put the panels away: the screens open without them, until the button or the key brings them back. */
    public boolean hidden() {
        return hidden;
    }

    public void toggleHidden() {
        hidden = !hidden;
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

    /** Puts the recipe panel away: nothing is looked up. What it showed joins the history. */
    public void clear() {
        remember(lookup);
        lookup = null;
        showRecipe();
    }

    /** Whether the console shows the total materials of the recipe it shows ({@link MaterialsTree}) instead of the recipe. */
    public boolean materials() {
        return materials;
    }

    /** Switches the console between the recipe and its total materials; with nothing looked up there is nothing to switch. */
    public void toggleMaterials() {
        if (lookup != null) {
            materials = !materials;
            materialsOffset = 0;
        }
    }

    /** The first row of the total materials shown, kept inside {@code total} rows of which {@code rows} fit. */
    public int materialsOffset(int total, int rows) {
        materialsOffset = Math.clamp(materialsOffset, 0, Math.max(0, total - rows));
        return materialsOffset;
    }

    public void scrollMaterials(int delta) {
        materialsOffset = Math.max(0, materialsOffset + delta);
    }

    private void showRecipe() {
        materials = false;
        materialsOffset = 0;
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
        remember(now);
        lookup = new Lookup(item, mode, 0);
        showRecipe();
    }

    /** Adds {@code left} to the history, unless it is the page already newest there, and lets the oldest go past {@link #HISTORY_DEPTH}. */
    private void remember(@Nullable Lookup left) {
        if (left == null) {
            return;
        }
        Lookup newest = history.peekFirst();
        if (newest != null && newest.item() == left.item() && newest.mode() == left.mode()) {
            history.removeFirst();
        }
        history.addFirst(left);
        while (history.size() > HISTORY_DEPTH) {
            history.removeLast();
        }
    }

    /**
     * Goes back to the lookup left last, at the recipe it showed; returns whether there was one. A page of the item and mode shown now
     * (put away, then looked up again) is passed over and dropped: going back to it would change nothing.
     */
    public boolean back() {
        Lookup previous = history.pollFirst();
        while (previous != null && shownNow(previous)) {
            previous = history.pollFirst();
        }
        if (previous == null) {
            return false;
        }
        lookup = previous;
        showRecipe();
        return true;
    }

    /** Whether {@link #back} has a lookup to go back to. */
    public boolean canGoBack() {
        return history.stream().anyMatch(page -> !shownNow(page));
    }

    /** Whether {@code page} is of the item and mode looked up now. */
    private boolean shownNow(Lookup page) {
        Lookup now = lookup;
        return now != null && now.item() == page.item() && now.mode() == page.mode();
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
        materialsOffset = 0;
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
