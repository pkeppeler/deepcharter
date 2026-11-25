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
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractRecipeBookScreen;
import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;
import net.minecraft.util.context.ContextMap;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.StackedItemContents;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
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

    private int screenWidth;
    private int screenHeight;
    private PartsCatalogue catalogue = PartsCatalogue.of(List.of(), ContextMap.EMPTY);
    private @Nullable List<RecipeCollection> builtFrom;
    private StackedItemContents contents = new StackedItemContents();
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
        CatalogueLayout now = CatalogueLayout.of(width, height, look(), CrtTuning.current(), font.lineHeight);
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

    /** The recipe panel is drawn only while an item is looked up. */
    public boolean showsRecipePanel() {
        return layout != null && state.lookup() != null;
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
        refresh();
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
        if (now == null) {
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
        if (now == null) {
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
        state.look(catalogue, item, mode);
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
        if (layout == null || !searchFocused) {
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
            // Any click puts the sheet away, and is the sheet's: the inventory under it never sees the press or its
            // release.
            closeSheet();
            pressTaken = true;
            return true;
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
        graphics.nextStratum();
        drawShelf(graphics, now, look, hit);
        if (showsRecipePanel()) {
            drawCard(graphics, now, look, hit);
        }
        if (hit != null) {
            drawNameStrip(graphics, now, look, hit);
        }
        if (shownSheet != null && sheetAt != null) {
            SpecSheetView.draw(graphics, font, sheetAt.layout(), shownSheet, sheetAt.plan(), mouseX, mouseY);
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
        CatalogueHardware.body(graphics, look, now.recipe(), now.recipeGlass(), card.vents());
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
