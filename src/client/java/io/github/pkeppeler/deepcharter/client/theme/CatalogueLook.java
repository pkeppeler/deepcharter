package io.github.pkeppeler.deepcharter.client.theme;

import io.github.pkeppeler.deepcharter.theme.ThemeData;

/**
 * The size and place of the parts catalogue and its recipe panel ({@code theme/catalogue.json}, #846), in GUI pixels. Its colours are the
 * colony terminals', {@code crt.json} and {@code panel.json}, and its glass, buttons, pips and bezel are the pack's own sprites under
 * {@code textures/gui/sprites/panel/}; the bezel is cut from {@code frame.png} by the {@code bezel*} numbers.
 *
 * @param maxPanelWidth the widest either panel gets, beside the inventory
 * @param minPanelWidth the narrowest either panel draws at; room for less hides both
 * @param screenEdge pixels between a panel and the window's edge
 * @param gapLeft pixels between the recipe panel and the inventory
 * @param gapRight pixels between the inventory and the catalogue
 * @param catalogueTop the catalogue's top edge
 * @param catalogueHeight the catalogue's height, less on a GUI too short for it
 * @param recipeTop the recipe panel's top edge
 * @param recipeHeight the recipe panel's height, less on a GUI too short for it
 * @param glassLeft pixels from a panel's left edge to its glass
 * @param glassTop pixels from a panel's top edge to its glass
 * @param glassRight pixels from a panel's right edge to its glass
 * @param glassBottom pixels from a panel's bottom edge to its glass
 * @param frameWidth the width in pixels of the frame sprite the bezel is cut from
 * @param frameHeight its height
 * @param bezelCorner the side of a bezel corner cut from the frame sprite
 * @param bezelLeftU the frame sprite's x of the left corners, and of the left edge slice
 * @param bezelRightU the frame sprite's x of the right corners, and of the right edge slice
 * @param bezelTopV the frame sprite's y of the top corners, and of the top edge slice
 * @param bezelBottomV the frame sprite's y of the bottom corners, and of the bottom edge slice
 * @param bezelEdgeU the frame sprite's x of the one-pixel column the top and bottom edges are stretched from
 * @param bezelEdgeV the frame sprite's y of the one-pixel row the left and right edges are stretched from
 * @param pad pixels between the glass and what is drawn on it, left and right
 * @param gridRightPad pixels the item grid keeps clear of the glass's right edge
 * @param titleTop pixels from the glass's top to a panel's title
 * @param titleGap pixels from the title's rule to what follows
 * @param searchHeight the search field's height
 * @param searchPad pixels between the search field's border and its text
 * @param searchGap pixels from the search field to the item grid
 * @param gridCell the pitch of the catalogue's cells
 * @param gridColumns the catalogue's columns at full width, fewer when the panel is narrower
 * @param gridRows the catalogue's rows, fewer when the panel is shorter
 * @param gridGap pixels from the item grid to the favourites label
 * @param labelHeight the height of a dim label with the row of cells under it
 * @param slotSize the side of one cell's outline
 * @param iconInset pixels from a cell's outline to its item
 * @param buttonHeight the height of a metal button
 * @param buttonRise pixels from the glass's bottom to the top of the bottom row of buttons
 * @param craftableRise pixels the craftable toggle sits above the bottom row of buttons
 * @param pageButtonWidth the width of the page arrows
 * @param pageButtonOutset pixels the page arrows reach out past the text margin
 * @param recipeCell the pitch of the recipe grid's cells
 * @param stationGap pixels from the station name to the recipe grid
 * @param arrowX pixels from the recipe grid's left to the arrow
 * @param arrowY pixels from the recipe grid's top to the arrow
 * @param resultX pixels from the recipe grid's left to the result cell
 * @param resultY pixels from the recipe grid's top to the result cell
 * @param counterY pixels from the recipe grid's top to the "1/3" counter under the result
 * @param needTop pixels from the recipe grid's top to the first have/need row
 * @param needRow the pitch of the have/need rows
 * @param needTextX pixels from a have/need row's left to its figures
 * @param needTextY pixels from a have/need row's top to its figures
 * @param cycleMillis how long a cell shows one item of a tag before the next
 * @param maxQueryLength the longest search the field takes
 * @param nameStripPad pixels between the border of the strip that names the hovered item and its text
 * @param nameStripGap pixels between the hovered cell and that strip
 * @param caretHeight the thickness of the search field's caret, the underline after the text; at least the scanline spacing, so a scanline never takes it
 */
