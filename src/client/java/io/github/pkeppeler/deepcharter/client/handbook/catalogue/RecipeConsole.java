package io.github.pkeppeler.deepcharter.client.handbook.catalogue;

import static io.github.pkeppeler.deepcharter.client.handbook.catalogue.CatalogueHardware.ICON;
import static io.github.pkeppeler.deepcharter.client.handbook.catalogue.CatalogueHardware.dim;
import static io.github.pkeppeler.deepcharter.client.handbook.catalogue.CatalogueHardware.fit;
import static io.github.pkeppeler.deepcharter.client.handbook.catalogue.CatalogueHardware.glow;
import static io.github.pkeppeler.deepcharter.client.handbook.catalogue.CatalogueHardware.termWidth;

import io.github.pkeppeler.deepcharter.client.handbook.catalogue.CatalogueLayout.Card;
import io.github.pkeppeler.deepcharter.client.handbook.catalogue.CatalogueLayout.Rect;
import io.github.pkeppeler.deepcharter.client.theme.CatalogueLook;
import io.github.pkeppeler.deepcharter.client.theme.PanelLook;
import io.github.pkeppeler.deepcharter.client.ui.CrtTuning;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;
import net.minecraft.util.context.ContextMap;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;
import org.jspecify.annotations.Nullable;

/**
 * The recipe console: the recipes or uses {@link CatalogueState} has looked up, the back knob and the SPEC SHEET switch on its deck, the
 * fill switch under them, and a star at the end of the title for the favourite of the item it is titled with. {@link CataloguePanel}
 * stands it beside the inventory and {@link RecipeConsoleScreen} puts it on a screen of its own over a container that has no catalogue;
 * both ask it where a thing is and what a click does, and it keeps what the charter knows and the player's favourites for both, so the
 * two behave alike.
 */
final class RecipeConsole {
    /** An item under the mouse and the cell it is in. */
    record Hit(Item item, Rect cell) {}

    /** What a click on the console's controls does. */
    enum Control {
        /** The back knob: the recipe looked at before. */
        BACK,
        /** The star: star the item the console is titled with, or take its star off. */
        STAR,
        /** The SPEC SHEET switch. */
        SPEC,
        /** The fill switch. */
        FILL,
        /** The title: put the console away. */
        TITLE,
        NONE
    }

    /** How the fill switch is drawn: its legend, whether its lever is thrown, and whether a press does anything. */
    record FillSwitch(String legend, boolean on, boolean active) {}

    private final CatalogueState state;
    private final Font font;
    private PartsCatalogue catalogue = PartsCatalogue.of(List.of(), ContextMap.EMPTY);
    /** The recipe book's collections {@link #catalogue} was built from: the book makes a new list when it changes. */
    private @Nullable List<RecipeCollection> builtFrom;

    private List<Item> favourites = List.of();

    RecipeConsole(CatalogueState state, Font font) {
        this.state = state;
        this.font = font;
    }

    /** Builds what the charter knows again when the recipe book changed, and reads the favourites again. Once a tick. */
    void refresh() {
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
        favourites = CatalogueFavourites.shared().items(player.getUUID());
    }

    /** What the charter knows, as of the last {@link #refresh}. */
    PartsCatalogue catalogue() {
        return catalogue;
    }

    /** The player's favourites, as of the last {@link #refresh} or star. */
    List<Item> favourites() {
        return favourites;
    }

