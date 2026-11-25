package io.github.pkeppeler.deepcharter.client.handbook.catalogue;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.pkeppeler.deepcharter.client.theme.CatalogueLook;
import io.github.pkeppeler.deepcharter.client.ui.CrtTuning;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.util.context.ContextMap;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;
import org.jspecify.annotations.Nullable;

/**
 * The recipe console on a screen of its own, over a container screen that has no catalogue beside it (a chest, a barrel, the creative
 * inventory): where R and U on one of its slots show the item's recipes or uses ({@link SlotLookup}), as EMI's recipe screen does. It
 * moves the client's {@link CatalogueState}, so its history and the catalogue's are one. R, U and A on its cells, Backspace and the back
 * knob, the star and the wheel work as on the catalogue's console. Escape, the inventory key, the title and a click off the console go
 * back to the screen under it; a container stays open on the server the whole time.
 *
 * <p>There is no crafting grid here, so the fill switch says NOT HERE; the SPEC SHEET switch is dim, because a sheet opens only over the
 * catalogue or with no screen open ({@link SpecSheetOpener}).
 */
public final class RecipeConsoleScreen extends Screen {
    private final @Nullable Screen parent;
    private final CatalogueState state;
    private final RecipeConsole console;
    private PartsCatalogue catalogue = PartsCatalogue.of(List.of(), ContextMap.EMPTY);
    private @Nullable List<RecipeCollection> builtFrom;
    private List<Item> favourites = List.of();
    private CatalogueLayout.@Nullable Console layout;
    private int mouseX = -1;
    private int mouseY = -1;

    /**
     * The console showing the recipes (or uses) of {@code item}, over {@code parent}, which comes back when it closes (none: back to the
     * game). The item already shown in that mode stays at the recipe it showed.
     */
    public RecipeConsoleScreen(@Nullable Screen parent, Item item, CatalogueState.Mode mode) {
        super(Component.translatable("deepcharter.catalogue.title"));
        this.parent = parent;
        this.state = CatalogueScreens.state();
        this.console = new RecipeConsole(state, Minecraft.getInstance().font);
        refresh();
        CatalogueState.Lookup now = state.lookup();
        if (now == null || now.item() != item || now.mode() != mode) {
            state.look(catalogue, item, mode);
        }
    }

    /** Whether a screen {@code width} by {@code height} has room for the console. */
    static boolean fits(int width, int height) {
        return centred(width, height) != null;
    }

    private static CatalogueLayout.@Nullable Console centred(int width, int height) {
        return CatalogueLayout.centred(
                width, height, CatalogueLook.current(), CrtTuning.current(), Minecraft.getInstance().font.lineHeight);
    }

    @Override
    protected void init() {
        layout = centred(width, height);
    }

    /** Where the console stands; null before the screen has opened, or in a window too small for it. */
    // test-seam: the client tests click the back knob and the star of this console
    public CatalogueLayout.@Nullable Console console() {
        return layout;
    }

    /** Builds what the charter knows again when the recipe book changed, and reads the favourites. */
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
        favourites = CatalogueFavourites.shared().items(player.getUUID());
    }

    @Override
    public void tick() {
        refresh();
        // Nothing to show (the world was left, or the window shrank past the console): back to the screen under it.
        if (state.lookup() == null || layout == null) {
            onClose();
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        this.mouseX = mouseX;
        this.mouseY = mouseY;
        CatalogueLayout.Console now = layout;
        CatalogueState.Lookup lookup = state.lookup();
        if (now == null || lookup == null) {
            return;
        }
        RecipeConsole.Hit hit = console.hitAt(catalogue, now.card(), mouseX, mouseY);
        console.draw(
                graphics,
                catalogue,
                now.casing(),
                now.glass(),
                now.card(),
                hit,
                mouseX,
                mouseY,
                favourites.contains(lookup.item()),
                false,
                new RecipeConsole.FillSwitch(RecipeConsole.text("fill.here"), false, false));
        if (hit != null) {
            RecipeConsole.nameStrip(graphics, font, now.glass(), hit);
        }
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.isEscape() || Minecraft.getInstance().options.keyInventory.matches(event)) {
            onClose();
            return true;
        }
        if (CatalogueKeys.BACK.matches(event)) {
            state.back();
            return true;
        }
        CatalogueLayout.Console now = layout;
        RecipeConsole.Hit hit = now == null ? null : console.hitAt(catalogue, now.card(), mouseX, mouseY);
        if (hit != null && CatalogueKeys.RECIPES.matches(event)) {
            state.look(catalogue, hit.item(), CatalogueState.Mode.RECIPES);
            return true;
        }
        if (hit != null && CatalogueKeys.USES.matches(event)) {
            state.look(catalogue, hit.item(), CatalogueState.Mode.USES);
            return true;
        }
        if (hit != null && CatalogueKeys.FAVOURITE.matches(event)) {
            toggleFavourite(hit.item());
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        CatalogueLayout.Console now = layout;
        CatalogueState.Lookup lookup = state.lookup();
        if (now == null || lookup == null || !now.casing().contains(event.x(), event.y())) {
            onClose();
            return true;
        }
        RecipeConsole.Hit hit = console.hitAt(catalogue, now.card(), event.x(), event.y());
        if (hit != null) {
            state.look(
                    catalogue,
                    hit.item(),
                    event.button() == InputConstants.MOUSE_BUTTON_RIGHT
                            ? CatalogueState.Mode.USES
                            : CatalogueState.Mode.RECIPES);
            return true;
        }
        switch (console.controlAt(catalogue, now.card(), event.x(), event.y())) {
            case BACK -> state.back();
            case STAR -> toggleFavourite(lookup.item());
            case TITLE -> {
                state.clear();
                onClose();
            }
            // No grid to fill here, and no sheet to open over a screen that is not the catalogue's.
            case SPEC, FILL, NONE -> {}
        }
        return true;
    }

    @Override
    public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        CatalogueLayout.Console now = layout;
        if (now != null && vertical != 0 && now.casing().contains(x, y)) {
            state.step(catalogue, vertical > 0 ? -1 : 1);
            return true;
        }
        return false;
    }

    private void toggleFavourite(Item item) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null) {
            CatalogueFavourites.shared().toggle(player.getUUID(), item);
            favourites = CatalogueFavourites.shared().items(player.getUUID());
        }
    }

    /** Back to the screen the console was opened over; it is not closed, so a container stays open on the server. */
    @Override
    public void onClose() {
        Minecraft.getInstance().gui.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