public record CatalogueLook(
        int maxPanelWidth,
        int minPanelWidth,
        int screenEdge,
        int gapLeft,
        int gapRight,
        int catalogueTop,
        int catalogueHeight,
        int recipeTop,
        int recipeHeight,
        int glassLeft,
        int glassTop,
        int glassRight,
        int glassBottom,
        int frameWidth,
        int frameHeight,
        int bezelCorner,
        int bezelLeftU,
        int bezelRightU,
        int bezelTopV,
        int bezelBottomV,
        int bezelEdgeU,
        int bezelEdgeV,
        int pad,
        int gridRightPad,
        int titleTop,
        int titleGap,
        int searchHeight,
        int searchPad,
        int searchGap,
        int gridCell,
        int gridColumns,
        int gridRows,
        int gridGap,
        int labelHeight,
        int slotSize,
        int iconInset,
        int buttonHeight,
        int buttonRise,
        int craftableRise,
        int pageButtonWidth,
        int pageButtonOutset,
        int recipeCell,
        int stationGap,
        int arrowX,
        int arrowY,
        int resultX,
        int resultY,
        int counterY,
        int needTop,
        int needRow,
        int needTextX,
        int needTextY,
        int cycleMillis,
        int maxQueryLength,
        int nameStripPad,
        int nameStripGap,
        int caretHeight) {
    public static CatalogueLook current() {
        return UiTheme.current().catalogue();
    }

    public static CatalogueLook of(ThemeData d) {
        CatalogueLook look = new CatalogueLook(
                d.integer("maxPanelWidth", 40, 400),
                d.integer("minPanelWidth", 40, 400),
                d.integer("screenEdge", 0, 64),
                d.integer("gapLeft", 0, 64),
                d.integer("gapRight", 0, 64),
                d.integer("catalogueTop", 0, 200),
                d.integer("catalogueHeight", 40, 400),
                d.integer("recipeTop", 0, 200),
                d.integer("recipeHeight", 40, 400),
                d.integer("glassLeft", 0, 64),
                d.integer("glassTop", 0, 64),
                d.integer("glassRight", 0, 64),
                d.integer("glassBottom", 0, 64),
                d.integer("frameWidth", 1, 4096),
                d.integer("frameHeight", 1, 4096),
                d.integer("bezelCorner", 1, 64),
                d.integer("bezelLeftU", 0, 4096),
                d.integer("bezelRightU", 0, 4096),
                d.integer("bezelTopV", 0, 4096),
                d.integer("bezelBottomV", 0, 4096),
                d.integer("bezelEdgeU", 0, 4096),
                d.integer("bezelEdgeV", 0, 4096),
                d.integer("pad", 0, 64),
                d.integer("gridRightPad", 0, 64),
                d.integer("titleTop", 0, 64),
                d.integer("titleGap", 0, 64),
                d.integer("searchHeight", 9, 64),
                d.integer("searchPad", 0, 16),
                d.integer("searchGap", 0, 64),
                d.integer("gridCell", 16, 64),
                d.integer("gridColumns", 1, 12),
                d.integer("gridRows", 1, 12),
                d.integer("gridGap", 0, 64),
                d.integer("labelHeight", 9, 64),
                d.integer("slotSize", 16, 64),
                d.integer("iconInset", 0, 16),
                d.integer("buttonHeight", 9, 64),
                d.integer("buttonRise", 0, 128),
                d.integer("craftableRise", 0, 128),
                d.integer("pageButtonWidth", 8, 64),
                d.integer("pageButtonOutset", 0, 16),
                d.integer("recipeCell", 16, 64),
                d.integer("stationGap", 0, 64),
                d.integer("arrowX", 0, 256),
                d.integer("arrowY", 0, 256),
                d.integer("resultX", 0, 256),
                d.integer("resultY", 0, 256),
                d.integer("counterY", 0, 256),
                d.integer("needTop", 0, 256),
                d.integer("needRow", 16, 64),
                d.integer("needTextX", 0, 64),
                d.integer("needTextY", 0, 64),
                d.integer("cycleMillis", 100, 60000),
                d.integer("maxQueryLength", 1, 256),
                d.integer("nameStripPad", 0, 16),
                d.integer("nameStripGap", 0, 16),
                d.integer("caretHeight", 1, 8));
        if (look.minPanelWidth() > look.maxPanelWidth()) {
            throw d.conflict("minPanelWidth must not exceed maxPanelWidth", "minPanelWidth", "maxPanelWidth");
        }
        return look;
    }
}
