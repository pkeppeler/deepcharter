package io.github.pkeppeler.deepcharter.client.handbook.catalogue;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.client.handbook.catalogue.CatalogueLayout.Rect;
import io.github.pkeppeler.deepcharter.client.theme.CatalogueLook;
import io.github.pkeppeler.deepcharter.client.theme.PanelLook;
import io.github.pkeppeler.deepcharter.client.ui.CrtDraw;
import io.github.pkeppeler.deepcharter.client.ui.CrtText;
import io.github.pkeppeler.deepcharter.client.ui.CrtTuning;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;
import net.minecraft.util.context.ContextMap;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.StackedItemContents;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;
import org.jspecify.annotations.Nullable;

/**
 * The parts catalogue of one open screen: the catalogue to the right of the inventory image and the recipe panel to its left, in the
 * colony terminals' CRT look (#846). It draws over the screen after the screen has drawn, reads keys and clicks before the screen does
 * and takes the ones that are its own, so a click on a panel never reaches the inventory behind it.
 *
 * <p>It is not a widget: the screen it stands beside is a vanilla one, so the text the panels draw is the screen's own for the layout
 * tests ({@code DrawnText}), and the bounds they check are the panels'.
 */
public final class CataloguePanel {
    private static final Identifier FRAME =
            Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "textures/gui/sprites/panel/frame.png");
    /** Item icons are 16 pixels. */
    private static final int ICON = 16;
    /** The dots a name loses its tail to when it is too long for its line. */
    private static final String ELLIPSIS = "..";

    /** What is under the mouse in a panel (not in the inventory): the item and the cell it is in. */
    private record Hit(Item item, Rect cell) {}

    private final AbstractContainerScreen<?> screen;
    private final CatalogueState state = new CatalogueState();
    private final Font font = Minecraft.getInstance().font;
    private @Nullable CatalogueLayout layout;
    private PartsCatalogue catalogue = PartsCatalogue.of(List.of(), ContextMap.EMPTY);
    private @Nullable List<RecipeCollection> builtFrom;
    private StackedItemContents contents = new StackedItemContents();
    private boolean searchFocused;
    private boolean pressTaken;
    private int ticks;
    private int mouseX = -1;
    private int mouseY = -1;

    CataloguePanel(AbstractContainerScreen<?> screen) {
        this.screen = screen;
        refresh();
    }

    /** Lays the panels out for a screen {@code width} by {@code height}. */
    void resize(int width, int height) {
        CrtTuning crt = CrtTuning.current();
        layout = CatalogueLayout.of(width, height, CatalogueLook.current(), crt, font.lineHeight);
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
    }

    // ---------------------------------------------------------------- what is where

    private static UUID player() {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            throw new IllegalStateException("The catalogue is open with no player");
        }
        return player.getUUID();
    }

    private List<Item> shown() {
        return state.shown(catalogue, contents);
    }

    /** The cell {@code index} of the item grid or of the favourites row. */
    private Rect gridCell(CatalogueLayout.Shelf shelf, CatalogueLook look, int index) {
        return new Rect(
                shelf.gridX() + (index % shelf.columns()) * look.gridCell(),
                shelf.gridY() + (index / shelf.columns()) * look.gridCell(),
                look.slotSize(),
                look.slotSize());
    }

    private Rect favouriteCell(CatalogueLayout.Shelf shelf, CatalogueLook look, int index) {
        return new Rect(shelf.gridX() + index * look.gridCell(), shelf.favouritesY(), look.slotSize(), look.slotSize());
    }

    private List<Item> favourites() {
        return CatalogueFavourites.shared().items(player());
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
                card.gridX() + (index % recipe.columns()) * look.recipeCell(),
                card.gridY() + (index / recipe.columns()) * look.recipeCell(),
                look.slotSize(),
                look.slotSize());
    }

    private static Rect resultCell(CatalogueLayout.Card card, CatalogueLook look) {
        return new Rect(card.gridX() + look.resultX(), card.gridY() + look.resultY(), look.slotSize(), look.slotSize());
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
        CatalogueLook look = CatalogueLook.current();
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
            for (Hit hit : recipeHits(now.card(), look, recipe)) {
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
        if (searchFocused) {
            return searchKey(event);
        }
        if (event.hasControlDown() && event.key() == InputConstants.KEY_F) {
            searchFocused = true;
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
            return true;
        }
        return false;
    }

    private boolean searchKey(KeyEvent event) {
        String query = state.query();
        if (event.isEscape() || event.isConfirmation()) {
            searchFocused = false;
        } else if (event.key() == InputConstants.KEY_BACKSPACE) {
            if (event.hasControlDown()) {
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
        double x = event.x();
        double y = event.y();
        CatalogueLayout.Shelf shelf = now.shelf();
        boolean onShelf = now.catalogue().contains(x, y);
        boolean onCard = showsRecipePanel() && now.recipe().contains(x, y);
        searchFocused = onShelf && shelf.search().contains(x, y);
        if (!onShelf && !onCard) {
            return false;
        }
        pressTaken = true;
        boolean right = event.button() == 1;
        Hit hit = hitAt(x, y);
        if (hit != null) {
            lookUp(hit.item(), right ? CatalogueState.Mode.USES : CatalogueState.Mode.RECIPES);
        } else if (onShelf) {
            shelfClick(shelf, x, y);
        } else {
            CatalogueLayout.Card card = now.card();
            Item item = state.lookup() == null ? null : state.lookup().item();
            if (item != null && card.spec().contains(x, y)) {
                SpecSheetHook.open(item);
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
        int direction = vertical > 0 ? -1 : 1;
        if (now.catalogue().contains(x, y)) {
            CatalogueLayout.Shelf shelf = now.shelf();
            if (y >= shelf.favouritesLabelY()
                    && y < shelf.favouritesY() + CatalogueLook.current().slotSize()) {
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
        CatalogueLook look = CatalogueLook.current();
        Hit hit = hitAt(mouseX, mouseY);
        graphics.nextStratum();
        drawShelf(graphics, now, look, hit);
        if (showsRecipePanel()) {
            drawCard(graphics, now, look, hit);
        }
        if (hit != null) {
            graphics.setTooltipForNextFrame(font, new ItemStack(hit.item()), mouseX, mouseY);
        }
    }

    /** The bezel, cut from the pack's frame sprite as the terminals' panel is: corners as drawn, edges stretched from one clean pixel line. */
    private void bezel(GuiGraphicsExtractor graphics, CatalogueLook look, Rect panel) {
        int corner = look.bezelCorner();
        int right = panel.right() - corner;
        int bottom = panel.bottom() - corner;
        int fw = look.frameWidth();
        int fh = look.frameHeight();
        blit(
                graphics,
                panel.x(),
                panel.y(),
                look.bezelLeftU(),
                look.bezelTopV(),
                corner,
                corner,
                corner,
                corner,
                fw,
                fh);
        blit(graphics, right, panel.y(), look.bezelRightU(), look.bezelTopV(), corner, corner, corner, corner, fw, fh);
        blit(
                graphics,
                panel.x(),
                bottom,
                look.bezelLeftU(),
                look.bezelBottomV(),
                corner,
                corner,
                corner,
                corner,
                fw,
                fh);
        blit(graphics, right, bottom, look.bezelRightU(), look.bezelBottomV(), corner, corner, corner, corner, fw, fh);
        int across = panel.width() - 2 * corner;
        int down = panel.height() - 2 * corner;
        blit(
                graphics,
                panel.x() + corner,
                panel.y(),
                look.bezelEdgeU(),
                look.bezelTopV(),
                across,
                corner,
                1,
                corner,
                fw,
                fh);
        blit(
                graphics,
                panel.x() + corner,
                bottom,
                look.bezelEdgeU(),
                look.bezelBottomV(),
                across,
                corner,
                1,
                corner,
                fw,
                fh);
        blit(
                graphics,
                panel.x(),
                panel.y() + corner,
                look.bezelLeftU(),
                look.bezelEdgeV(),
                corner,
                down,
                corner,
                1,
                fw,
                fh);
        blit(
                graphics,
                right,
                panel.y() + corner,
                look.bezelRightU(),
                look.bezelEdgeV(),
                corner,
                down,
                corner,
                1,
                fw,
                fh);
    }

    private static void blit(
            GuiGraphicsExtractor graphics,
            int x,
            int y,
            int u,
            int v,
            int width,
            int height,
            int uWidth,
            int vHeight,
            int fw,
            int fh) {
        graphics.blit(RenderPipelines.GUI_TEXTURED, FRAME, x, y, u, v, width, height, uWidth, vHeight, fw, fh);
    }

    /** What is behind a panel's content: the phosphor in the glass, then the bezel round it. */
    private void back(GuiGraphicsExtractor graphics, CatalogueLook look, Rect panel, Rect glass) {
        CrtDraw.backdrop(graphics, glass.x(), glass.y(), glass.right(), glass.bottom());
        bezel(graphics, look, panel);
    }

    /** What is over a panel's content: the glass sprite and the scanlines, inside the glass only. */
    private void front(GuiGraphicsExtractor graphics, Rect glass) {
        CrtDraw.sprite(graphics, PanelLook.GLASS, glass.x(), glass.y(), glass.width(), glass.height());
        CrtDraw.scanlines(graphics, glass.x(), glass.y(), glass.right(), glass.bottom());
    }

    private void drawShelf(GuiGraphicsExtractor graphics, CatalogueLayout now, CatalogueLook look, @Nullable Hit hit) {
        CrtTuning crt = CrtTuning.current();
        PanelLook panel = PanelLook.current();
        CatalogueLayout.Shelf shelf = now.shelf();
        back(graphics, look, now.catalogue(), now.catalogueGlass());
        graphics.nextStratum();

        int width = shelf.right() - shelf.left();
        CrtDraw.header(
                graphics, font, CrtText.terminal(text("title")), shelf.left(), shelf.titleY(), shelf.titleRight());

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
            dim(graphics, fit(text("empty"), width), shelf.gridX(), shelf.gridY() + 2, panel);
        }

        dim(graphics, text("favourites"), shelf.left(), shelf.favouritesLabelY(), panel);
        List<Item> favourites = favourites();
        int offset = state.favouriteOffset(favourites.size(), shelf.columns());
        for (int i = 0; i < shelf.columns() && offset + i < favourites.size(); i++) {
            drawCell(graphics, favouriteCell(shelf, look, i), look, favourites.get(offset + i), hit);
        }

        graphics.nextStratum();
        front(graphics, now.catalogueGlass());
        graphics.nextStratum();

        // The buttons are metal, over the glass and its scanlines.
        Rect craftable = shelf.craftable();
        button(
                graphics,
                craftable,
                text("craftable"),
                true,
                state.craftableOnly() || craftable.contains(mouseX, mouseY),
                panel,
                crt);
        int page = state.page(pages);
        button(graphics, shelf.previous(), "<", page > 0, shelf.previous().contains(mouseX, mouseY), panel, crt, false);
        button(graphics, shelf.next(), ">", page < pages - 1, shelf.next().contains(mouseX, mouseY), panel, crt, false);
        String counter = (page + 1) + "/" + pages;
        int counterX = (shelf.left() + shelf.right()) / 2 - termWidth(counter) / 2;
        dim(graphics, counter, counterX, shelf.pageTextY(), panel);
    }

    private void drawSearch(
            GuiGraphicsExtractor graphics, Rect search, CatalogueLook look, CrtTuning crt, PanelLook panel) {
        int textX = search.x() + look.searchPad();
        int textY = search.y() + look.searchPad();
        int room = search.width() - 2 * look.searchPad() - termWidth("_");
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
            CrtDraw.glowText(graphics, font, CrtText.terminal("_"), textX, textY, crt.phosphorColor());
        }
    }

    private void drawCell(GuiGraphicsExtractor graphics, Rect cell, CatalogueLook look, Item item, @Nullable Hit hit) {
        CrtTuning crt = CrtTuning.current();
        CatalogueState.Lookup lookup = state.lookup();
        boolean lit = (hit != null && hit.cell().equals(cell)) || (lookup != null && lookup.item() == item);
        slot(graphics, cell, lit, crt);
        graphics.item(new ItemStack(item), cell.x() + look.iconInset(), cell.y() + look.iconInset());
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
        CatalogueLayout.Card card = now.card();
        CatalogueState.Lookup lookup = state.lookup();
        if (lookup == null) {
            return;
        }
        KnownRecipe recipe = state.shownRecipe(catalogue);
        int width = card.right() - card.left();
        graphics.nextStratum();
        back(graphics, look, now.recipe(), now.recipeGlass());
        graphics.nextStratum();

        String title = recipe != null
                ? recipe.results().getFirst().getHoverName().getString()
                : new ItemStack(lookup.item()).getHoverName().getString();
        CrtDraw.header(
                graphics,
                font,
                CrtText.terminal(fit(title.toUpperCase(Locale.ROOT), width)),
                card.left(),
                card.titleY(),
                card.right());
        if (recipe == null) {
            String none = text(lookup.mode() == CatalogueState.Mode.RECIPES ? "none.recipes" : "none.uses");
            dim(graphics, fit(none, width), card.left(), card.stationY(), panel);
        } else {
            dim(graphics, fit(recipe.station(), width), card.left(), card.stationY(), panel);
            drawRecipe(graphics, card, look, recipe, hit, crt, panel);
        }

        graphics.nextStratum();
        front(graphics, now.recipeGlass());
        graphics.nextStratum();
        Rect spec = card.spec();
        button(
                graphics,
                spec,
                text("spec"),
                recipe != null,
                recipe != null && spec.contains(mouseX, mouseY),
                panel,
                crt);
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
        glow(graphics, "->", card.gridX() + look.arrowX(), card.gridY() + look.arrowY(), crt);
        Rect result = resultCell(card, look);
        slot(graphics, result, true, crt);
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

    private void button(
            GuiGraphicsExtractor graphics,
            Rect rect,
            String label,
            boolean active,
            boolean lit,
            PanelLook panel,
            CrtTuning crt) {
        button(graphics, rect, label, active, lit, panel, crt, true);
    }

    /** A metal button of the machine panel with its label in the terminal font; the pip only when it fits and {@code pip} says so. */
    private void button(
            GuiGraphicsExtractor graphics,
            Rect rect,
            String label,
            boolean active,
            boolean lit,
            PanelLook panel,
            CrtTuning crt,
            boolean pip) {
        int labelWidth = termWidth(label);
        boolean showPip = pip && panel.showsPip(rect.width(), labelWidth);
        boolean hot = lit && active;
        int labelX = CrtDraw.panelButtonFace(
                graphics, panel, rect.x(), rect.y(), rect.width(), rect.height(), active, hot, labelWidth, showPip);
        int labelY = rect.y() + (rect.height() - font.lineHeight) / 2 + crt.buttonLabelOffset();
        graphics.text(font, CrtText.terminal(label), labelX, labelY, panel.labelColor(active, hot), false);
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
