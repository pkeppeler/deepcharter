package io.github.pkeppeler.deepcharter.repair;

import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.EventFactory;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/** Server-side events of the repair feature. */
public final class RepairEvents {
    /**
     * A player bought {@code item} at the repair station's shop, paid for it and was given it. Fires after the item is in their
     * inventory, from inside the buy action. A listener that throws stops the call: the purchase has taken effect, but the screen
     * is not refreshed and the {@code TerminalEvents.ACTED} listeners do not run, so listeners must not throw.
     */
    public static final Event<Bought> BOUGHT =
            EventFactory.createArrayBacked(Bought.class, listeners -> (server, player, item) -> {
                for (Bought listener : listeners) {
                    listener.onBought(server, player, item);
                }
            });

    private RepairEvents() {}

    @FunctionalInterface
    public interface Bought {
        void onBought(MinecraftServer server, ServerPlayer player, Consumable item);
    }
}
