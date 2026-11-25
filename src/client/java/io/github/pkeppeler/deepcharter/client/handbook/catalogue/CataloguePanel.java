package io.github.pkeppeler.deepcharter.client.handbook.catalogue;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.TextInputManager;
import io.github.pkeppeler.deepcharter.client.handbook.catalogue.CatalogueLayout.Rect;
import io.github.pkeppeler.deepcharter.client.handbook.catalogue.SpecSheetView.Placed;
import io.github.pkeppeler.deepcharter.client.theme.CatalogueLook;
import io.github.pkeppeler.deepcharter.client.theme.PanelLook;
import io.github.pkeppeler.deepcharter.client.ui.CrtDraw;
import io.github.pkeppeler.deepcharter.client.ui.CrtText;
import io.github.pkeppeler.deepcharter.client.ui.CrtTuning;
import io.github.pkeppeler.deepcharter.handbook.SpecSheet;
import io.github.pkeppeler.deepcharter.handbook.SpecSheets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractRecipeBookScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;
import net.minecraft.util.context.ContextMap;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.StackedItemContents;
import net.minecraft.world.inventory.AbstractCraftingMenu;
import net.minecraft.world.inventory.RecipeBookMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.display.RecipeDisplayId;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;
import org.jspecify.annotations.Nullable;

/**
 * The parts catalogue of one open screen: the catalogue and the recipe console round the inventory image, in the colony terminals' CRT
 * look (#846). It draws over the screen after the screen has drawn, reads keys and clicks before the screen does
 * and takes the ones that are its own, so a click on a panel never reaches the inventory behind it.
 *
 * <p>It is not a widget: the screen it stands beside is a vanilla one, so the text the panels draw is the screen's own for the layout
 * tests ({@code DrawnText}), and the bounds they check are the panels'.
 */
public final class CataloguePanel {
    /** Item icons are 16 pixels. */
    private static final int ICON = 16;
    /** The most the craftable switch counts out; more reads "99+". */
    private static final int MOST_COUNTED = 99;
    /** The dots a text loses its tail to when it is too long for its line. */
    private static final String ELLIPSIS = "..";
    /** How long a note about the fill switch stays over the recipe console. */
    private static final int STATUS_TICKS = 80;
    /** How many ticks a fill press is given to change the grid before the server is taken to have refused it. */
    private static final int FILL_WAIT_TICKS = 10;

    /** What is under the mouse in a panel (not in the inventory): the item and the cell it is in. */
    private record Hit(Item item, Rect cell) {}

    private final AbstractRecipeBookScreen<?> screen;
    private final Bench bench;
    private final CatalogueState state;
    private final Font font = Minecraft.getInstance().font;
    private @Nullable CatalogueLayout layout;
    /** The spec sheet that covers the screen, or null. */
    private @Nullable SpecSheet sheet;
    /** Where the sheet stands at the screen's size and what it says there; null while no sheet is open. */
    private @Nullable Placed placed;

    /** The recipe shown as a ghost over the crafting grid, with the slots the player cannot fill marked, or null. */
    private @Nullable Ghost ghost;
    /** Why the fill switch did nothing, drawn over the recipe console for {@link #STATUS_TICKS}, or null. */
    private @Nullable Note fillNote;
    /** The last fill press the server has not yet been seen to answer, or null. */
    private @Nullable Pressed pressed;
    /** What the player holds, grid included, as of the last tick: what the fill switch weighs a recipe against. */
    private StackedItemContents fillContents = new StackedItemContents();

    private int screenWidth;
    private int screenHeight;
    private PartsCatalogue catalogue = PartsCatalogue.of(List.of(), ContextMap.EMPTY);
    private @Nullable List<RecipeCollection> builtFrom;
    private StackedItemContents contents = new StackedItemContents();
    /** What the recipe card shows: the recipe, and the item and mode looked up to get it. */
    private record CardShows(
            @Nullable RecipeDisplayId recipe, @Nullable Item item, CatalogueState.@Nullable Mode mode) {}

    /** A fill press waiting for the server: the recipe asked for, the grid as it was, and the tick it is judged at. */
    private record Pressed(KnownRecipe recipe, List<ItemStack> before, int judgedAt) {}

    /** A note about the fill switch: what it says, what the card showed when it was made (it goes when that changes), and the tick it ends at. */
    private record Note(String message, CardShows shows, int endsAt) {}

    /** A recipe's ghost: where each of its cells goes in the grid and which of those the player lacks. */
    private record Ghost(KnownRecipe recipe, List<RecipeFill.Placed> cells, Set<Integer> missing) {}

    /** What {@link #shownItems} was last worked out from; it is worked out again only when one of these changes. */
    private List<Item> shownCache = List.of();

    private @Nullable PartsCatalogue shownCatalogue;
    private String shownQuery = "";
    private boolean shownFilter;
    private int contentsStamp;
    private int craftableCount;
    /** The items {@link PartsCatalogue#tableOnly} holds for, as of the last tick. */
    private Set<Item> tableOnly = Set.of();

    private int shownStamp = -1;
    private List<Item> favouritesCache = List.of();
    private boolean searchFocused;
    private boolean pressTaken;
    private int ticks;
    private int mouseX = -1;
    private int mouseY = -1;

    /** A panel for {@code screen} over {@code state}, which is the client's own and so carries from one screen to the next. */
    CataloguePanel(AbstractRecipeBookScreen<?> screen, CatalogueState state) {
        this.screen = screen;
        this.state = state;
        this.bench = Bench.of(screen.getMenu());
        refresh();
    }

    /** Lays the panels out for a screen {@code width} by {@code height}. */
    void resize(int width, int height) {
        CatalogueLayout now = CatalogueLayout.of(
                width, height, look(), CrtTuning.current(), font.lineHeight, screen instanceof InventoryScreen);
        layout = now;
        screenWidth = width;
        screenHeight = height;
        planSheet();
        if (searchFocused) {
            focusSearch(layout != null);
        }
    }

    /** Shows the spec sheet of {@code item} over the screen, until a click or Escape puts it away. */
    void showSheet(Item item) {
        sheet = SpecSheets.of(item);
        planSheet();
    }

    /** Puts the sheet away, and lets the next one waiting take its place. */
    private void closeSheet() {
        sheet = null;
        planSheet();
        SpecSheetOpener.drain(Minecraft.getInstance());
    }

    private void planSheet() {
        SpecSheet shown = sheet;
        placed = shown == null
                ? null
                : SpecSheetView.place(shown, screenWidth, screenHeight, look(), CrtTuning.current(), font);
    }