    /** Stars {@code item}, or takes its star off: the favourite key on an item, and the star on what the console is titled with. */
    void toggleFavourite(Item item) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null) {
            CatalogueFavourites.shared().toggle(player.getUUID(), item);
            favourites = CatalogueFavourites.shared().items(player.getUUID());
        }
    }

    /** A click on the star: stars what the console is titled with, or takes its star off. */
    void toggleStar() {
        Item titled = titled();
        if (titled != null) {
            toggleFavourite(titled);
        }
    }

    /**
     * The item the console is titled with, whose favourite the star shows and toggles: what the shown recipe makes, or the item looked
     * up while no recipe is shown; null while nothing is looked up. In U the two differ: the uses of iron are titled with what they make.
     */
    @Nullable
    Item titled() {
        ItemStack titled = titledStack();
        return titled == null ? null : titled.getItem();
    }

    /** {@link #titled} as a stack, whose name the title shows: the shown recipe's own result, which may carry a name of its own. */
    private @Nullable ItemStack titledStack() {
        CatalogueState.Lookup lookup = state.lookup();
        if (lookup == null) {
            return null;
        }
        KnownRecipe recipe = state.shownRecipe(catalogue);
        return recipe != null ? recipe.results().getFirst() : new ItemStack(lookup.item());
    }

    /** The lines the title draws on {@code base}, short of the star, in capitals; none while nothing is looked up. */
    List<String> titleLines(Card base) {
        ItemStack titled = titledStack();
        return titled == null
                ? List.of()
                : TextWrap.lines(
                        text -> termWidth(font, text),
                        titled.getHoverName().getString().toUpperCase(Locale.ROOT),
                        base.titleRight() - base.left());
    }

    /** {@code base} moved down by each line the title takes beyond the first. */
    Card card(Card base) {
        int lines = titleLines(base).size();
        return lines <= 1
                ? base
                : base.lowered(
                        (lines - 1) * font.lineHeight, CatalogueLook.current().needRow());
    }

    /** Each item of the shown recipe and its cell: the grid, the result and the have/need rows; none while no recipe is shown. */
    List<Hit> hits(Card base) {
        KnownRecipe recipe = state.shownRecipe(catalogue);
        if (recipe == null) {
            return List.of();
        }
        CatalogueLook look = CatalogueLook.current();
        Card card = card(base);
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

    /** The item of the shown recipe at ({@code x}, {@code y}), or null. */
    @Nullable
    Hit hitAt(Card base, double x, double y) {
        for (Hit hit : hits(base)) {
            if (hit.cell().contains(x, y)) {
                return hit;
            }
        }
        return null;
    }

    /** The control at ({@code x}, {@code y}) on a console laid out as {@code base}. */
    Control controlAt(Card base, double x, double y) {
        if (state.lookup() == null) {
            return Control.NONE;
        }
        Rect title = new Rect(
                base.left(),
                base.titleY(),
                base.right() - base.left(),
                titleLines(base).size() * font.lineHeight);
        if (base.back().contains(x, y)) {
            return Control.BACK;
        } else if (base.star().contains(x, y)) {
            return Control.STAR;
        } else if (base.spec().contains(x, y)) {
            return Control.SPEC;
        } else if (base.fill().contains(x, y)) {
            return Control.FILL;
        } else if (title.contains(x, y)) {
            return Control.TITLE;
        }
        return Control.NONE;
    }

    /**
     * Draws the console in {@code casing} with its glass, laid out as {@code base}, with {@code hit} under the mouse at ({@code mouseX},
     * {@code mouseY}). {@code specActive} is whether the SPEC SHEET switch does anything here.
     */
    void draw(
            GuiGraphicsExtractor graphics,
            Rect casing,
            Rect glass,
            Card base,
            @Nullable Hit hit,
            int mouseX,
            int mouseY,
            boolean specActive,
            FillSwitch fill) {
        CatalogueState.Lookup lookup = state.lookup();
        if (lookup == null) {
            return;
        }
        CatalogueLook look = CatalogueLook.current();
        CrtTuning crt = CrtTuning.current();
        PanelLook panel = PanelLook.current();
        Card card = card(base);
        KnownRecipe recipe = state.shownRecipe(catalogue);
        Item titled = titled();
        boolean starred = titled != null && favourites.contains(titled);
        int width = card.right() - card.left();
        graphics.nextStratum();
        CatalogueHardware.body(graphics, look, casing, glass, List.of());
        graphics.nextStratum();

        CatalogueHardware.title(graphics, font, titleLines(base), card.left(), card.titleY(), card.right());
        Rect star = base.star();
        int size = Math.min(star.width(), star.height());
        CatalogueHardware.star(
                graphics,
                star.x() + (star.width() - size) / 2,
                star.y() + (star.height() - size) / 2,
                size,
                starred,
                starred || star.contains(mouseX, mouseY));
        if (recipe == null) {
            String none = text(lookup.mode() == CatalogueState.Mode.RECIPES ? "none.recipes" : "none.uses");
            dim(graphics, font, fit(font, none, width), card.left(), card.stationY());
        } else {
            dim(graphics, font, fit(font, recipe.station(), width), card.left(), card.stationY());
            drawRecipe(graphics, card, look, recipe, hit, crt);
        }

        graphics.nextStratum();
        CatalogueHardware.glassFront(graphics, look, panel, glass);
        graphics.nextStratum();
        CatalogueHardware.knob(
                graphics,
                font,
                look,
                panel,
                crt,
                base.back(),
                "<",
                state.canGoBack(),
                base.back().contains(mouseX, mouseY));
        Rect spec = base.spec();
        CatalogueHardware.legendSwitch(
                graphics,
                font,
                look,
                panel,
                crt,
                spec,
                text("spec"),
                false,
                specActive && recipe != null,
                recipe != null && spec.contains(mouseX, mouseY));
        CatalogueHardware.legendSwitch(
                graphics,
                font,
                look,
                panel,
                crt,
                base.fill(),
                fill.legend(),
                fill.on(),
                fill.active(),
                recipe != null && base.fill().contains(mouseX, mouseY));
    }

    private void drawRecipe(
            GuiGraphicsExtractor graphics,
            Card card,
            CatalogueLook look,
            KnownRecipe recipe,
            @Nullable Hit hit,
            CrtTuning crt) {
        for (int i = 0; i < recipe.cells().size(); i++) {
            Rect cell = recipeCell(card, look, recipe, i);
            boolean lit = hit != null && hit.cell().equals(cell);
            CatalogueHardware.slot(graphics, cell, lit, crt);
            ItemStack stack = cycled(recipe.cells().get(i));
            if (!stack.isEmpty()) {
                graphics.item(stack, cell.x() + look.iconInset(), cell.y() + look.iconInset());
            }
        }
        glow(graphics, font, "->", card.left() + look.arrowX(), card.gridY() + look.arrowY());
        Rect result = resultCell(card, look);
        CatalogueHardware.slot(graphics, result, true, crt);
        if (recipe.needsTable()) {
            // Over the result: made only in the crafting table's 3 x 3 grid.
            CatalogueHardware.tableIcon(
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
            dim(graphics, font, counter, card.right() - termWidth(font, counter), card.gridY() + look.counterY());
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
                glow(graphics, font, figures, x, y + look.needTextY());
            } else {
                dim(graphics, font, figures, x, y + look.needTextY());
            }
        }
        if (needs.size() > rows && rows > 0) {
            int y = card.needTop() + rows * look.needRow() - look.needRow();
            String more = "+" + (needs.size() - rows);
            dim(graphics, font, more, card.right() - termWidth(font, more), y + look.needTextY());
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

    private static Rect recipeCell(Card card, CatalogueLook look, KnownRecipe recipe, int index) {
        return new Rect(
                card.left() + (index % recipe.columns()) * look.recipeCell(),
                card.gridY() + (index / recipe.columns()) * look.recipeCell(),
                look.slotSize(),
                look.slotSize());
    }

    private static Rect resultCell(Card card, CatalogueLook look) {
        return new Rect(card.left() + look.resultX(), card.gridY() + look.resultY(), look.slotSize(), look.slotSize());
    }

    /** The item of a cell that takes several (a tag) which is shown now: each in turn, so the player sees what a tag allows. */
    static ItemStack cycled(List<ItemStack> choices) {
        if (choices.isEmpty()) {
            return ItemStack.EMPTY;
        }
        long step = Util.getMillis() / CatalogueLook.current().cycleMillis();
        return choices.get((int) (step % choices.size()));
    }

    /** The lines of the name strip of {@code item} on {@code glass}: its whole name, on as many lines as the glass needs. */
    static List<String> stripLines(Font font, Item item, Rect glass) {
        CatalogueLook look = CatalogueLook.current();
        String name = new ItemStack(item).getHoverName().getString().toUpperCase(Locale.ROOT);
        int inset = look.nameStripPad() + 1;
        return TextWrap.lines(text -> termWidth(font, text), name, glass.width() - 2 * look.pad() - 2 * inset);
    }

    /**
     * The name of the hovered item in a strip of its own, where the game's purple tooltip would clash with the console. Over the cell, or
     * under it when there is no room above, and kept inside {@code glass}, off its bezel. A name wider than the glass goes on a second
     * line, and a third: it is never cut.
     */
    static void nameStrip(GuiGraphicsExtractor graphics, Font font, Rect glass, Hit hit) {
        CatalogueLook look = CatalogueLook.current();
        List<String> lines = stripLines(font, hit.item(), glass);
        int inset = look.nameStripPad() + 1;
        int width = lines.stream().mapToInt(line -> termWidth(font, line)).max().orElse(0) + 2 * inset;
        int height = lines.size() * font.lineHeight + 2 * inset;
        int left = glass.x() + look.pad();
        int x = Math.clamp(hit.cell().x(), left, Math.max(left, glass.right() - look.pad() - width));
        int y = hit.cell().y() - look.nameStripGap() - height;
        if (y < glass.y()) {
            y = hit.cell().bottom() + look.nameStripGap();
        }
        CatalogueHardware.strip(graphics, font, lines, x, y, inset);
    }

    static String text(String key) {
        return Component.translatable("deepcharter.catalogue." + key).getString();
    }
}
