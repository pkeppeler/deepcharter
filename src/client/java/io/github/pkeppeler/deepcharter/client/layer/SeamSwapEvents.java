package io.github.pkeppeler.deepcharter.client.layer;

import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.EventFactory;
import net.minecraft.client.multiplayer.ClientLevel;

/**
 * Client events of the seamless swap (#1229, ADR 0089). Fabric's {@code ClientLevelEvents.AFTER_CLIENT_LEVEL_CHANGE}
 * fires for a swap too, but it does not say the level changed with the player, the vehicle and the sounds kept, which a
 * listener that holds state per level needs to know.
 */
public final class SeamSwapEvents {
    /**
     * The client swapped from one level to the next, keeping its player, the vehicle it rides and everything playing.
     */
    public static final Event<Swapped> SWAPPED =
            EventFactory.createArrayBacked(Swapped.class, listeners -> (from, to) -> {
                for (Swapped listener : listeners) {
                    listener.onSwapped(from, to);
                }
            });

    private SeamSwapEvents() {}

    @FunctionalInterface
    public interface Swapped {
        void onSwapped(ClientLevel from, ClientLevel to);
    }
}
