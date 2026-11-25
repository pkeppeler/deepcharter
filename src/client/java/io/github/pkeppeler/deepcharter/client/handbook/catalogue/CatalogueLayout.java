package io.github.pkeppeler.deepcharter.client.handbook.catalogue;

import io.github.pkeppeler.deepcharter.client.theme.CatalogueLook;
import io.github.pkeppeler.deepcharter.client.ui.CrtTuning;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Where everything of the catalogue and its recipe panel sits on a screen of a given size, in GUI pixels. Pure
 * arithmetic over the {@link CatalogueLook} numbers: the draw code and the input code read the same rectangles, and a
 * test reads them against the screen.
 *
 * <p>The recipe console stands to the left of the inventory image and the catalogue console to its right, each level
 * with it and as wide as {@link CatalogueLook#maxPanelWidth()} allows; a band under {@link
 * CatalogueLook#minPanelWidth()} hides both. The layout is {@linkplain #of hidden} (null) when the window has no room
 * for them, because the content has no room to fit.
 *
 * <p>The inventory's active effects stand in a row over the three, in the strip the window leaves above them: vanilla
 * draws them right of the inventory image, where the catalogue stands, and the sides have no room to move them to. The
 * row is 32 px tiles in a window with room for them, bare 18 px icons in one with less, and a window with room for
 * neither hides the panels, so vanilla's recipe book and effect list come back. Only a screen that draws effects (the
 * inventory) has the row.
 *
 * @param catalogue the catalogue's casing
 * @param catalogueGlass the glass of the catalogue
 * @param shelf what is drawn on the catalogue's glass and under it
 * @param recipe the recipe console's casing
 * @param recipeGlass the glass of the recipe console
 * @param card what is drawn on the recipe console's glass and under it
 * @param inventory the inventory image the consoles stand beside
 * @param effects the row the inventory's active effects stand in, over the image and both consoles; null on a screen
 *     that draws none
 */
