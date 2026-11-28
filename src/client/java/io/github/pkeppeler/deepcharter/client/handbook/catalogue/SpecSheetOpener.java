package io.github.pkeppeler.deepcharter.client.handbook.catalogue;

import io.github.pkeppeler.deepcharter.client.handbook.StepToast;
import io.github.pkeppeler.deepcharter.client.ui.HoveredItemScreen;
import io.github.pkeppeler.deepcharter.handbook.SpecSheetPayload;
import java.util.ArrayDeque;
import java.util.Deque;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * Opens spec sheets: the one a player asks for, and the latest the server offered the first time they craft or buy an
 * item ({@link SpecSheetPayload}). An offer opens nothing: it shows a corner slip with the item's name and the key
 * ({@link CatalogueKeys#SPEC_SHEET}) that opens its sheet, and remembers the item. In a screen that shows items, the
 * key opens the sheet of the item under the cursor, and with none under it the latest offered (#1651): over the parts
 * catalogue where there is one, else on a sheet screen over the screen, which comes back when it closes. Every request
 * joins one queue, and the queue is drained in order whenever a sheet can be shown: over the parts catalogue of the
 * screen that is open (where the player is when they craft), on a screen of its own when nothing is open
 * ({@link SpecSheetScreen}), and never over anything else, so a sheet waits for a terminal to close and never takes its
 * session away. A sheet being shown is never replaced: the next waits until it is put away.
 *
 * <p>The queue and the latest offer are memory only, and a disconnect drops both: the player can look a sheet up in the
 * catalogue any time.
 */
public final class SpecSheetOpener {
    /** Sheets asked for and not yet shown, in the order they came. */
    private static final Deque<SheetRequest> WAITING = new ArrayDeque<>();

    /** The sheet most recently offered, which the key opens; null before one was. */
    private static @Nullable Item latest;

    private SpecSheetOpener() {}

    public static void register() {
        SpecSheetHook.set(SpecSheetOpener::show);
        ClientPlayNetworking.registerGlobalReceiver(
                SpecSheetPayload.TYPE,
                (payload, context) ->
                        BuiltInRegistries.ITEM.getOptional(payload.item()).ifPresent(SpecSheetOpener::offered));
        ClientTickEvents.END_CLIENT_TICK.register(SpecSheetOpener::tick);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            WAITING.clear();
            latest = null;
        });
    }

    /** Shows the slip of a sheet the server offered and remembers it for the key. */
    private static void offered(Item item) {
        latest = item;
        Minecraft.getInstance()
                .gui
                .toastManager()
                .addToast(StepToast.specSlip(
                        new ItemStack(item).getHoverName(), CatalogueKeys.SPEC_SHEET.getTranslatedKeyMessage()));
    }

    private static void tick(Minecraft client) {
        while (CatalogueKeys.SPEC_SHEET.consumeClick()) {
            Item item = latest;
            if (item != null && client.player != null && !onItsWay(client, item)) {
                // With no screen open the key reads as held for as long as it is down.
                queue(new SheetRequest(item, true, CatalogueKeys.SPEC_SHEET.isDown()));
            }
        }
        drain(client);
    }

    /**
     * Lets the spec sheet key act on the item under the cursor in {@code screen}, when it is a screen that shows items.
     * Called on each init, after the catalogue's own keys are attached, so a key the catalogue takes (its search, an
     * open sheet) never gets here.
     */
    static void attach(Screen screen) {
        if (screen instanceof AbstractContainerScreen<?>
                || screen instanceof RecipeConsoleScreen
                || screen instanceof HoveredItemScreen) {
            ScreenKeyboardEvents.allowKeyPress(screen).register((s, event) -> !keyPressed(s, event));
        }
    }

    /**
     * The spec sheet key in {@code screen}, before the screen sees it: the sheet of the item under the cursor; nothing
     * over an empty slot; the latest sheet offered, marked LATEST, when the cursor is on no slot and no item. Returns
     * whether it opened, or had already opened, a sheet.
     */
    private static boolean keyPressed(Screen screen, KeyEvent event) {
        Minecraft client = Minecraft.getInstance();
        if (!CatalogueKeys.SPEC_SHEET.matches(event) || typing(screen) || client.player == null) {
            return false;
        }
        Item hovered = hoveredIn(screen);
        if (hovered == null && onEmptySlot(screen)) {
            return false;
        }
        Item item = hovered == null ? latest : hovered;
        if (item == null) {
            return false;
        }
        if (onItsWay(client, item)) {
            return true;
        }
        SheetRequest request = new SheetRequest(item, hovered == null, true);
        CataloguePanel panel = CatalogueScreens.panelOf(screen);
        if (panel != null && panel.layout() != null) {
            queue(request);
        } else {
            client.gui.setScreen(new SpecSheetScreen(screen, request));
        }
        return true;
    }

    /** Whether the cursor is on a slot with nothing in it, where the key opens nothing rather than an unrelated sheet. */
    private static boolean onEmptySlot(Screen screen) {
        // The screen sets it each frame from the mouse; it is public through deepcharter.classtweaker.
        return screen instanceof AbstractContainerScreen<?> container
                && container.hoveredSlot != null
                && !container.hoveredSlot.hasItem();
    }

    /** Whether a text field has the keyboard, so the key is a letter typed. */
    private static boolean typing(Screen screen) {
        CataloguePanel panel = CatalogueScreens.panelOf(screen);
        return screen.getFocused() instanceof EditBox
                || (screen instanceof AbstractContainerScreen<?> container && container.isInputCaptured())
                || (panel != null && panel.searchFocused());
    }

    /** The item under the cursor in {@code screen}: a catalogue cell, a slot, a console's item or a terminal row. */
    private static @Nullable Item hoveredIn(Screen screen) {
        CataloguePanel panel = CatalogueScreens.panelOf(screen);
        Item inPanel = panel == null ? null : panel.hoveredItem();
        if (inPanel != null) {
            return inPanel;
        }
        return switch (screen) {
            case RecipeConsoleScreen console -> console.hoveredItem();
            case HoveredItemScreen rows -> rows.hoveredItem();
            // The screen sets it each frame from the mouse; it is public through deepcharter.classtweaker.
            case AbstractContainerScreen<?> container ->
                container.hoveredSlot != null && container.hoveredSlot.hasItem()
                        ? container.hoveredSlot.getItem().getItem()
                        : null;
            default -> null;
        };
    }

    /** Whether the sheet of {@code item} is queued or already shown, so a key tapped again asks for it twice. */
    private static boolean onItsWay(Minecraft client, Item item) {
        if (WAITING.stream().anyMatch(waiting -> waiting.item() == item)) {
            return true;
        }
        Screen open = client.gui.screen();
        if (open instanceof SpecSheetScreen screen) {
            return screen.item() == item;
        }
        CataloguePanel panel = open == null ? null : CatalogueScreens.panelOf(open);
        return panel != null && panel.sheetItem() == item;
    }

    /** Queues the sheet of {@code item} and shows it at once if nothing is in its way. */
    public static void show(Item item) {
        queue(SheetRequest.of(item));
    }

    private static void queue(SheetRequest request) {
        WAITING.add(request);
        drain(Minecraft.getInstance());
    }

    /** Shows the sheet at the head of the queue if it can be shown now. Called on each client tick and when a sheet is put away. */
    static void drain(Minecraft client) {
        if (WAITING.isEmpty() || client.player == null) {
            return;
        }
        Screen open = client.gui.screen();
        if (open == null) {
            client.gui.setScreen(new SpecSheetScreen(null, WAITING.remove()));
            return;
        }
        CataloguePanel panel = CatalogueScreens.panelOf(open);
        if (panel != null && panel.layout() != null && panel.sheetItem() == null) {
            panel.showSheet(WAITING.remove());
        }
    }
}
