package io.github.pkeppeler.deepcharter.client.handbook.catalogue;

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

/**
 * Opens spec sheets: the one a player asks for, and the one the server sends the first time they craft or buy an item
 * ({@link SpecSheetPayload}). Every request joins one queue, and the queue is drained in order whenever a sheet can be shown:
 * over the parts catalogue of the screen that is open (where the player is when they craft), on a screen of its own when nothing is
 * open ({@link SpecSheetScreen}), and never over anything else, so a sheet waits for a terminal to close and never takes its session
 * away. A sheet being shown is never replaced: the next waits until it is put away.
 *
 * <p>The queue is memory only. A sheet still waiting when the client disconnects is dropped: the server marked it shown, and the
 * player can look it up in the catalogue any time.
 */
public final class SpecSheetOpener {
    /** Sheets asked for and not yet shown, in the order they came. */
    private static final Deque<Item> WAITING = new ArrayDeque<>();

    private SpecSheetOpener() {}

    public static void register() {
        SpecSheetHook.set(SpecSheetOpener::show);
        ClientPlayNetworking.registerGlobalReceiver(
                SpecSheetPayload.TYPE,
                (payload, context) ->
                        BuiltInRegistries.ITEM.getOptional(payload.item()).ifPresent(SpecSheetHook::open));
        ClientTickEvents.END_CLIENT_TICK.register(SpecSheetOpener::drain);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> WAITING.clear());
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