public record CatalogueLayout(
        Rect catalogue,
        Rect catalogueGlass,
        Shelf shelf,
        Rect recipe,
        Rect recipeGlass,
        Card card,
        Rect inventory,
        @Nullable Rect effects) {
    /** The width of the inventory image of the screens the catalogue stands beside (vanilla's default). */
    public static final int IMAGE_WIDTH = 176;
    /** The height of that image. */
    public static final int IMAGE_HEIGHT = 166;
    /** The side of an effect's tile, vanilla's {@code effect_background} sprite. */
    public static final int EFFECT_TILE = 32;
    /** The side of an effect's icon, vanilla's. */
    public static final int EFFECT_ICON = 18;
    /** Pixels between the effects' row and the consoles under it. */
    public static final int EFFECTS_GAP = 1;

    /**
     * How tall the row of effects over the consoles is: none on a screen without effects, else tiles, or bare icons in
     * a short window.
     */
    public enum EffectRow {
        NONE(0),
        ICONS(EFFECT_ICON),
        TILES(EFFECT_TILE);

        private final int height;

        EffectRow(int height) {
            this.height = height;
        }

        /** The row's height, 0 for none. */
        public int height() {
            return height;
        }
    }

    /**
     * The shortest GUI the panels draw at with {@code row} over them: level with the inventory image, with the row and
     * its gap above and a screen edge each side. The one number the layout and its tests read.
     */
    public static int shortest(CatalogueLook look, EffectRow row) {
        int band = row == EffectRow.NONE ? 0 : EFFECTS_GAP + row.height();
        return Math.max(look.consoleHeight(), IMAGE_HEIGHT) + 2 * (look.screenEdge() + band);
    }

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
     * The catalogue's content: what is on the glass, and the controls under it.
     *
     * @param left where text and cells start, the left of the item grid and of the favourites
     * @param right where the title and the search end, and its rule
     * @param titleY the title's top
     * @param search the search field
     * @param gridY the top of the item grid
     * @param columns the grid's columns, and the cells of the favourites row
     * @param rows the grid's rows
     * @param favourites the favourites' label and its row of cells
     * @param favouritesLabelY the top of the "FAVOURITES" label
     * @param favouritesY the top of the favourites row
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
            Rect search,
            int gridY,
            int columns,
            int rows,
            Rect favourites,
            int favouritesLabelY,
            int favouritesY,
            Rect craftable,
            Rect previous,
            Rect next,
            Rect counter,
            List<Rect> vents) {
        /** Cells on one page. */
        public int perPage() {
            return columns * rows;
        }
    }

    /**
     * The recipe console's content.
     *
     * @param left where text and cells start, the left of the recipe grid
     * @param right where text ends
     * @param titleY the title's top
     * @param stationY the station name's top
     * @param gridY the top of the recipe grid
     * @param needTop the top of the first have/need row
     * @param needRows how many have/need rows fit
     * @param needBottom the y under which no have/need row may reach
     * @param spec the spec sheet switch, a plate with its legend
     * @param fill the switch that puts the recipe in the crafting grid, or shows it there, a plate with its legend under the spec sheet's
     */
    public record Card(
            int left,
            int right,
            int titleY,
            int stationY,
            int gridY,
            int needTop,
            int needRows,
            int needBottom,
            Rect spec,
            Rect fill) {
        /**
         * The card with its title {@code extra} pixels taller (a name that takes more lines than one): the station, the grid and the
         * have/need rows move down by it, and fewer rows may fit.
         */
        public Card lowered(int extra, int needRow) {
            int top = needTop + extra;
            return new Card(
                    left,
                    right,
                    titleY,
                    stationY + extra,
                    gridY + extra,
                    top,
                    Math.max(0, (needBottom - top) / needRow),
                    needBottom,
                    spec,
                    fill);
        }
    }

    /**
     * The layout on a screen {@code width} by {@code height}, or null when the window is too narrow for the units'
     * bands or too short for them and, when the screen {@code wantsEffects} (draws the player's effects), the smallest
     * row of them over them.
     */
    public static @Nullable CatalogueLayout of(
            int width, int height, CatalogueLook look, CrtTuning crt, int lineHeight, boolean wantsEffects) {
        int inventoryLeft = (width - IMAGE_WIDTH) / 2;
        int rightX = inventoryLeft + IMAGE_WIDTH + look.gapRight();
        int rightWidth = Math.min(look.maxPanelWidth(), width - look.screenEdge() - rightX);
        int leftWidth = Math.min(look.maxPanelWidth(), inventoryLeft - look.gapLeft() - look.screenEdge());
        if (leftWidth < look.minPanelWidth() || rightWidth < look.minPanelWidth()) {
            return null;
        }
        EffectRow row = !wantsEffects
                ? EffectRow.NONE
                : height >= shortest(look, EffectRow.TILES) ? EffectRow.TILES : EffectRow.ICONS;
        if (height < shortest(look, row)) {
            return null;
        }
        int inventoryTop = (height - IMAGE_HEIGHT) / 2;
        Rect inventory = new Rect(inventoryLeft, inventoryTop, IMAGE_WIDTH, IMAGE_HEIGHT);

        Rect recipe =
                new Rect(inventoryLeft - look.gapLeft() - leftWidth, inventoryTop, leftWidth, look.consoleHeight());
        Rect recipeGlass = glass(recipe, look, look.casing().consoleDeck());
        Card card = card(recipeGlass, look, crt, lineHeight);
        Rect casing = new Rect(rightX, inventoryTop, rightWidth, look.consoleHeight());
        Rect glass = glass(casing, look, look.casing().consoleDeck());
        Rect effects = row == EffectRow.NONE
                ? null
                : new Rect(
                        recipe.x(),
                        inventoryTop - EFFECTS_GAP - row.height(),
                        casing.right() - recipe.x(),
                        row.height());
        return new CatalogueLayout(
                casing, glass, shelf(glass, look, crt, lineHeight), recipe, recipeGlass, card, inventory, effects);
    }

    static Rect glass(Rect casing, CatalogueLook look, int deck) {
        CatalogueLook.Casing c = look.casing();
        return new Rect(
                casing.x() + c.sideBezel(),
                casing.y() + c.topBezel(),
                casing.width() - 2 * c.sideBezel(),
                casing.height() - c.topBezel() - deck);
    }

    /** The y under a title that starts at {@code titleY}: its rule, then the gap. */
    static int belowTitle(int titleY, CatalogueLook look, CrtTuning crt, int lineHeight) {
        return titleY + lineHeight + crt.headerRuleGap() + 1 + look.titleGap();
    }

    private static Shelf shelf(Rect glass, CatalogueLook look, CrtTuning crt, int lineHeight) {
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
                search,
                gridY,
                columns,
                rows,
                favourites,
                favouritesLabelY,
                favouritesLabelY + look.labelHeight(),
                craftable,
                previous,
                next,
                counter,
                List.of(ventsBetween(previous, counter, c), ventsBetween(counter, next, c)));
    }

    /** The vents in the gap from {@code before} to {@code after}, a row of them as tall as the controls less their margin. */
    private static Rect ventsBetween(Rect before, Rect after, CatalogueLook.Casing c) {
        int margin = c.deckGap();
        int slotsHeight = c.ventHeight();
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
        int needBottom = glass.bottom() - look.contentBottom();
        int needRows = Math.max(0, (needBottom - needTop) / look.needRow());
        int lower = upper + c.deckRow() + c.deckRowGap();
        Rect fill = new Rect(glass.x(), lower, glass.width(), c.deckRow());
        return new Card(left, right, titleY, stationY, gridY, needTop, needRows, needBottom, spec, fill);
    }
}
