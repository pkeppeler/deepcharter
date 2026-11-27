package io.github.pkeppeler.deepcharter.client.theme;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.theme.ThemeData;
import net.minecraft.resources.Identifier;

/**
 * The size, place and hardware of the parts catalogue and its recipe panel ({@code theme/catalogue.json}, #846), in GUI pixels. The
 * glass is lit in the colony terminals' colours, {@code crt.json} and {@code panel.json}; the casing round it, the lip, vents, rivets,
 * knobs and switches are drawn from the numbers and colours here ({@link Casing}). The favourite's star is a pair of white GUI sprites,
 * {@link #STAR} and {@link #STAR_HOLLOW}, tinted in the CRT's colours, which a resource pack replaces as it does any vanilla sprite.
 *
 * @param maxPanelWidth the widest a console gets, beside the inventory
 * @param minPanelWidth the narrowest a console draws at; room for less hides the panels
 * @param screenEdge pixels between a panel and the window's edge
 * @param gapLeft pixels between the recipe console and the inventory
 * @param gapRight pixels between the inventory and the catalogue console
 * @param consoleHeight the height of both consoles, which stand level with the inventory image; a window too short for it hides them
 * @param casing the casing and its hardware
 * @param pad pixels between the glass and what is drawn on it, left and right
 * @param gridRightPad pixels the item grid keeps clear of the glass's right edge
 * @param contentBottom pixels the content keeps clear of the glass's bottom edge
 * @param titleTop pixels from the glass's top to a panel's title
 * @param titleGap pixels from the title's rule to what follows
 * @param searchHeight the search field's height
 * @param searchPad pixels between the search field's border and its text
 * @param searchGap pixels from the search field to the item grid
 * @param gridCell the pitch of the catalogue's cells
 * @param gridColumns the console catalogue's columns at full width, fewer when the panel is narrower
 * @param gridRows the console catalogue's rows
 * @param gridGap pixels from the item grid to the favourites label
 * @param labelHeight the height of a dim label with the row of cells under it
 * @param slotSize the side of one cell's outline
 * @param iconInset pixels from a cell's outline to its item
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
 * @param tableIconSize the side of the small crafting-table icon that marks an item or a recipe made only at the table
 * @param sheetWidth the width of a spec sheet's console; a window narrower than it gets the window's width
 * @param sheetHeight the tallest a spec sheet's console gets; a window shorter than it gets the window's height
 * @param sheetMinHeight the shortest a spec sheet's console is: it is as tall as its content, and no shorter than this
 * @param sheetGap pixels between the blocks of a spec sheet: the item cell, what it is, and its ratings
 * @param sheetLineGap pixels between two lines of a spec sheet's text
 * @param sheetValueGap the least pixels between a rating's name and its figure
 * @param buttonWidth the width of the show/hide button, which takes the place of the vanilla recipe-book button
 * @param buttonHeight its height
 * @param buttonCell the side of one of the small cells drawn on the button's face
 * @param buttonCellGap pixels between those cells
 * @param buttonColumns how many columns of cells the button's face has
 * @param buttonRows how many rows
 * @param ghostColor the veil over a ghost item in the crafting grid, the recipe the player has all the ingredients of but has not filled in
 * @param missingColor the veil over a ghost item the player lacks, which marks that slot of the grid as missing
 */
