package io.github.pkeppeler.deepcharter.client.handbook.catalogue;

import io.github.pkeppeler.deepcharter.client.theme.CatalogueLook;
import io.github.pkeppeler.deepcharter.client.theme.CatalogueLook.Form;
import io.github.pkeppeler.deepcharter.client.ui.CrtTuning;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Where everything of the catalogue and its recipe panel sits on a screen of a given size, in GUI pixels. Pure arithmetic over the
 * {@link CatalogueLook} numbers: the draw code and the input code read the same rectangles, and a test reads them against the screen.
 *
 * <p>Both forms put the recipe console to the left of the inventory image, level with it. In the {@linkplain Form#CONSOLE console} form
 * the catalogue is a second console to its right, so each is as wide as {@link CatalogueLook#maxPanelWidth()} allows and a band under
 * {@link CatalogueLook#minPanelWidth()} hides both. In the {@linkplain Form#MONITOR monitor} form the catalogue is one wide monitor over
 * the inventory, and the inventory image moves down to make room for it ({@code inventory} says where it ends up). The unit is
 * {@linkplain #of hidden} (null) when the window has no room for it, because the content has no room to fit.
 *
 * @param form the form the layout is of
 * @param catalogue the catalogue's casing
 * @param catalogueGlass the glass of the catalogue
 * @param shelf what is drawn on the catalogue's glass and under it
 * @param recipe the recipe console's casing
 * @param recipeGlass the glass of the recipe console
 * @param card what is drawn on the recipe console's glass and under it
 * @param inventory the inventory image the units stand beside or under
 */
public record CatalogueLayout(
        Form form,
        Rect catalogue,
        Rect catalogueGlass,
        Shelf shelf,
        Rect recipe,
        Rect recipeGlass,
        Card card,
        Rect inventory) {
    /** The width of the inventory image of the screens the catalogue stands beside (vanilla's default). */
    public static final int IMAGE_WIDTH = 176;
    /** The height of that image. */
    public static final int IMAGE_HEIGHT = 166;

    /** A rectangle on the screen. */
    public record Rect(int x, int y, int width, int height) {
        public int right() {
            return x + width;
        }

        public int bottom() {
            return y + height;
        }

        public boolean contains(double px, double py) {
            return px >= x && px < right() && py >= y && py < bottom();
        }

        /** Whether the two share a pixel. */
        public boolean overlaps(Rect other) {
            return x < other.right() && other.x < right() && y < other.bottom() && other.y < bottom();
        }
    }

    /**
     * The catalogue's content: what is on the glass, and the controls under it.
     *
     * @param left where text and cells start
     * @param right where the title and the search end
     * @param titleY the title's top
     * @param titleRight the right end of the title's rule
     * @param search the search field
     * @param gridX the left of the item grid
     * @param gridY the top of the item grid
     * @param columns the grid's columns
     * @param rows the grid's rows
     * @param favourites the favourites' label and its row of cells
     * @param favouritesLabelY the top of the "FAVOURITES" label
     * @param favouritesX the left of the label and of the row
     * @param favouritesY the top of the favourites row
     * @param favouriteColumns the cells of the favourites row
     * @param craftable the craftable-now switch, a plate with its legend
     * @param previous the page-back knob
     * @param next the page-forward knob
     * @param counter the plate of the page counter
     * @param vents the vents between the controls
     */
    public record Shelf(
            int left,
            int right,
            int titleY,
            int titleRight,
            Rect search,
            int gridX,
            int gridY,
            int columns,
            int rows,
            Rect favourites,
            int favouritesLabelY,
            int favouritesX,
            int favouritesY,
            int favouriteColumns,
            Rect craftable,
            Rect previous,
            Rect next,
            Rect counter,
            List<Rect> vents) {
        /** Cells on one page. */
        public int perPage() {
            return columns * rows;
        }

        /** The controls, which are drawn over the casing, outside the glass. */
        public List<Rect> controls() {
            return List.of(craftable, previous, next, counter);
        }
    }

    /**
     * The recipe console's content.
     *
     * @param left where text and cells start
     * @param right where text ends
     * @param titleY the title's top
     * @param stationY the station name's top
     * @param gridX the left of the recipe grid
     * @param gridY the top of the recipe grid
     * @param needTop the top of the first have/need row
     * @param needRows how many have/need rows fit
     * @param spec the spec sheet switch, a plate with its legend
     * @param vents the vents under the switch
     */
    public record Card(
            int left,
            int right,
            int titleY,
            int stationY,
            int gridX,
            int gridY,
            int needTop,
            int needRows,
            Rect spec,
            List<Rect> vents) {}

    /**
     * The layout on a screen {@code width} by {@code height}, or null when the window is too narrow for the units' bands or too short for
     * them.
     */
    public static @Nullable CatalogueLayout of(
            int width, int height, CatalogueLook look, CrtTuning crt, int lineHeight) {
        boolean monitor = look.form() == Form.MONITOR;
        int inventoryLeft = (width - IMAGE_WIDTH) / 2;
        int rightX = inventoryLeft + IMAGE_WIDTH + look.gapRight();
        int rightWidth = Math.min(look.maxPanelWidth(), width - look.screenEdge() - rightX);
        int leftWidth = Math.min(look.maxPanelWidth(), inventoryLeft - look.gapLeft() - look.screenEdge());
        if (leftWidth < look.minPanelWidth() || (!monitor && rightWidth < look.minPanelWidth())) {
            return null;
        }
        CatalogueLook.Monitor m = look.monitor();
        int inventoryTop;
        int monitorTop = 0;
        if (monitor) {
            int stack = m.height() + m.gap() + IMAGE_HEIGHT;
            if (height < stack + 2 * look.screenEdge() || width < m.width() + 2 * look.screenEdge()) {
                return null;
            }
            monitorTop = (height - stack) / 2;
            inventoryTop = monitorTop + m.height() + m.gap();
        } else {
            if (height < look.consoleHeight() + 2 * look.screenEdge()) {
                return null;
            }
            inventoryTop = (height - IMAGE_HEIGHT) / 2;
        }
        Rect inventory = new Rect(inventoryLeft, inventoryTop, IMAGE_WIDTH, IMAGE_HEIGHT);

        Rect recipe =
                new Rect(inventoryLeft - look.gapLeft() - leftWidth, inventoryTop, leftWidth, look.consoleHeight());
        Rect recipeGlass = glass(recipe, look, look.casing().consoleDeck());
        Card card = card(recipeGlass, look, crt, lineHeight);
        if (monitor) {
            int left = inventoryLeft + (IMAGE_WIDTH - m.width()) / 2;
            Rect casing = new Rect(left, monitorTop, m.width(), m.height());
            Rect glass = glass(casing, look, look.casing().monitorDeck());
            return new CatalogueLayout(
                    look.form(),
                    casing,
                    glass,
                    monitorShelf(glass, look, crt, lineHeight),
                    recipe,
                    recipeGlass,
                    card,
                    inventory);
        }
        Rect casing = new Rect(rightX, inventoryTop, rightWidth, look.consoleHeight());
        Rect glass = glass(casing, look, look.casing().consoleDeck());
        return new CatalogueLayout(
                look.form(),
                casing,
                glass,
                consoleShelf(glass, look, crt, lineHeight),
                recipe,
                recipeGlass,
                card,
                inventory);
    }

    private static Rect glass(Rect casing, CatalogueLook look, int deck) {
        CatalogueLook.Casing c = look.casing();
        return new Rect(
                casing.x() + c.sideBezel(),
                casing.y() + c.topBezel(),
                casing.width() - 2 * c.sideBezel(),
                casing.height() - c.topBezel() - deck);
    }

    /** The y under a title that starts at {@code titleY}: its rule, then the gap. */
    private static int belowTitle(int titleY, CatalogueLook look, CrtTuning crt, int lineHeight) {
        return titleY + lineHeight + crt.headerRuleGap() + 1 + look.titleGap();
    }

    private static Shelf consoleShelf(Rect glass, CatalogueLook look, CrtTuning crt, int lineHeight) {
        CatalogueLook.Casing c = look.casing();
        int left = glass.x() + look.pad();
        int right = glass.right() - look.pad();
        int titleY = glass.y() + look.titleTop();
        Rect search = new Rect(left, belowTitle(titleY, look, crt, lineHeight), right - left, look.searchHeight());
        int gridY = search.bottom() + look.searchGap();
        int slackPerCell = look.gridCell() - look.slotSize();
        int columns = Math.clamp(
                (glass.right() - look.gridRightPad() - left + slackPerCell) / look.gridCell(), 1, look.gridColumns());
        int favouritesBlock = look.labelHeight() + look.slotSize();
        int gridBottom = glass.bottom() - look.contentBottom() - favouritesBlock - look.gridGap();
        int rows = Math.clamp((gridBottom - gridY + slackPerCell) / look.gridCell(), 0, look.gridRows());
        int favouritesLabelY = gridY + rows * look.gridCell() + look.gridGap();
        Rect favourites =
                new Rect(left, favouritesLabelY, (columns - 1) * look.gridCell() + look.slotSize(), favouritesBlock);

        int upper = glass.bottom() + c.deckGap();
        int lower = upper + c.deckRow() + c.deckRowGap();
        Rect craftable = new Rect(glass.x(), upper, glass.width(), c.deckRow());
        Rect previous = new Rect(glass.x(), lower, c.knobSize(), c.knobSize());
        Rect next = new Rect(glass.right() - c.knobSize(), lower, c.knobSize(), c.knobSize());
        Rect counter =
                new Rect(glass.x() + (glass.width() - c.counterWidth()) / 2, lower, c.counterWidth(), c.deckRow());
        return new Shelf(
                left,
                right,
                titleY,
                right,
                search,
                left,
                gridY,
                columns,
                rows,
                favourites,
                favouritesLabelY,
                left,
                favouritesLabelY + look.labelHeight(),
                columns,
                craftable,
                previous,
                next,
                counter,
                List.of(ventsBetween(previous, counter, c), ventsBetween(counter, next, c)));
    }

    private static Shelf monitorShelf(Rect glass, CatalogueLook look, CrtTuning crt, int lineHeight) {
        CatalogueLook.Casing c = look.casing();
        CatalogueLook.Monitor m = look.monitor();
        int left = glass.x() + look.pad();
        int right = left + m.sidebar();
        int titleY = glass.y() + look.titleTop();
        Rect search = new Rect(left, belowTitle(titleY, look, crt, lineHeight), m.sidebar(), look.searchHeight());
        int favouritesRight = glass.right() - look.pad();
        int favouritesX = favouritesRight - m.favouritesWidth();
        int gridX = right + m.gutter();
        int slackPerCell = look.gridCell() - look.slotSize();
        int columns = Math.clamp((favouritesX - m.gutter() - gridX + slackPerCell) / look.gridCell(), 1, m.columns());
        int gridHeight = (m.rows() - 1) * look.gridCell() + look.slotSize();
        int gridY = glass.y() + (glass.height() - gridHeight) / 2;
        int favouritesBlock = look.labelHeight() + look.slotSize();
        int favouritesLabelY = glass.y() + (glass.height() - favouritesBlock) / 2;
        Rect favourites = new Rect(favouritesX, favouritesLabelY, favouritesRight - favouritesX, favouritesBlock);

        int row = glass.bottom() + c.deckGap();
        Rect previous = new Rect(glass.x(), row, c.knobSize(), c.knobSize());
        Rect counter = new Rect(previous.right() + c.deckGap(), row, c.counterWidth(), c.deckRow());
        Rect next = new Rect(counter.right() + c.deckGap(), row, c.knobSize(), c.knobSize());
        Rect craftable = new Rect(next.right() + m.deckGap(), row, m.craftableWidth(), c.deckRow());
        Rect vents = ventsBetween(craftable, new Rect(glass.right(), row, 0, c.deckRow()), c);
        return new Shelf(
                left,
                right,
                titleY,
                right,
                search,
                gridX,
                gridY,
                columns,
                m.rows(),
                favourites,
                favouritesLabelY,
                favouritesX,
                favouritesLabelY + look.labelHeight(),
                m.favouriteColumns(),
                craftable,
                previous,
                next,
                counter,
                List.of(vents));
    }

    /** The vents in the gap from {@code before} to {@code after}, a row of them as tall as the controls less their margin. */
    private static Rect ventsBetween(Rect before, Rect after, CatalogueLook.Casing c) {
        int margin = c.deckGap();
        int slotsHeight = 3 * c.ventSlots();
        return new Rect(
                before.right() + margin,
                before.y() + (before.height() - slotsHeight) / 2,
                after.x() - before.right() - 2 * margin,
                slotsHeight);
    }

    private static Card card(Rect glass, CatalogueLook look, CrtTuning crt, int lineHeight) {
        CatalogueLook.Casing c = look.casing();
        int left = glass.x() + look.pad();
        int right = glass.right() - look.pad();
        int titleY = glass.y() + look.titleTop();
        int stationY = belowTitle(titleY, look, crt, lineHeight);
        int gridY = stationY + look.stationGap();
        int upper = glass.bottom() + c.deckGap();
        Rect spec = new Rect(glass.x(), upper, glass.width(), c.deckRow());
        int needTop = gridY + look.needTop();
        int needRows = Math.max(0, (glass.bottom() - look.contentBottom() - needTop) / look.needRow());
        int lower = upper + c.deckRow() + c.deckRowGap();
        int slotsHeight = 3 * c.ventSlots();
        Rect vents = new Rect(
                glass.x() + c.knobSize() + c.deckGap(),
                lower + (c.deckRow() - slotsHeight) / 2,
                glass.width() - 2 * (c.knobSize() + c.deckGap()),
                slotsHeight);
        return new Card(left, right, titleY, stationY, left, gridY, needTop, needRows, spec, List.of(vents));
    }
}
