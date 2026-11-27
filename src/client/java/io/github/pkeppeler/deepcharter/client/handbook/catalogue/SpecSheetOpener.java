package io.github.pkeppeler.deepcharter.client.handbook.catalogue;

import io.github.pkeppeler.deepcharter.client.handbook.StepToast;
import io.github.pkeppeler.deepcharter.handbook.SpecSheetPayload;
import java.util.ArrayDeque;
import java.util.Deque;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * Opens spec sheets: the one a player asks for, and the latest the server offered the first time they craft or buy an
 * item ({@link SpecSheetPayload}). An offer opens nothing: it shows a corner slip with the item's name and the key
 * ({@link CatalogueKeys#SPEC_SHEET}) that opens its sheet, and remembers the item. Every request joins one queue, and
 * the queue is drained in order whenever a sheet can be shown: over the parts catalogue of the screen that is open
 * (where the player is when they craft), on a screen of its own when nothing is open ({@link SpecSheetScreen}), and
 * never over anything else, so a sheet waits for a terminal to close and never takes its session away. A sheet being
 * shown is never replaced: the next waits until it is put away.
 *
 * <p>The queue and the latest offer are memory only, and a disconnect drops both: the player can look a sheet up in the
 * catalogue any time.
 */
public final class SpecSheetOpener {
    /** Sheets asked for and not yet shown, in the order they came. */
    private static final Deque<Item> WAITING = new ArrayDeque<>();

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
                show(item);
            }
        }
        drain(client);
    }

    /** Whether the sheet of {@code item} is queued or already shown, so a key tapped again asks for it twice. */
    private static boolean onItsWay(Minecraft client, Item item) {
        if (WAITING.contains(item)) {
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
        WAITING.add(item);
        drain(Minecraft.getInstance());
    }

    /** Shows the sheet at the head of the queue if it can be shown now. Called on each client tick and when a sheet is put away. */
    static void drain(Minecraft client) {
        if (WAITING.isEmpty() || client.player == null) {
            return;
        }
        Screen open = client.gui.screen();
        if (open == null) {
            client.gui.setScreen(new SpecSheetScreen(WAITING.remove()));
            return;
        }
        CataloguePanel panel = CatalogueScreens.panelOf(open);
        if (panel != null && panel.layout() != null && panel.sheetItem() == null) {
            panel.showSheet(WAITING.remove());
        }
    }
}