    /** The item whose spec sheet covers the screen, or null. */
    // test-seam: the client test reads which sheet is open
    public @Nullable Item sheetItem() {
        SpecSheet shown = sheet;
        return shown == null ? null : shown.item();
    }

    /** The glass of the open spec sheet, or null when none is open. */
    // test-seam: the client test reads where the sheet's text must lie
    public @Nullable Rect sheetGlass() {
        Placed where = placed;
        return where == null ? null : where.layout().glass();
    }

    /** The Recipes link of the open spec sheet, or null when none is open. */
    // test-seam: the client test clicks the link
    public @Nullable Rect sheetRecipesLink() {
        Placed where = placed;
        return where == null ? null : where.layout().recipes();
    }

    /** The close switch of the open spec sheet, or null when none is open. */
    // test-seam: the client test clicks the switch
    public @Nullable Rect sheetCloseSwitch() {
        Placed where = placed;
        return where == null ? null : where.layout().close();
    }

    private CatalogueLook look() {
        return CatalogueLook.current();
    }

    /** The layout, or null while the screen is too narrow to hold the panels and they are hidden. */
    // test-seam: where the panels are, which the client test and the evidence scenario hover and click
    public @Nullable CatalogueLayout layout() {
        return layout;
    }

    // test-seam: what the catalogue is set to, which the client test reads
    public CatalogueState state() {
        return state;
    }

    /** What the charter knows now, as of the last tick. */
    // test-seam: what the charter knows now, which the client test reads
    public PartsCatalogue catalogue() {
        return catalogue;
    }

    /** Whether the panels are drawn and take input: they have room, and the player has not put them away. */
    // test-seam: the client test reads whether the panels are out
    public boolean panelsShown() {
        return layout != null && !state.hidden();
    }

    /** Puts the panels away, or brings them back: the button's press and the key. The search lets go of the keyboard either way. */
    void toggleHidden() {
        state.toggleHidden();
        dropStale();
        focusSearch(false);
    }

    /** The recipe panel is drawn only while an item is looked up. */
    public boolean showsRecipePanel() {
        return panelsShown() && state.lookup() != null;
    }

    // test-seam: whether the search field has the keyboard, which the client test reads
    public boolean searchFocused() {
        return searchFocused;
    }

    /** SDL sends no text until text input is on, so focus starts it and unfocus stops it, as {@code EditBox} does. */
    private void focusSearch(boolean focused) {
        CatalogueLayout now = layout;
        searchFocused = focused && now != null;
        TextInputManager input = Minecraft.getInstance().textInputManager();
        if (now != null && searchFocused) {
            Rect search = now.shelf().search();
            input.setTextInputArea(search.x(), search.y(), search.right(), search.bottom());
            input.startTextInput(this);
        } else {
            input.stopTextInput(this);
        }
    }

    void tick() {
        ticks++;
        if (fillNote != null && ticks >= fillNote.endsAt()) {
            fillNote = null;
        }
        refresh();
        dropStale();
        refreshGhost();
        checkPressed();
    }

    /** Says so when the server left the grid as it was after a fill press (its inventory was full, say), and the grid does not already hold the recipe. */
    private void checkPressed() {
        Pressed waiting = pressed;
        if (waiting == null) {
            return;
        }
        if (ticks < waiting.judgedAt()) {
            return;
        }
        pressed = null;
        List<ItemStack> now = gridSlots().stream().map(Slot::getItem).toList();
        boolean unchanged = now.size() == waiting.before().size();
        for (int i = 0; unchanged && i < now.size(); i++) {
            unchanged = ItemStack.matches(now.get(i), waiting.before().get(i));
        }
        if (unchanged
                && !gridMakes(waiting.recipe())
                && Objects.equals(idOf(waiting.recipe()), idOf(state.shownRecipe(catalogue)))) {
            note(text("fill.status.refused"));
        }
    }

    /** Whether the grid already holds {@code recipe}: its result is showing, or a furnace's input is one of its ingredients. */
    private boolean gridMakes(KnownRecipe recipe) {
        if (screen.getMenu() instanceof AbstractCraftingMenu crafting) {
            return crafting.getResultSlot()
                    .getItem()
                    .is(recipe.results().getFirst().getItem());
        }
        List<Slot> grid = gridSlots();
        return !grid.isEmpty()
                && recipe.cells().stream()
                        .anyMatch(cell -> cell.stream()
                                .anyMatch(choice -> grid.getFirst().getItem().is(choice.getItem())));
    }

    /** What the card shows now. */
    private CardShows cardShows() {
        CatalogueState.Lookup lookup = state.lookup();
        return new CardShows(
                idOf(state.shownRecipe(catalogue)),
                lookup == null ? null : lookup.item(),
                lookup == null ? null : lookup.mode());
    }

    /** The id of {@code recipe}, which names it across a rebuild of the catalogue (a recipe the player has just been taught): the recipes themselves are new objects then. */
    private static @Nullable RecipeDisplayId idOf(@Nullable KnownRecipe recipe) {
        return recipe == null ? null : recipe.entry().id();
    }

    /** Takes away the ghost and the note that no longer fit what is on the screen: another recipe, panels put away, or a fit that is not the ghost's. */
    private void dropStale() {
        CardShows now = cardShows();
        if (fillNote != null && !now.equals(fillNote.shows())) {
            fillNote = null;
        }
        Ghost shownGhost = ghost;
        if (shownGhost != null
                && (state.hidden()
                        || layout == null
                        || !Objects.equals(idOf(shownGhost.recipe()), now.recipe())
                        || fillFit() != RecipeFill.Fit.GHOST)) {
            ghost = null;
        }
    }

    /** Marks again what the player still lacks of the ghost's recipe, from what they hold now. */
    private void refreshGhost() {
        Ghost shownGhost = ghost;
        if (shownGhost != null) {
            ghost = new Ghost(
                    shownGhost.recipe(), shownGhost.cells(), RecipeFill.missing(shownGhost.cells(), held(gridSlots())));
        }
    }

    /** Rebuilds what the catalogue lists when the recipe book changed, and counts what the player holds. */
    private void refresh() {
        Minecraft client = Minecraft.getInstance();
        LocalPlayer player = client.player;
        if (player == null || client.level == null) {
            return;
        }
        List<RecipeCollection> collections = player.getRecipeBook().getCollections();
        if (collections != builtFrom) {
            builtFrom = collections;
            catalogue = PartsCatalogue.of(player.getRecipeBook(), SlotDisplayContext.fromLevel(client.level));
        }
        contents = new StackedItemContents();
        player.getInventory().fillStackedContents(contents);
        // The fill takes the grid's items back first, as the server does, so they count as held.
        fillContents = new StackedItemContents();
        player.getInventory().fillStackedContents(fillContents);
        screen.getMenu().fillCraftSlotsStackedContents(fillContents);
        contentsStamp++;
        craftableCount = state.craftableCount(catalogue, contents, bench);
        tableOnly = catalogue.tableOnlyItems(contents, bench);
        favouritesCache = CatalogueFavourites.shared().items(player.getUUID());
    }

