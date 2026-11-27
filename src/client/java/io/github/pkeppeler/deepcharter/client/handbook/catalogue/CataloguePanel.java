package io.github.pkeppeler.deepcharter.client.handbook.catalogue;

import static io.github.pkeppeler.deepcharter.client.handbook.catalogue.CatalogueHardware.ICON;

import com.google.common.base.Splitter;
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
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractRecipeBookScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.StackedItemContents;
import net.minecraft.world.inventory.AbstractCraftingMenu;
import net.minecraft.world.inventory.RecipeBookMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.display.RecipeDisplayId;
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
    /** The most the craftable switch counts out; more reads "99+". */
    private static final int MOST_COUNTED = 99;
    /**
     * Where the favourites hint shows the recipe console's star, which is a sprite and not a letter: a private-use character, which no
     * translation holds and the terminal font never draws.
     */
    private static final String STAR_MARK = "\uE000";
    /** How long a note about the fill switch stays over the recipe console, and the note of an item with nothing known by a slot. */
    static final int STATUS_TICKS = 80;
    /** How many ticks a fill press is given to change the grid before the server is taken to have refused it. */
    private static final int FILL_WAIT_TICKS = 10;

    private final AbstractRecipeBookScreen<?> screen;
    private final Bench bench;
    private final CatalogueState state;
    private final Font font = Minecraft.getInstance().font;
    private final RecipeConsole console;
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
        this.console = new RecipeConsole(state, font);
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
        return console.catalogue();
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
                && Objects.equals(idOf(waiting.recipe()), idOf(state.shownRecipe(catalogue())))) {
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
                idOf(state.shownRecipe(catalogue())),
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
        console.refresh();
        Minecraft client = Minecraft.getInstance();
        LocalPlayer player = client.player;
        if (player == null || client.level == null) {
            return;
        }
        PartsCatalogue catalogue = catalogue();
        contents = new StackedItemContents();
        player.getInventory().fillStackedContents(contents);
        // The fill takes the grid's items back first, as the server does, so they count as held.
        fillContents = new StackedItemContents();
        player.getInventory().fillStackedContents(fillContents);
        screen.getMenu().fillCraftSlotsStackedContents(fillContents);
        contentsStamp++;
        craftableCount = state.craftableCount(catalogue, contents, bench);
        tableOnly = catalogue.tableOnlyItems(contents, bench);
    }

    // ---------------------------------------------------------------- what is where

    /**
     * The items the grid lists, with the search and the filter applied. Worked out again once a tick (what the player holds may have
     * changed), and when the search, the filter or the catalogue changes, not on each call (a frame asks several times).
     */
    private List<Item> shown() {
        String query = state.query();
        boolean filter = state.craftableOnly();
        if (shownCatalogue != catalogue()
                || shownStamp != contentsStamp
                || shownFilter != filter
                || !shownQuery.equals(query)) {
            shownCache = state.shown(catalogue(), contents, bench);
            shownCatalogue = catalogue();
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
        return RecipeConsole.stripLines(font, item, layoutOrThrow().catalogueGlass());
    }

    /** The lines the recipe console's title draws, or none while nothing is looked up. */
    // test-seam: the client test reads the lines the title must draw
    public List<String> titleLines() {
        CatalogueLayout now = layout;
        return now == null ? List.of() : console.titleLines(now.card());
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

    /** What the mouse is over in a panel, or null. */
    private RecipeConsole.@Nullable Hit hitAt(double x, double y) {
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
                return new RecipeConsole.Hit(shown.get(first + i), cell);
            }
        }
        List<Item> favourites = console.favourites();
        int offset = state.favouriteOffset(favourites.size(), shelf.columns());
        for (int i = 0; i < shelf.columns() && offset + i < favourites.size(); i++) {
            Rect cell = favouriteCell(shelf, look, i);
            if (cell.contains(x, y)) {
                return new RecipeConsole.Hit(favourites.get(offset + i), cell);
            }
        }
        return showsRecipePanel() ? console.hitAt(now.card(), x, y) : null;
    }

    /** The item under the mouse, in a panel or in the inventory behind them. */
    public @Nullable Item hoveredItem() {
        RecipeConsole.Hit hit = hitAt(mouseX, mouseY);
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
        state.look(catalogue(), item, mode);
    }

    // ---------------------------------------------------------------- fill and ghost

    /** What the fill switch does with the recipe shown, or null while none is. */
    private RecipeFill.@Nullable Fit fillFit() {
        KnownRecipe recipe = showsRecipePanel() ? state.shownRecipe(catalogue()) : null;
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
        KnownRecipe recipe = state.shownRecipe(catalogue());
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
            graphics.fakeItem(RecipeConsole.cycled(cell.choices()), x, y);
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
        Rect glass = now.recipeGlass();
        int inset = look.nameStripPad() + 1;
        List<String> lines =
                TextWrap.lines(this::termWidth, shown.message(), glass.width() - 2 * look.pad() - 2 * inset);
        int width = lines.stream().mapToInt(this::termWidth).max().orElse(0) + 2 * inset;
        int height = lines.size() * font.lineHeight + 2 * inset;
        CatalogueHardware.strip(
                graphics,
                font,
                lines,
                glass.x() + (glass.width() - width) / 2,
                glass.bottom() - look.contentBottom() - height,
                inset);
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
            // Before the screen sees it: Escape puts the recipe panel away first and closes the inventory the
            // next time.
            state.clear();
            return true;
        }
        if (CatalogueKeys.BACK.matches(event) && showsRecipePanel()) {
            state.back();
            return true;
        }
        if (CatalogueKeys.MATERIALS.matches(event) && showsRecipePanel()) {
            state.toggleMaterials(catalogue());
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
            console.toggleFavourite(item);
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
        RecipeConsole.Hit hit = hitAt(x, y);
        CatalogueState.Lookup lookup = state.lookup();
        if (hit != null) {
            lookUp(hit.item(), right ? CatalogueState.Mode.USES : CatalogueState.Mode.RECIPES);
        } else if (onShelf) {
            shelfClick(shelf, x, y);
        } else if (lookup != null) {
            switch (console.controlAt(now.card(), x, y)) {
                case BACK -> state.back();
                case STAR -> console.toggleStar();
                case SPEC -> SpecSheetHook.open(lookup.item());
                case FILL -> fillClicked(event.hasShiftDown());
                case TITLE -> state.clear();
                case NONE -> {}
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

    /** The wheel over the catalogue turns its pages, over the favourites row scrolls it and over the recipe panel steps through the recipes or scrolls the total materials. */
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
            console.scroll(direction);
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
        RecipeConsole.Hit hit = shownSheet == null ? hitAt(mouseX, mouseY) : null;
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
                drawCard(graphics, now, hit);
            }
            if (hit != null) {
                RecipeConsole.nameStrip(
                        graphics,
                        font,
                        now.catalogueGlass().contains(hit.cell().x(), hit.cell().y())
                                ? now.catalogueGlass()
                                : now.recipeGlass(),
                        hit);
            }
            if (showsRecipePanel()) {
                drawFillStatus(graphics, now, look);
            }
        }
        if (shownSheet != null && sheetAt != null) {
            SpecSheetView.draw(graphics, font, sheetAt.layout(), shownSheet, sheetAt.plan(), mouseX, mouseY, true);
            // The screen's own tooltip step comes after this one and would draw the name of the slot under the sheet
            // over it. Public through deepcharter.classtweaker.
            screen.hoveredSlot = null;
        }
    }

    private void drawShelf(
            GuiGraphicsExtractor graphics, CatalogueLayout now, CatalogueLook look, RecipeConsole.@Nullable Hit hit) {
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
        drawSearch(graphics, search, look, crt);

        List<Item> shown = shown();
        int perPage = shelf.perPage();
        int pages = CatalogueState.pages(shown.size(), perPage);
        int first = state.page(pages) * perPage;
        for (int i = 0; i < perPage && first + i < shown.size(); i++) {
            Rect cell = gridCell(shelf, look, i);
            drawCell(graphics, cell, look, shown.get(first + i), hit);
        }
        if (shown.isEmpty()) {
            CatalogueHardware.dim(
                    graphics,
                    font,
                    fit(text("empty"), shelf.columns() * look.gridCell()),
                    shelf.left(),
                    shelf.gridY() + 2);
        }

        CatalogueHardware.dim(graphics, font, text("favourites"), shelf.left(), shelf.favouritesLabelY());
        List<Item> favourites = console.favourites();
        int offset = state.favouriteOffset(favourites.size(), shelf.columns());
        for (int i = 0; i < shelf.columns() && offset + i < favourites.size(); i++) {
            drawCell(graphics, favouriteCell(shelf, look, i), look, favourites.get(offset + i), hit);
        }
        if (favourites.isEmpty()) {
            drawFavouritesHint(graphics, shelf);
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

    /**
     * What the empty favourites row says instead: the favourite key, as bound now, and the recipe console's hollow star, drawn in the line
     * where the hint holds {@link #STAR_MARK}.
     */
    private void drawFavouritesHint(GuiGraphicsExtractor graphics, CatalogueLayout.Shelf shelf) {
        String key =
                CatalogueKeys.FAVOURITE.getTranslatedKeyMessage().getString().toUpperCase(Locale.ROOT);
        String hint = Component.translatable("deepcharter.catalogue.favourites.hint", key, STAR_MARK)
                .getString();
        Rect row = shelf.favourites();
        int rows = Math.max(1, (row.bottom() - shelf.favouritesY()) / font.lineHeight);
        List<String> lines = TextWrap.lines(this::hintWidth, hint, row.width());
        for (int i = 0; i < Math.min(rows, lines.size()); i++) {
            // A key bound to a long name can make the hint longer than the row: its last line takes the rest, cut.
            String line = i == rows - 1
                    ? CatalogueHardware.fit(
                            this::hintWidth, String.join(" ", lines.subList(i, lines.size())), row.width())
                    : lines.get(i);
            drawHintLine(graphics, line, shelf.left(), shelf.favouritesY() + i * font.lineHeight);
        }
    }

    /** How wide a line of the favourites hint is drawn: its text, and a line-high star for each {@link #STAR_MARK}. */
    private int hintWidth(String line) {
        int stars = (int) line.chars().filter(c -> c == STAR_MARK.charAt(0)).count();
        return termWidth(line.replace(STAR_MARK, "")) + stars * font.lineHeight;
    }

    /** A line of the favourites hint at ({@code x}, {@code y}): its text dim, and the hollow star, dim too, where it holds the mark. */
    private void drawHintLine(GuiGraphicsExtractor graphics, String line, int x, int y) {
        List<String> parts = Splitter.on(STAR_MARK).splitToList(line);
        int at = x;
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) {
                CatalogueHardware.star(graphics, at, y, font.lineHeight, false, false);
                at += font.lineHeight;
            }
            String part = parts.get(i);
            String words = part.strip();
            if (!words.isEmpty()) {
                CatalogueHardware.dim(graphics, font, words, at + termWidth(part.substring(0, part.indexOf(words))), y);
            }
            at += termWidth(part);
        }
    }

    private void drawSearch(GuiGraphicsExtractor graphics, Rect search, CatalogueLook look, CrtTuning crt) {
        int textX = search.x() + look.searchPad();
        int textY = search.y() + look.searchPad();
        int caretWidth = termWidth("_");
        int room = search.width() - 2 * look.searchPad() - caretWidth;
        String query = state.query();
        boolean caretOn = !searchFocused || (ticks / crt.cursorBlinkTicks()) % 2 == 0;
        if (query.isEmpty() && !searchFocused) {
            String hint = fit(text("search"), room);
            CatalogueHardware.dim(graphics, font, hint, textX, textY);
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
            // A solid bar at least as thick as the scanline spacing, so a scanline over it never turns it into
            // dashes ("=").
            int baseline = textY + font.lineHeight - look.caretHeight();
            graphics.fill(textX, baseline, textX + caretWidth, baseline + look.caretHeight(), crt.phosphorColor());
        }
    }

    private void drawCell(
            GuiGraphicsExtractor graphics, Rect cell, CatalogueLook look, Item item, RecipeConsole.@Nullable Hit hit) {
        CatalogueState.Lookup lookup = state.lookup();
        boolean lit = (hit != null && hit.cell().equals(cell)) || (lookup != null && lookup.item() == item);
        CatalogueHardware.slot(graphics, cell, lit, CrtTuning.current());
        graphics.item(new ItemStack(item), cell.x() + look.iconInset(), cell.y() + look.iconInset());
        if (marksTable(item)) {
            CatalogueHardware.tableIcon(
                    graphics, look, cell.right() - look.tableIconSize(), cell.bottom() - look.tableIconSize());
        }
    }

    private void drawCard(GuiGraphicsExtractor graphics, CatalogueLayout now, RecipeConsole.@Nullable Hit hit) {
        RecipeFill.Fit fit = fillFit();
        console.draw(
                graphics,
                now.recipe(),
                now.recipeGlass(),
                now.card(),
                hit,
                mouseX,
                mouseY,
                true,
                new RecipeConsole.FillSwitch(
                        fillLegend(),
                        fit == RecipeFill.Fit.FILL,
                        fit == RecipeFill.Fit.FILL || fit == RecipeFill.Fit.GHOST));
    }

    private int termWidth(String text) {
        return CatalogueHardware.termWidth(font, text);
    }

    private String fit(String text, int width) {
        return CatalogueHardware.fit(font, text, width);
    }

    private static String text(String key) {
        return RecipeConsole.text(key);
    }
}
