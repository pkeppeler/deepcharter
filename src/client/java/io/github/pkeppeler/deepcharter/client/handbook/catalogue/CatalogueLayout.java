package io.github.pkeppeler.deepcharter.client.handbook.catalogue;

import io.github.pkeppeler.deepcharter.client.theme.CatalogueLook;
import io.github.pkeppeler.deepcharter.client.ui.CrtTuning;
import org.jspecify.annotations.Nullable;

/**
 * Where everything of the catalogue and its recipe panel sits on a screen of a given size, in GUI pixels. Pure arithmetic over the
 * {@link CatalogueLook} numbers: the draw code and the input code read the same rectangles, and a test reads them against the screen.
 *
 * <p>The panels stand in the free bands beside the inventory image, the recipe panel to its left and the catalogue to its right. Each is
 * as wide as {@link CatalogueLook#maxPanelWidth()} allows; a narrower band narrows both, and a band under
 * {@link CatalogueLook#minPanelWidth()} hides both ({@link #of} returns null), because their content has no room to fit.
 *
 * @param catalogue the catalogue's outer rectangle, bezel included
 * @param catalogueGlass the glass of the catalogue
 * @param shelf what is drawn on the catalogue's glass
 * @param recipe the recipe panel's outer rectangle
 * @param recipeGlass the glass of the recipe panel
 * @param card what is drawn on the recipe panel's glass
 * @param inventory the inventory image the panels stand beside
 */
public record CatalogueLayout(
        Rect catalogue, Rect catalogueGlass, Shelf shelf, Rect recipe, Rect recipeGlass, Card card, Rect inventory) {
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
    }

    /**
     * The catalogue's content.
     *
     * @param left where text and cells start
     * @param right where text ends
     * @param titleY the title's top
     * @param titleRight the right end of the title's rule
     * @param search the search field
     * @param gridX the left of the item grid
     * @param gridY the top of the item grid
     * @param columns the grid's columns
     * @param rows the grid's rows
     * @param favouritesLabelY the top of the "FAVOURITES" label
     * @param favouritesY the top of the favourites row
     * @param craftable the craftable-now toggle
     * @param previous the page-back button
     * @param next the page-forward button
     * @param pageTextY the top of the page counter
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
            int favouritesLabelY,
            int favouritesY,
            Rect craftable,
            Rect previous,
            Rect next,
            int pageTextY) {
        /** Cells on one page. */
        public int perPage() {
            return columns * rows;
        }
    }

    /**
     * The recipe panel's content.
     *
     * @param left where text and cells start
     * @param right where text ends
     * @param titleY the title's top
     * @param stationY the station name's top
     * @param gridX the left of the recipe grid
     * @param gridY the top of the recipe grid
     * @param needTop the top of the first have/need row
     * @param needRows how many have/need rows fit
     * @param spec the spec sheet button
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
            Rect spec) {}

    /** The layout on a screen {@code width} by {@code height}, or null when the free bands are too narrow to hold both panels. */
    public static @Nullable CatalogueLayout of(
            int width, int height, CatalogueLook look, CrtTuning crt, int lineHeight) {
        int inventoryLeft = (width - IMAGE_WIDTH) / 2;
        int rightX = inventoryLeft + IMAGE_WIDTH + look.gapRight();
        int rightWidth = Math.min(look.maxPanelWidth(), width - look.screenEdge() - rightX);
        int leftWidth = Math.min(look.maxPanelWidth(), inventoryLeft - look.gapLeft() - look.screenEdge());
        if (rightWidth < look.minPanelWidth() || leftWidth < look.minPanelWidth()) {
            return null;
        }
        int leftX = inventoryLeft - look.gapLeft() - leftWidth;
        int inventoryTop = (height - IMAGE_HEIGHT) / 2;
        Rect inventory = new Rect(inventoryLeft, inventoryTop, IMAGE_WIDTH, IMAGE_HEIGHT);

        Rect catalogue = new Rect(
                rightX,
                look.catalogueTop(),
                rightWidth,
                Math.min(look.catalogueHeight(), height - 2 * look.catalogueTop()));
        Rect catalogueGlass = glass(catalogue, look);
        Rect recipe = new Rect(
                leftX,
                look.recipeTop(),
                leftWidth,
                Math.min(look.recipeHeight(), height - look.recipeTop() - look.catalogueTop()));
        Rect recipeGlass = glass(recipe, look);
        return new CatalogueLayout(
                catalogue,
                catalogueGlass,
                shelf(catalogueGlass, look, crt, lineHeight),
                recipe,
                recipeGlass,
                card(recipeGlass, look, crt, lineHeight),
                inventory);
    }

    private static Rect glass(Rect panel, CatalogueLook look) {
        return new Rect(
                panel.x() + look.glassLeft(),
                panel.y() + look.glassTop(),
                panel.width() - look.glassLeft() - look.glassRight(),
                panel.height() - look.glassTop() - look.glassBottom());
    }

    /** The y under a title that starts at {@code titleY}: its rule, then the gap. */
    private static int belowTitle(int titleY, CatalogueLook look, CrtTuning crt, int lineHeight) {
        return titleY + lineHeight + crt.headerRuleGap() + 1 + look.titleGap();
    }

    private static Shelf shelf(Rect glass, CatalogueLook look, CrtTuning crt, int lineHeight) {
        int left = glass.x() + look.pad();
        int right = glass.right() - look.pad();
        int titleY = glass.y() + look.titleTop();
        int searchY = belowTitle(titleY, look, crt, lineHeight);
        Rect search = new Rect(left, searchY, right - left, look.searchHeight());
        int gridY = search.bottom() + look.searchGap();
        int slackPerCell = look.gridCell() - look.slotSize();
        int columns = Math.clamp(
                (glass.right() - look.gridRightPad() - left + slackPerCell) / look.gridCell(), 1, look.gridColumns());
        int buttonY = glass.bottom() - look.buttonRise();
        Rect craftable = new Rect(glass.x(), buttonY - look.craftableRise(), glass.width(), look.buttonHeight());
        int favouritesBlock = look.labelHeight() + look.slotSize();
        int gridBottom = craftable.y() - look.searchGap() - favouritesBlock - look.gridGap();
        int rows = Math.clamp((gridBottom - gridY + slackPerCell) / look.gridCell(), 1, look.gridRows());
        int favouritesLabelY = gridY + rows * look.gridCell() + look.gridGap();
        Rect previous = new Rect(left - look.pageButtonOutset(), buttonY, look.pageButtonWidth(), look.buttonHeight());
        Rect next = new Rect(
                right - look.pageButtonWidth() + look.pageButtonOutset(),
                buttonY,
                look.pageButtonWidth(),
                look.buttonHeight());
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
                favouritesLabelY,
                favouritesLabelY + look.labelHeight(),
                craftable,
                previous,
                next,
                buttonY + (look.buttonHeight() - lineHeight) / 2 + crt.buttonLabelOffset());
    }

    private static Card card(Rect glass, CatalogueLook look, CrtTuning crt, int lineHeight) {
        int left = glass.x() + look.pad();
        int right = glass.right() - look.pad();
        int titleY = glass.y() + look.titleTop();
        int stationY = belowTitle(titleY, look, crt, lineHeight);
        int gridY = stationY + look.stationGap();
        Rect spec = new Rect(glass.x(), glass.bottom() - look.buttonRise(), glass.width(), look.buttonHeight());
        int needTop = gridY + look.needTop();
        int needRows = Math.max(0, (spec.y() - look.searchGap() - needTop) / look.needRow());
        return new Card(left, right, titleY, stationY, left, gridY, needTop, needRows, spec);
    }
}