public record CatalogueLook(
        int maxPanelWidth,
        int minPanelWidth,
        int screenEdge,
        int gapLeft,
        int gapRight,
        int consoleHeight,
        Casing casing,
        int pad,
        int gridRightPad,
        int contentBottom,
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
        int caretHeight,
        int tableIconSize,
        int sheetWidth,
        int sheetHeight,
        int sheetMinHeight,
        int sheetGap,
        int sheetLineGap,
        int sheetValueGap,
        int buttonWidth,
        int buttonHeight,
        int buttonCell,
        int buttonCellGap,
        int buttonColumns,
        int buttonRows,
        int ghostColor,
        int missingColor) {
    /** The star of a favourite, filled: {@code textures/gui/sprites/catalogue/star.png}, white, drawn a line high and tinted. */
    public static final Identifier STAR = Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "catalogue/star");
    /** The star of any other item, its outline only: {@code textures/gui/sprites/catalogue/star_hollow.png}. */
    public static final Identifier STAR_HOLLOW =
            Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "catalogue/star_hollow");

    /**
     * The casing round a glass and the hardware on it, drawn in the pack's metal greens.
     *
     * @param sideBezel pixels from a console's side to its glass
     * @param topBezel pixels from a unit's top to its glass
     * @param deckGap pixels from the glass to the first row of controls under it
     * @param deckRow the height of a row of controls
     * @param deckRowGap pixels between the two rows of a console's deck
     * @param bottomBezel pixels from the last row of controls to the unit's bottom
     * @param chamfer how many pixels the casing's corners are cut by
     * @param grooveInset pixels from the casing's edge to the groove cut round it
     * @param lipWidth the width of the shadowed lip round the glass
     * @param vignetteWidth the width of the dark ring the glass's bulge puts round its edge
     * @param highlightLength the length of the curved highlight in the glass's top left corner
     * @param rivetPitch the distance between the rivets down a console's sides
     * @param ventSlots the slots of a vent
     * @param knobSize the diameter of a knob
     * @param switchWidth the width of a toggle switch's slot
     * @param switchHeight its height
     * @param switchPad pixels from a plate's left edge to its switch
     * @param counterWidth the width of the page counter's plate
     * @param casingColor the casing's enamel
     * @param casingLightColor its lit top and left edges
     * @param casingDarkColor its shaded bottom and right edges
     * @param grooveColor the groove cut round the casing
     * @param plateColor the dark plates behind the switches and the counter
     * @param rivetColor a rivet's head
     * @param rivetShadowColor the shadow a rivet casts
     * @param lipDarkColor the lip's shadowed top and left
     * @param lipLightColor the lip's lit bottom and right
     * @param ventColor a vent's slot
     * @param ventLightColor the lit edge under a slot
     * @param knobRimColor a knob's rim
     * @param knobBodyColor a knob's face
     * @param knobOffColor a knob that does nothing now
     * @param knobLightColor a knob's lit edge
     * @param leverColor a toggle's lever
     * @param leverOffColor a lever of a switch that does nothing now
     * @param vignetteColor the glass's dark ring at its darkest
     * @param highlightColor the glass's curved highlight
     */
    public record Casing(
            int sideBezel,
            int topBezel,
            int deckGap,
            int deckRow,
            int deckRowGap,
            int bottomBezel,
            int chamfer,
            int grooveInset,
            int lipWidth,
            int vignetteWidth,
            int highlightLength,
            int rivetPitch,
            int ventSlots,
            int knobSize,
            int switchWidth,
            int switchHeight,
            int switchPad,
            int counterWidth,
            int casingColor,
            int casingLightColor,
            int casingDarkColor,
            int grooveColor,
            int plateColor,
            int rivetColor,
            int rivetShadowColor,
            int lipDarkColor,
            int lipLightColor,
            int ventColor,
            int ventLightColor,
            int knobRimColor,
            int knobBodyColor,
            int knobOffColor,
            int knobLightColor,
            int leverColor,
            int leverOffColor,
            int vignetteColor,
            int highlightColor) {
        static Casing of(ThemeData d) {
            return new Casing(
                    d.integer("sideBezel", 2, 64),
                    d.integer("topBezel", 2, 64),
                    d.integer("deckGap", 0, 32),
                    d.integer("deckRow", 10, 32),
                    d.integer("deckRowGap", 0, 32),
                    d.integer("bottomBezel", 2, 64),
                    d.integer("chamfer", 0, 8),
                    d.integer("grooveInset", 1, 16),
                    d.integer("lipWidth", 1, 8),
                    d.integer("vignetteWidth", 0, 16),
                    d.integer("highlightLength", 0, 64),
                    d.integer("rivetPitch", 8, 256),
                    d.integer("ventSlots", 1, 8),
                    d.integer("knobSize", 10, 32),
                    d.integer("switchWidth", 8, 48),
                    d.integer("switchHeight", 4, 16),
                    d.integer("switchPad", 0, 16),
                    d.integer("counterWidth", 16, 64),
                    d.color("casingColor"),
                    d.color("casingLightColor"),
                    d.color("casingDarkColor"),
                    d.color("grooveColor"),
                    d.color("plateColor"),
                    d.color("rivetColor"),
                    d.color("rivetShadowColor"),
                    d.color("lipDarkColor"),
                    d.color("lipLightColor"),
                    d.color("ventColor"),
                    d.color("ventLightColor"),
                    d.color("knobRimColor"),
                    d.color("knobBodyColor"),
                    d.color("knobOffColor"),
                    d.color("knobLightColor"),
                    d.color("leverColor"),
                    d.color("leverOffColor"),
                    d.color("vignetteColor"),
                    d.color("highlightColor"));
        }

        /** The rows one vent slot takes: two dark pixels and the lit one under them, the sprite the vents are drawn as. */
        public static final int VENT_PITCH = 3;

        /** The height of a vent: its slots, one pitch each. */
        public int ventHeight() {
            return VENT_PITCH * ventSlots;
        }

        /** What a console has under its glass: the gap, the two rows of controls with the gap between, and the bottom bezel. */
        public int consoleDeck() {
            return deckGap + 2 * deckRow + deckRowGap + bottomBezel;
        }
    }

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
                d.integer("consoleHeight", 40, 400),
                Casing.of(d),
                d.integer("pad", 0, 64),
                d.integer("gridRightPad", 0, 64),
                d.integer("contentBottom", 0, 64),
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
                d.integer("caretHeight", 1, 8),
                d.integer("tableIconSize", 4, 16),
                d.integer("sheetWidth", 40, 400),
                d.integer("sheetHeight", 40, 400),
                d.integer("sheetMinHeight", 40, 400),
                d.integer("sheetGap", 0, 32),
                d.integer("sheetLineGap", 0, 8),
                d.integer("sheetValueGap", 0, 32),
                d.integer("buttonWidth", 12, 64),
                d.integer("buttonHeight", 12, 64),
                d.integer("buttonCell", 1, 8),
                d.integer("buttonCellGap", 0, 4),
                d.integer("buttonColumns", 1, 6),
                d.integer("buttonRows", 1, 6),
                d.color("ghostColor"),
                d.color("missingColor"));
        if (look.minPanelWidth() > look.maxPanelWidth()) {
            throw d.conflict("minPanelWidth must not exceed maxPanelWidth", "minPanelWidth", "maxPanelWidth");
        }
        if (look.sheetMinHeight() > look.sheetHeight()) {
            throw d.conflict("sheetMinHeight must not exceed sheetHeight", "sheetMinHeight", "sheetHeight");
        }
        return look;
    }
}
