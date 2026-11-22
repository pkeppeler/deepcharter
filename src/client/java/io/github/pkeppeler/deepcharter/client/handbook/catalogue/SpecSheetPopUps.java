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
 * Shows spec sheets: the one a player asks for, and the one the server sends the first time they craft or buy an item
 * ({@link SpecSheetPayload}). A sheet opens over the parts catalogue of the screen that is open, which is where the player is when
 * they craft, in the recipe console's place; with no screen open it gets a screen of its own ({@link SpecSheetScreen}); and over any other
 * screen (a terminal where they bought a part, a window too small for the catalogue) it waits until that screen has closed, so it
 * never takes a terminal's session away.
 */
public final class SpecSheetPopUps {
    /** Sheets asked for while a screen that cannot hold one was open, in the order they came. */
    private static final Deque<Item> WAITING = new ArrayDeque<>();

    private SpecSheetPopUps() {}

    public static void register() {
        SpecSheetHook.set(SpecSheetPopUps::show);
        ClientPlayNetworking.registerGlobalReceiver(
                SpecSheetPayload.TYPE,
                (payload, context) ->
                        BuiltInRegistries.ITEM.getOptional(payload.item()).ifPresent(SpecSheetHook::open));
        ClientTickEvents.END_CLIENT_TICK.register(SpecSheetPopUps::showWaiting);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> WAITING.clear());
    }

    /** Opens the sheet of {@code item} where it can be held now, or when the open screen closes. */
    public static void show(Item item) {
        Minecraft client = Minecraft.getInstance();
        Screen open = client.gui.screen();
        if (open == null) {
            client.gui.setScreen(new SpecSheetScreen(item));
            return;
        }
        CataloguePanel panel = CatalogueScreens.panelOf(open);
        if (panel != null && panel.layout() != null) {
            panel.showSheet(item);
        } else {
            WAITING.add(item);
        }
    }

    private static void showWaiting(Minecraft client) {
        if (client.player == null || client.gui.screen() != null) {
            return;
        }
        Item next = WAITING.poll();
        if (next != null) {
            client.gui.setScreen(new SpecSheetScreen(next));
        }
    }
}