    // ---------------------------------------------------------------- what is where

    private static UUID player() {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            throw new IllegalStateException("The catalogue is open with no player");
        }
        return player.getUUID();
    }

    /**
     * The items the grid lists, with the search and the filter applied. Worked out again once a tick (what the player holds may have
     * changed), and when the search, the filter or the catalogue changes, not on each call (a frame asks several times).
     */
    private List<Item> shown() {
        String query = state.query();
        boolean filter = state.craftableOnly();
        if (shownCatalogue != catalogue
                || shownStamp != contentsStamp
                || shownFilter != filter
                || !shownQuery.equals(query)) {
            shownCache = state.shown(catalogue, contents, bench);
            shownCatalogue = catalogue;
            shownStamp = contentsStamp;
            shownFilter = filter;
            shownQuery = query;
        }
        return shownCache;
    }

    /** The legend of the craftable switch: its words and the count, which stops at "99+" so the legend stays on its plate. */
    // test-seam: the client test holds the legend of a count over 99 to its plate
    public static String craftableLegend(int count) {
        return count > MOST_COUNTED
                ? text("craftable.many")
                : Component.translatable("deepcharter.catalogue.craftable", count)
                        .getString();
    }

    // test-seam: the count the switch's legend shows
    public int craftableCount() {
        return craftableCount;
    }

    /** Whether {@code item} is made only at a crafting table by what the player holds, which its cell is marked with an icon for. */
    public boolean marksTable(Item item) {
        return tableOnly.contains(item);
    }

    /** The lines the strip that names {@code item} draws over the catalogue's glass. */
    // test-seam: the client test reads the lines the strip must draw
    public List<String> nameLines(Item item) {
        return stripLines(item, layoutOrThrow().catalogueGlass());
    }

    /** The lines the recipe console's title draws, or none while nothing is looked up. */
    // test-seam: the client test reads the lines the title must draw
    public List<String> titleLines() {
        CatalogueLayout now = layout;
        return now == null ? List.of() : titleLines(now.card());
    }

    private CatalogueLayout layoutOrThrow() {
        return Objects.requireNonNull(layout, "the panels are laid out");
    }

    // test-seam: the client test reads what the grid lists, which is the list it draws
    public List<Item> shownItems() {
        return shown();
    }

    /** The cell {@code index} of the item grid or of the favourites row. */
    private Rect gridCell(CatalogueLayout.Shelf shelf, CatalogueLook look, int index) {
        return new Rect(
                shelf.left() + (index % shelf.columns()) * look.gridCell(),
                shelf.gridY() + (index / shelf.columns()) * look.gridCell(),
                look.slotSize(),
                look.slotSize());
    }

    private Rect favouriteCell(CatalogueLayout.Shelf shelf, CatalogueLook look, int index) {
        return new Rect(shelf.left() + index * look.gridCell(), shelf.favouritesY(), look.slotSize(), look.slotSize());
    }

    private List<Item> favourites() {
        return favouritesCache;
    }

    /** The name the recipe console is titled with: what the shown recipe makes, or the item looked up, in capitals. */
    private @Nullable String title() {
        CatalogueState.Lookup lookup = state.lookup();
        if (lookup == null) {
            return null;
        }
        KnownRecipe recipe = state.shownRecipe(catalogue);
        ItemStack named = recipe != null ? recipe.results().getFirst() : new ItemStack(lookup.item());
        return named.getHoverName().getString().toUpperCase(Locale.ROOT);
    }

    private List<String> titleLines(CatalogueLayout.Card card) {
        String title = title();
        return title == null ? List.of() : TextWrap.lines(this::termWidth, title, card.right() - card.left());
    }

    /** The recipe console's content, moved down by each line its title takes beyond the first. */
    private CatalogueLayout.Card card(CatalogueLayout now) {
        int lines = titleLines(now.card()).size();
        return lines <= 1 ? now.card() : now.card().lowered((lines - 1) * font.lineHeight, look().needRow());
    }

    /** The lines of the name strip of {@code item} on {@code glass}: its whole name, on as many lines as the glass needs. */
    private List<String> stripLines(Item item, Rect glass) {
        String name = new ItemStack(item).getHoverName().getString().toUpperCase(Locale.ROOT);
        int inset = look().nameStripPad() + 1;
        return TextWrap.lines(this::termWidth, name, glass.width() - 2 * look().pad() - 2 * inset);
    }

    /** The cell of {@code item} in the recipe panel's grid, result and have/need rows, as laid out for the shown recipe. */
    private List<Hit> recipeHits(CatalogueLayout.Card card, CatalogueLook look, KnownRecipe recipe) {
        List<Hit> hits = new ArrayList<>();
        for (int i = 0; i < recipe.cells().size(); i++) {
            ItemStack stack = cycled(recipe.cells().get(i));
            if (!stack.isEmpty()) {
                hits.add(new Hit(stack.getItem(), recipeCell(card, look, recipe, i)));
            }
        }
        hits.add(new Hit(recipe.results().getFirst().getItem(), resultCell(card, look)));
        List<KnownRecipe.Need> needs = recipe.needs();
        for (int i = 0; i < Math.min(needs.size(), card.needRows()); i++) {
            hits.add(new Hit(
                    cycled(needs.get(i).choices()).getItem(),
                    new Rect(card.left(), card.needTop() + i * look.needRow(), ICON, ICON)));
        }
        return hits;
    }

    private static Rect recipeCell(CatalogueLayout.Card card, CatalogueLook look, KnownRecipe recipe, int index) {
        return new Rect(
                card.left() + (index % recipe.columns()) * look.recipeCell(),
                card.gridY() + (index / recipe.columns()) * look.recipeCell(),
                look.slotSize(),
                look.slotSize());
    }

    private static Rect resultCell(CatalogueLayout.Card card, CatalogueLook look) {
        return new Rect(card.left() + look.resultX(), card.gridY() + look.resultY(), look.slotSize(), look.slotSize());
    }

    /** The item of a cell that takes several (a tag) which is shown now: each in turn, so the player sees what a tag allows. */
    private static ItemStack cycled(List<ItemStack> choices) {
        if (choices.isEmpty()) {
            return ItemStack.EMPTY;
        }
        long step = Util.getMillis() / CatalogueLook.current().cycleMillis();
        return choices.get((int) (step % choices.size()));
    }

    /** What the mouse is over in a panel, or null. */
    private @Nullable Hit hitAt(double x, double y) {
        CatalogueLayout now = layout;
        if (now == null || state.hidden()) {
            return null;
        }
        CatalogueLook look = look();
        CatalogueLayout.Shelf shelf = now.shelf();
        List<Item> shown = shown();
        int perPage = shelf.perPage();
        int first = state.page(CatalogueState.pages(shown.size(), perPage)) * perPage;
        for (int i = 0; i < perPage && first + i < shown.size(); i++) {
            Rect cell = gridCell(shelf, look, i);
            if (cell.contains(x, y)) {
                return new Hit(shown.get(first + i), cell);
            }
        }
        List<Item> favourites = favourites();
        int offset = state.favouriteOffset(favourites.size(), shelf.columns());
        for (int i = 0; i < shelf.columns() && offset + i < favourites.size(); i++) {
            Rect cell = favouriteCell(shelf, look, i);
            if (cell.contains(x, y)) {
                return new Hit(favourites.get(offset + i), cell);
            }
        }
        KnownRecipe recipe = showsRecipePanel() ? state.shownRecipe(catalogue) : null;
        if (recipe != null) {
            for (Hit hit : recipeHits(card(now), look, recipe)) {
                if (hit.cell().contains(x, y)) {
                    return hit;
                }
            }
        }
        return null;
    }

    /** The item under the mouse, in a panel or in the inventory behind them. */
    public @Nullable Item hoveredItem() {
        Hit hit = hitAt(mouseX, mouseY);
        if (hit != null) {
            return hit.item();
        }
        CatalogueLayout now = layout;
        if (now == null || state.hidden()) {
            return null;
        }
        for (Slot slot : screen.getMenu().slots) {
            int slotX = now.inventory().x() + slot.x;
            int slotY = now.inventory().y() + slot.y;
            if (slot.isActive()
                    && slot.hasItem()
                    && mouseX >= slotX - 1
                    && mouseX < slotX + ICON + 1
                    && mouseY >= slotY - 1
                    && mouseY < slotY + ICON + 1) {
                return slot.getItem().getItem();
            }
        }
        return null;
    }

    /** R or U on {@code item} from outside the screen's keys: the quest map's "recipe one click away" calls this through {@code CatalogueScreens}. */
    void lookUp(Item item, CatalogueState.Mode mode) {
        // A recipe asked for is a recipe to show, so the panels that were put away come back for it.
        if (state.hidden()) {
            state.toggleHidden();
        }
        state.look(catalogue, item, mode);
    }

    // ---------------------------------------------------------------- fill and ghost

    /** What the fill switch does with the recipe shown, or null while none is. */
    private RecipeFill.@Nullable Fit fillFit() {
        KnownRecipe recipe = showsRecipePanel() ? state.shownRecipe(catalogue) : null;
        return recipe == null ? null : RecipeFill.fit(recipe, bench, fillContents);
    }

    /** The legend of the fill switch, which says what a press does with the recipe shown, or an empty string while none is. */
    // test-seam: the client test reads what the switch offers
    public String fillLegend() {
        RecipeFill.Fit fit = fillFit();
        return fit == null ? "" : text(fit.legendKey());
    }

    /** Why the last press of the fill switch did nothing, or null. */
    // test-seam: the client test reads the note over the recipe console
    public @Nullable String fillStatus() {
        return fillNote == null ? null : fillNote.message();
    }

    /** The grid slots (indexes of the menu's input slots) the ghost marks as missing, or none when no ghost is shown. */
    // test-seam: the client test reads which slots the ghost marks
    public Set<Integer> ghostMissing() {
        Ghost shown = ghost;
        return shown == null ? Set.of() : shown.missing();
    }

    /** Whether a recipe is shown as a ghost over the grid. */
    // test-seam: the client test reads whether the ghost is up
    public boolean ghostShown() {
        return ghost != null;
    }

    private List<Slot> gridSlots() {
        RecipeBookMenu menu = screen.getMenu();
        if (menu instanceof AbstractCraftingMenu crafting) {
            return crafting.getInputGridSlots();
        }
        // A furnace takes its one ingredient in its first slot.
        return menu.slots.isEmpty() ? List.of() : List.of(menu.slots.getFirst());
    }

    /** The press of the fill switch: the server fills the grid, or the recipe shows as a ghost, or a note says why neither. */
    private void fillClicked(boolean maximum) {
        KnownRecipe recipe = state.shownRecipe(catalogue);
        RecipeFill.Fit fit = fillFit();
        if (recipe == null || fit == null) {
            return;
        }
        switch (fit) {
            case NEEDS_TABLE -> note(text("fill.status.table"));
            case NOT_HERE -> note(text("fill.status.here"));
            case FILL -> {
                // Vanilla's recipe-book placement: the server moves the ingredients, and the grid's items back first.
                ghost = null;
                pressed = new Pressed(
                        recipe,
                        gridSlots().stream().map(slot -> slot.getItem().copy()).toList(),
                        ticks + FILL_WAIT_TICKS);
                MultiPlayerGameMode mode = Minecraft.getInstance().gameMode;
                if (mode != null) {
                    mode.handlePlaceRecipe(
                            screen.getMenu().containerId, recipe.entry().id(), maximum);
                }
            }
            case GHOST -> {
                List<Slot> grid = gridSlots();
                List<RecipeFill.Placed> cells = RecipeFill.place(recipe, bench.width(), bench.height());
                ghost = new Ghost(recipe, cells, RecipeFill.missing(cells, held(grid)));
            }
        }
    }

    /** Shows {@code message} over the recipe console for a while. */
    private void note(String message) {
        fillNote = new Note(message, cardShows(), ticks + STATUS_TICKS);
    }

    /** How many of each item the player holds, the items in the grid included: the server takes the grid back before it places. */
    private Map<Item, Integer> held(List<Slot> grid) {
        Map<Item, Integer> held = new HashMap<>();
        LocalPlayer player = Minecraft.getInstance().player;
        List<ItemStack> stacks = new ArrayList<>();
        if (player != null) {
            stacks.addAll(player.getInventory().getNonEquipmentItems());
        }
        for (Slot slot : grid) {
            stacks.add(slot.getItem());
        }
        for (ItemStack stack : stacks) {
            if (!stack.isEmpty()) {
                held.merge(stack.getItem(), stack.getCount(), Integer::sum);
            }
        }
        return held;
    }

    /** Takes the ghost away when the player works the grid itself: a click on one of its slots or on the result, as vanilla's recipe book does. */
    private void dropGhostOnGridClick(double x, double y) {
        CatalogueLayout now = layout;
        if (ghost == null || now == null) {
            return;
        }
        List<Slot> slots = new ArrayList<>(gridSlots());
        if (screen.getMenu() instanceof AbstractCraftingMenu crafting) {
            slots.add(crafting.getResultSlot());
        }
        for (Slot slot : slots) {
            int slotX = now.inventory().x() + slot.x;
            int slotY = now.inventory().y() + slot.y;
            if (x >= slotX && x < slotX + ICON && y >= slotY && y < slotY + ICON) {
                ghost = null;
                return;
            }
        }
    }

    /** Draws the ghost over the grid: each ingredient faint in its slot, the ones the player lacks tinted red, and the result faint too. */
    private void drawGhost(GuiGraphicsExtractor graphics, CatalogueLayout now, CatalogueLook look) {
        Ghost shown = ghost;
        if (shown == null) {
            return;
        }
        List<Slot> grid = gridSlots();
        graphics.nextStratum();
        for (RecipeFill.Placed cell : shown.cells()) {
            Slot slot = grid.get(cell.slot());
            boolean missing = shown.missing().contains(cell.slot());
            int veil = missing ? look.missingColor() : look.ghostColor();
            int x = now.inventory().x() + slot.x;
            int y = now.inventory().y() + slot.y;
            graphics.fill(x, y, x + ICON, y + ICON, veil);
            graphics.fakeItem(cycled(cell.choices()), x, y);
            graphics.fill(x, y, x + ICON, y + ICON, veil);
        }
        if (screen.getMenu() instanceof AbstractCraftingMenu crafting) {
            Slot result = crafting.getResultSlot();
            int x = now.inventory().x() + result.x;
            int y = now.inventory().y() + result.y;
            graphics.fakeItem(shown.recipe().results().getFirst(), x, y);
            graphics.fill(x, y, x + ICON, y + ICON, look.ghostColor());
        }
    }

    /** The note about the fill switch in a strip over the foot of the recipe glass, in the name strip's look, a name's width at most. */
    private void drawFillStatus(GuiGraphicsExtractor graphics, CatalogueLayout now, CatalogueLook look) {
        Note shown = fillNote;
        if (shown == null) {
            return;
        }
        String message = shown.message();
        CrtTuning crt = CrtTuning.current();
        Rect glass = now.recipeGlass();
        int inset = look.nameStripPad() + 1;
        List<String> lines = TextWrap.lines(this::termWidth, message, glass.width() - 2 * look.pad() - 2 * inset);
        int widest = lines.stream().mapToInt(this::termWidth).max().orElse(0);
        int width = widest + 2 * inset;
        int height = lines.size() * font.lineHeight + 2 * inset;
        int x = glass.x() + (glass.width() - width) / 2;
        int y = glass.bottom() - look.contentBottom() - height;
        graphics.nextStratum();
        graphics.fill(x, y, x + width, y + height, crt.backgroundColor());
        CrtDraw.border(graphics, x, y, x + width, y + height, crt.phosphorColor());
        for (int i = 0; i < lines.size(); i++) {
            CrtDraw.glowText(
                    graphics,
                    font,
                    CrtText.terminal(lines.get(i)),
                    x + inset,
                    y + inset + i * font.lineHeight,
                    crt.phosphorColor());
        }
    }

    // ---------------------------------------------------------------- input

    /** A key, before the screen sees it. Returns whether the catalogue took it. */
    boolean keyPressed(KeyEvent event) {
        if (layout == null) {
            return false;
        }
        if (sheet != null) {
            // The sheet covers everything, so no key may reach the slot under the mouse (a digit would swap it into the
            // hotbar, Q drop it). The key that closes the inventory is the screen's and ends it all; any other puts the
            // sheet away and is the sheet's.
            if (Minecraft.getInstance().options.keyInventory.matches(event)) {
                return false;
            }
            closeSheet();
            return true;
        }
        if (searchFocused) {
            return searchKey(event);
        }
        if (CatalogueKeys.TOGGLE.matches(event)) {
            toggleHidden();
            return true;
        }
        if (state.hidden()) {
            return false;
        }
        // Ctrl+F, or Cmd+F on a Mac (hasControlDownWithQuirk is the shortcut key of the platform).
        if (event.hasControlDownWithQuirk() && event.key() == InputConstants.KEY_F) {
            focusSearch(true);
            return true;
        }
        if (event.isEscape() && showsRecipePanel()) {
            // Before the screen sees it: Escape puts the recipe panel away first and closes the inventory the next
            // time.
            state.clear();
            return true;
        }
        Item item = hoveredItem();
        if (item == null) {
            return false;
        }
        if (CatalogueKeys.RECIPES.matches(event)) {
            lookUp(item, CatalogueState.Mode.RECIPES);
            return true;
        }
        if (CatalogueKeys.USES.matches(event)) {
            lookUp(item, CatalogueState.Mode.USES);
            return true;
        }
        if (CatalogueKeys.FAVOURITE.matches(event)) {
            CatalogueFavourites.shared().toggle(player(), item);
            favouritesCache = CatalogueFavourites.shared().items(player());
            return true;
        }
        return false;
    }

    private boolean searchKey(KeyEvent event) {
        String query = state.query();
        if (event.isEscape() || event.isConfirmation()) {
            focusSearch(false);
        } else if (event.key() == InputConstants.KEY_BACKSPACE) {
            if (event.hasControlDownWithQuirk()) {
                state.setQuery("");
            } else if (!query.isEmpty()) {
                state.setQuery(query.substring(0, query.length() - 1));
            }
        } else if (event.isPaste()) {
            String clipboard = Minecraft.getInstance().keyboardHandler.getClipboard();
            state.setQuery(clip(query + clipboard.replaceAll("\\p{Cntrl}", "")));
        }
        // While the search has the keyboard the screen gets none of it: E would close the inventory and a digit swap a
        // hotbar slot.
        return true;
    }

    private static String clip(String query) {
        return query.length() <= CatalogueLook.current().maxQueryLength()
                ? query
                : query.substring(0, CatalogueLook.current().maxQueryLength());
    }

    boolean charTyped(CharacterEvent event) {
        if (!panelsShown() || !searchFocused) {
            return false;
        }
        if (event.isAllowedChatCharacter()) {
            state.setQuery(clip(state.query() + event.codepointAsString()));
        }
        return true;
    }

    /** A click, before the screen sees it. Returns whether the catalogue took it. */
    boolean mouseClicked(MouseButtonEvent event) {
        CatalogueLayout now = layout;
        if (now == null) {
            return false;
        }
        if (sheet != null) {
            // Every click is the sheet's: the inventory under it never sees the press or its release. The Recipes link
            // shows the item's recipes, the close switch and a click off the console put the sheet away, and a click on
            // the console itself does nothing, so a stray one does not lose the sheet.
            pressTaken = true;
            Placed where = placed;
            SpecSheetView.Click click = where == null
                    ? SpecSheetView.Click.CLOSE
                    : SpecSheetView.clickAt(where.layout(), event.x(), event.y());
            if (click == SpecSheetView.Click.RECIPES) {
                lookUp(Objects.requireNonNull(sheetItem(), "<a sheet is open>"), CatalogueState.Mode.RECIPES);
            }
            if (click != SpecSheetView.Click.NONE) {
                closeSheet();
            }
            return true;
        }
        dropGhostOnGridClick(event.x(), event.y());
        if (state.hidden()) {
            return false;
        }
        double x = event.x();
        double y = event.y();
        CatalogueLayout.Shelf shelf = now.shelf();
        boolean onShelf = now.catalogue().contains(x, y);
        boolean onCard = showsRecipePanel() && now.recipe().contains(x, y);
        focusSearch(onShelf && shelf.search().contains(x, y));
        if (!onShelf && !onCard) {
            return false;
        }
        pressTaken = true;
        if (event.button() != InputConstants.MOUSE_BUTTON_LEFT && event.button() != InputConstants.MOUSE_BUTTON_RIGHT) {
            // A middle or side button on a panel is taken and does nothing, so it never reaches the inventory behind.
            return true;
        }
        boolean right = event.button() == InputConstants.MOUSE_BUTTON_RIGHT;
        Hit hit = hitAt(x, y);
        if (hit != null) {
            lookUp(hit.item(), right ? CatalogueState.Mode.USES : CatalogueState.Mode.RECIPES);
        } else if (onShelf) {
            shelfClick(shelf, x, y);
        } else {
            CatalogueLayout.Card card = now.card();
            CatalogueState.Lookup lookup = state.lookup();
            Rect title = new Rect(
                    card.left(),
                    card.titleY(),
                    card.right() - card.left(),
                    titleLines(card).size() * font.lineHeight);
            if (lookup != null && card.spec().contains(x, y)) {
                SpecSheetHook.open(lookup.item());
            } else if (lookup != null && card.fill().contains(x, y)) {
                fillClicked(event.hasShiftDown());
            } else if (title.contains(x, y)) {
                state.clear();
            }
        }
        return true;
    }

    private void shelfClick(CatalogueLayout.Shelf shelf, double x, double y) {
        int pages = CatalogueState.pages(shown().size(), shelf.perPage());
        if (shelf.craftable().contains(x, y)) {
            state.toggleCraftableOnly();
        } else if (shelf.previous().contains(x, y)) {
            state.turn(-1, pages);
        } else if (shelf.next().contains(x, y)) {
            state.turn(1, pages);
        }
    }

    /** The release of a press the catalogue took: the screen never saw the press, so it must not see the release either. */
    boolean mouseReleased() {
        boolean taken = pressTaken;
        pressTaken = false;
        return taken;
    }

    /** The wheel over the catalogue turns its pages, over the favourites row scrolls it and over the recipe panel steps through the recipes. */
    boolean mouseScrolled(double x, double y, double vertical) {
        CatalogueLayout now = layout;
        if (now == null || vertical == 0) {
            return false;
        }
        if (sheet != null) {
            return true;
        }
        if (state.hidden()) {
            return false;
        }
        int direction = vertical > 0 ? -1 : 1;
        if (now.catalogue().contains(x, y)) {
            CatalogueLayout.Shelf shelf = now.shelf();
            if (shelf.favourites().contains(x, y)) {
                state.scrollFavourites(direction);
            } else {
                state.turn(direction, CatalogueState.pages(shown().size(), shelf.perPage()));
            }
            return true;
        }
        if (showsRecipePanel() && now.recipe().contains(x, y)) {
            state.step(catalogue, direction);
            return true;
        }
        return false;
    }

    // ---------------------------------------------------------------- drawing

    /** Draws both panels over the screen, with the mouse at ({@code mouseX}, {@code mouseY}). */
    void extract(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        this.mouseX = mouseX;
        this.mouseY = mouseY;
        CatalogueLayout now = layout;
        if (now == null) {
            return;
        }
        CatalogueLook look = look();
        // Nothing under a sheet is lit or named: the sheet covers the screen.
        SpecSheet shownSheet = sheet;
        Placed sheetAt = placed;
        Hit hit = shownSheet == null ? hitAt(mouseX, mouseY) : null;
        dropStale();
        if (shownSheet == null) {
            drawGhost(graphics, now, look);
        }
        if (!state.hidden()) {
            Rect effects = now.effects();
            if (effects != null) {
                CatalogueEffects.extract(
                        graphics, effects, now.inventory().x() + CatalogueLayout.IMAGE_WIDTH / 2, mouseX, mouseY);
            }
            graphics.nextStratum();
            drawShelf(graphics, now, look, hit);
            if (showsRecipePanel()) {
                drawCard(graphics, now, look, hit);
            }
            if (hit != null) {
                drawNameStrip(graphics, now, look, hit);
            }
            if (showsRecipePanel()) {
                drawFillStatus(graphics, now, look);
            }
        }
        if (shownSheet != null && sheetAt != null) {
            SpecSheetView.draw(graphics, font, sheetAt.layout(), shownSheet, sheetAt.plan(), mouseX, mouseY, true);
            // The screen's own tooltip step comes after this one and would draw the name of the slot under the sheet
            // over it.
            HoveredSlot.clear(screen);
        }
    }

    /**
     * The name of the hovered item in a strip of its own, in the terminal font on the phosphor black with a phosphor border, where the
     * game's purple tooltip would clash with the panels. Over the cell, or under it when there is no room above, and kept inside the glass
     * of the panel the cell is on, off its bezel. A name wider than the glass goes on a second line, and a third: it is never cut.
     */
    private void drawNameStrip(GuiGraphicsExtractor graphics, CatalogueLayout now, CatalogueLook look, Hit hit) {
        CrtTuning crt = CrtTuning.current();
        Rect glass = now.catalogueGlass().contains(hit.cell().x(), hit.cell().y())
                ? now.catalogueGlass()
                : now.recipeGlass();
        List<String> lines = stripLines(hit.item(), glass);
        int inset = look.nameStripPad() + 1;
        int widest = lines.stream().mapToInt(this::termWidth).max().orElse(0);
        int width = widest + 2 * inset;
        int height = lines.size() * font.lineHeight + 2 * inset;
        int left = glass.x() + look.pad();
        int x = Math.clamp(hit.cell().x(), left, Math.max(left, glass.right() - look.pad() - width));
        int y = hit.cell().y() - look.nameStripGap() - height;
        if (y < glass.y()) {
            y = hit.cell().bottom() + look.nameStripGap();
        }
        graphics.nextStratum();
        graphics.fill(x, y, x + width, y + height, crt.backgroundColor());
        CrtDraw.border(graphics, x, y, x + width, y + height, crt.phosphorColor());
        for (int i = 0; i < lines.size(); i++) {
            CrtDraw.glowText(
                    graphics,
                    font,
                    CrtText.terminal(lines.get(i)),
                    x + inset,
                    y + inset + i * font.lineHeight,
                    crt.phosphorColor());
        }
    }

    private void drawShelf(GuiGraphicsExtractor graphics, CatalogueLayout now, CatalogueLook look, @Nullable Hit hit) {
        CrtTuning crt = CrtTuning.current();
        PanelLook panel = PanelLook.current();
        CatalogueLayout.Shelf shelf = now.shelf();
        CatalogueHardware.body(graphics, look, now.catalogue(), now.catalogueGlass(), shelf.vents());
        graphics.nextStratum();

        CrtDraw.header(graphics, font, CrtText.terminal(text("title")), shelf.left(), shelf.titleY(), shelf.right());

        Rect search = shelf.search();
        graphics.fill(search.x(), search.y(), search.right(), search.bottom(), crt.backgroundColor());
        CrtDraw.border(
                graphics,
                search.x(),
                search.y(),
                search.right(),
                search.bottom(),
                searchFocused ? crt.phosphorColor() : crt.dimColor());
        drawSearch(graphics, search, look, crt, panel);

        List<Item> shown = shown();
        int perPage = shelf.perPage();
        int pages = CatalogueState.pages(shown.size(), perPage);
        int first = state.page(pages) * perPage;
        for (int i = 0; i < perPage && first + i < shown.size(); i++) {
            Rect cell = gridCell(shelf, look, i);
            drawCell(graphics, cell, look, shown.get(first + i), hit);
        }
        if (shown.isEmpty()) {
            dim(
                    graphics,
                    fit(text("empty"), shelf.columns() * look.gridCell()),
                    shelf.left(),
                    shelf.gridY() + 2,
                    panel);
        }

        dim(graphics, text("favourites"), shelf.left(), shelf.favouritesLabelY(), panel);
        List<Item> favourites = favourites();
        int offset = state.favouriteOffset(favourites.size(), shelf.columns());
        for (int i = 0; i < shelf.columns() && offset + i < favourites.size(); i++) {
            drawCell(graphics, favouriteCell(shelf, look, i), look, favourites.get(offset + i), hit);
        }

        graphics.nextStratum();
        CatalogueHardware.glassFront(graphics, look, panel, now.catalogueGlass());
        graphics.nextStratum();

        // The controls are on the deck under the glass, over the casing.
        Rect craftable = shelf.craftable();
        CatalogueHardware.legendSwitch(
                graphics,
                font,
                look,
                panel,
                crt,
                craftable,
                craftableLegend(craftableCount),
                state.craftableOnly(),
                true,
                craftable.contains(mouseX, mouseY));
        int page = state.page(pages);
        CatalogueHardware.knob(
                graphics,
                font,
                look,
                panel,
                crt,
                shelf.previous(),
                "<",
                page > 0,
                shelf.previous().contains(mouseX, mouseY));
        CatalogueHardware.knob(
                graphics,
                font,
                look,
                panel,
                crt,
                shelf.next(),
                ">",
                page < pages - 1,
                shelf.next().contains(mouseX, mouseY));
        CatalogueHardware.counter(graphics, font, look, panel, crt, shelf.counter(), (page + 1) + "/" + pages);
    }

    private void drawSearch(
            GuiGraphicsExtractor graphics, Rect search, CatalogueLook look, CrtTuning crt, PanelLook panel) {
        int textX = search.x() + look.searchPad();
        int textY = search.y() + look.searchPad();
        int caretWidth = termWidth("_");
        int room = search.width() - 2 * look.searchPad() - caretWidth;
        String query = state.query();
        boolean caretOn = !searchFocused || (ticks / crt.cursorBlinkTicks()) % 2 == 0;
        if (query.isEmpty() && !searchFocused) {
            String hint = fit(text("search"), room);
            dim(graphics, hint, textX, textY, panel);
            textX += termWidth(hint + " ");
        } else {
            // Shown in capitals like the rest of the terminal; the search itself ignores case.
            String tail = query.toUpperCase(Locale.ROOT);
            while (termWidth(tail) > room && !tail.isEmpty()) {
                tail = tail.substring(1);
            }
            CrtDraw.glowText(graphics, font, CrtText.terminal(tail), textX, textY, crt.phosphorColor());
            textX += termWidth(tail);
        }
        if (caretOn) {
            // A solid bar at least as thick as the scanline spacing, so a scanline over it never turns it into dashes
            // ("=").
            int baseline = textY + font.lineHeight - look.caretHeight();
            graphics.fill(textX, baseline, textX + caretWidth, baseline + look.caretHeight(), crt.phosphorColor());
        }
    }

    private void drawCell(GuiGraphicsExtractor graphics, Rect cell, CatalogueLook look, Item item, @Nullable Hit hit) {
        CrtTuning crt = CrtTuning.current();
        CatalogueState.Lookup lookup = state.lookup();
        boolean lit = (hit != null && hit.cell().equals(cell)) || (lookup != null && lookup.item() == item);
        slot(graphics, cell, lit, crt);
        graphics.item(new ItemStack(item), cell.x() + look.iconInset(), cell.y() + look.iconInset());
        if (marksTable(item)) {
            tableIcon(graphics, look, cell.right() - look.tableIconSize(), cell.bottom() - look.tableIconSize());
        }
    }

    /** A crafting table, small, at ({@code x}, {@code y}): what marks an item or a recipe that only a crafting table makes. */
    private static void tableIcon(GuiGraphicsExtractor graphics, CatalogueLook look, int x, int y) {
        graphics.pose().pushMatrix();
        graphics.pose().translate(x, y);
        float scale = look.tableIconSize() / (float) ICON;
        graphics.pose().scale(scale, scale);
        graphics.item(new ItemStack(Items.CRAFTING_TABLE), 0, 0);
        graphics.pose().popMatrix();
    }

    private static void slot(GuiGraphicsExtractor graphics, Rect cell, boolean lit, CrtTuning crt) {
        if (lit) {
            graphics.fill(cell.x(), cell.y(), cell.right(), cell.bottom(), crt.hoverFillColor());
        }
        CrtDraw.border(
                graphics, cell.x(), cell.y(), cell.right(), cell.bottom(), lit ? crt.phosphorColor() : crt.dimColor());
    }

    private void drawCard(GuiGraphicsExtractor graphics, CatalogueLayout now, CatalogueLook look, @Nullable Hit hit) {
        CrtTuning crt = CrtTuning.current();
        PanelLook panel = PanelLook.current();
        CatalogueLayout.Card card = card(now);
        CatalogueState.Lookup lookup = state.lookup();
        if (lookup == null) {
            return;
        }
        KnownRecipe recipe = state.shownRecipe(catalogue);
        int width = card.right() - card.left();
        graphics.nextStratum();
        CatalogueHardware.body(graphics, look, now.recipe(), now.recipeGlass(), List.of());
        graphics.nextStratum();

        CatalogueHardware.title(graphics, font, titleLines(card), card.left(), card.titleY(), card.right());
        if (recipe == null) {
            String none = text(lookup.mode() == CatalogueState.Mode.RECIPES ? "none.recipes" : "none.uses");
            dim(graphics, fit(none, width), card.left(), card.stationY(), panel);
        } else {
            dim(graphics, fit(recipe.station(), width), card.left(), card.stationY(), panel);
            drawRecipe(graphics, card, look, recipe, hit, crt, panel);
        }

        graphics.nextStratum();
        CatalogueHardware.glassFront(graphics, look, panel, now.recipeGlass());
        graphics.nextStratum();
        Rect spec = card.spec();
        CatalogueHardware.legendSwitch(
                graphics,
                font,
                look,
                panel,
                crt,
                spec,
                text("spec"),
                false,
                recipe != null,
                recipe != null && spec.contains(mouseX, mouseY));
        RecipeFill.Fit fit = fillFit();
        Rect fill = card.fill();
        CatalogueHardware.legendSwitch(
                graphics,
                font,
                look,
                panel,
                crt,
                fill,
                fillLegend(),
                fit == RecipeFill.Fit.FILL,
                fit == RecipeFill.Fit.FILL || fit == RecipeFill.Fit.GHOST,
                recipe != null && fill.contains(mouseX, mouseY));
    }

    private void drawRecipe(
            GuiGraphicsExtractor graphics,
            CatalogueLayout.Card card,
            CatalogueLook look,
            KnownRecipe recipe,
            @Nullable Hit hit,
            CrtTuning crt,
            PanelLook panel) {
        for (int i = 0; i < recipe.cells().size(); i++) {
            Rect cell = recipeCell(card, look, recipe, i);
            boolean lit = hit != null && hit.cell().equals(cell);
            slot(graphics, cell, lit, crt);
            ItemStack stack = cycled(recipe.cells().get(i));
            if (!stack.isEmpty()) {
                graphics.item(stack, cell.x() + look.iconInset(), cell.y() + look.iconInset());
            }
        }
        glow(graphics, "->", card.left() + look.arrowX(), card.gridY() + look.arrowY(), crt);
        Rect result = resultCell(card, look);
        slot(graphics, result, true, crt);
        if (recipe.needsTable()) {
            // Over the result: made only in the crafting table's 3 x 3 grid.
            tableIcon(
                    graphics,
                    look,
                    result.x() + (result.width() - look.tableIconSize()) / 2,
                    result.y() - look.tableIconSize() - 1);
        }
        ItemStack made = recipe.results().getFirst();
        graphics.item(made, result.x() + look.iconInset(), result.y() + look.iconInset());
        graphics.itemDecorations(font, made, result.x() + look.iconInset(), result.y() + look.iconInset());

        List<KnownRecipe> found = state.found(catalogue);
        CatalogueState.Lookup lookup = state.lookup();
        if (found.size() > 1 && lookup != null) {
            String key = lookup.mode() == CatalogueState.Mode.RECIPES ? "counter.recipes" : "counter.uses";
            String counter = Component.translatable("deepcharter.catalogue." + key, lookup.index() + 1, found.size())
                    .getString();
            dim(graphics, counter, card.right() - termWidth(counter), card.gridY() + look.counterY(), panel);
        }

        Inventory inventory = Minecraft.getInstance().player == null
                ? null
                : Minecraft.getInstance().player.getInventory();
        List<KnownRecipe.Need> needs = recipe.needs();
        int rows = Math.min(needs.size(), card.needRows());
        for (int i = 0; i < rows; i++) {
            KnownRecipe.Need need = needs.get(i);
            int y = card.needTop() + i * look.needRow();
            graphics.item(cycled(need.choices()), card.left(), y);
            int have = inventory == null ? 0 : held(inventory, need);
            String figures = have + "/" + need.count();
            int x = card.left() + look.needTextX();
            if (have < need.count()) {
                CrtDraw.glowText(
                        graphics, font, CrtText.terminal(figures), x, y + look.needTextY(), crt.phosphorColor());
            } else {
                dim(graphics, figures, x, y + look.needTextY(), panel);
            }
        }
        if (needs.size() > rows && rows > 0) {
            int y = card.needTop() + rows * look.needRow() - look.needRow();
            String more = "+" + (needs.size() - rows);
            dim(graphics, more, card.right() - termWidth(more), y + look.needTextY(), panel);
        }
    }

    /** How many of what {@code need} takes the player holds, any of a tag counted. */
    private static int held(Inventory inventory, KnownRecipe.Need need) {
        int have = 0;
        for (ItemStack stack : inventory.getNonEquipmentItems()) {
            for (ItemStack choice : need.choices()) {
                if (stack.is(choice.getItem())) {
                    have += stack.getCount();
                    break;
                }
            }
        }
        return have;
    }

    private void dim(GuiGraphicsExtractor graphics, String text, int x, int y, PanelLook panel) {
        graphics.text(font, CrtText.terminal(text), x, y, panel.dimTextColor(), false);
    }

    private void glow(GuiGraphicsExtractor graphics, String text, int x, int y, CrtTuning crt) {
        CrtDraw.glowText(graphics, font, CrtText.terminal(text), x, y, crt.phosphorColor());
    }

    private int termWidth(String text) {
        return font.width(CrtText.terminal(text));
    }

    /** {@code text} cut to {@code width} with {@link #ELLIPSIS} in place of its tail when it is wider. */
    private String fit(String text, int width) {
        if (termWidth(text) <= width) {
            return text;
        }
        String cut = text;
        while (!cut.isEmpty() && termWidth(cut + ELLIPSIS) > width) {
            cut = cut.substring(0, cut.length() - 1);
        }
        return cut.stripTrailing() + ELLIPSIS;
    }

    private static String text(String key) {
        return Component.translatable("deepcharter.catalogue." + key).getString();
    }
}
