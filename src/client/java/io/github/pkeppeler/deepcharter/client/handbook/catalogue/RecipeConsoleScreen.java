package io.github.pkeppeler.deepcharter.client.handbook.catalogue;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.pkeppeler.deepcharter.client.theme.CatalogueLook;
import io.github.pkeppeler.deepcharter.client.ui.CrtTuning;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import org.jspecify.annotations.Nullable;

/**
 * The recipe console on a screen of its own, over a container screen that has no catalogue beside it (a chest, a barrel, the creative
 * inventory): where R and U on one of its slots show the item's recipes or uses ({@link SlotLookup}), as EMI's recipe screen does. It
 * moves the client's {@link CatalogueState}, so its history and the catalogue's are one. R, U and A on its cells, Backspace and the back
 * knob, M, the star and the wheel work as on the catalogue's console. Escape, the inventory key, the title and a click off the console go
 * back to the screen under it; a container stays open on the server the whole time. A click goes back on its release, which the console
 * keeps: the screen under it would take a release it never saw pressed as the end of a click, and drop or place a stack on the cursor.
 *
 * <p>There is no crafting grid here, so the fill switch says NOT HERE; the SPEC SHEET switch is dim, because a sheet opens only over the
 * catalogue or with no screen open ({@link SpecSheetOpener}).
 */
public final class RecipeConsoleScreen extends Screen {
    /** A press that asked to go back: off the console, or on its title, which puts the console away too. */
    private record BackOnRelease(int button, boolean putAway) {}

    private final @Nullable Screen parent;
    private final CatalogueState state;
    private final RecipeConsole console;
    private CatalogueLayout.@Nullable Console layout;
    /** The press waiting for its release to go back, or null. */
    private @Nullable BackOnRelease backOnRelease;

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
        console.refresh();
        CatalogueState.Lookup now = state.lookup();
        if (now == null || now.item() != item || now.mode() != mode) {
            state.look(console.catalogue(), item, mode);
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

    @Override
    public void tick() {
        console.refresh();
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
        if (now == null) {
            return;
        }
        RecipeConsole.Hit hit = console.hitAt(now.card(), mouseX, mouseY);
        console.draw(
                graphics,
                now.casing(),
                now.glass(),
                now.card(),
                hit,
                mouseX,
                mouseY,
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
        if (CatalogueKeys.MATERIALS.matches(event)) {
            state.toggleMaterials(console.catalogue());
            return true;
        }
        CatalogueLayout.Console now = layout;
        RecipeConsole.Hit hit = now == null ? null : console.hitAt(now.card(), mouseX, mouseY);
        if (hit != null && CatalogueKeys.RECIPES.matches(event)) {
            state.look(console.catalogue(), hit.item(), CatalogueState.Mode.RECIPES);
            return true;
        }
        if (hit != null && CatalogueKeys.USES.matches(event)) {
            state.look(console.catalogue(), hit.item(), CatalogueState.Mode.USES);
            return true;
        }
        if (hit != null && CatalogueKeys.FAVOURITE.matches(event)) {
            console.toggleFavourite(hit.item());
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        CatalogueLayout.Console now = layout;
        if (now == null || state.lookup() == null || !now.casing().contains(event.x(), event.y())) {
            backOnRelease = new BackOnRelease(event.button(), false);
            return true;
        }
        RecipeConsole.Hit hit = console.hitAt(now.card(), event.x(), event.y());
        if (hit != null) {
            state.look(
                    console.catalogue(),
                    hit.item(),
                    event.button() == InputConstants.MOUSE_BUTTON_RIGHT
                            ? CatalogueState.Mode.USES
                            : CatalogueState.Mode.RECIPES);
            return true;
        }
        switch (console.controlAt(now.card(), event.x(), event.y())) {
            case BACK -> state.back();
            case STAR -> console.toggleStar();
            case TITLE -> backOnRelease = new BackOnRelease(event.button(), true);
            // No grid to fill here, and no sheet to open over a screen that is not the catalogue's.
            case SPEC, FILL, NONE -> {}
        }
        return true;
    }

    /**
     * The release of a click that asked to go back: back to the screen under the console, on the title with the console put away. Every
     * release is the console's, so the screen under it never sees one.
     */
    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        BackOnRelease pressed = backOnRelease;
        if (pressed != null && pressed.button() == event.button()) {
            backOnRelease = null;
            if (pressed.putAway()) {
                state.clear();
            }
            onClose();
        }
        return true;
    }

    @Override
    public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        CatalogueLayout.Console now = layout;
        if (now != null && vertical != 0 && now.casing().contains(x, y)) {
            console.scroll(vertical > 0 ? -1 : 1);
            return true;
        }
        return false;
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
