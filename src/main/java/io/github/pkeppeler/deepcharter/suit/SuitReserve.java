package io.github.pkeppeler.deepcharter.suit;

import io.github.pkeppeler.deepcharter.attachment.Versioned;
import java.util.Optional;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * Adds to a person's reserve from outside the suit's own tick (#1194): what the air canister does. It never fills the suit past its own
 * capacity ({@link SuitTracks#capacity}), and what does not fit is lost.
 */
public final class SuitReserve {
    private SuitReserve() {}

    /**
     * Adds {@code seconds} of reserve to {@code player}'s suit, up to its capacity. Empty when it was added, or the refusal: nothing
     * changes when the suit or its tracks cannot be read, or the suit is already full. The mode is left for the next second to decide,
     * so a breached suit leaves its breach then.
     */
    public static Optional<Component> topUp(ServerPlayer player, float seconds) {
        Optional<SuitState> state = Versioned.readable(player, SuitRegistry.SUIT);
        Optional<SuitTracks> tracks = Versioned.readable(player, SuitRegistry.TRACKS);
        if (state.isEmpty() || tracks.isEmpty()) {
            return refusal("reserve_unreadable");
        }
        float capacity = tracks.get().capacity(SuitTuning.DEFAULT);
        float reserve = state.get().reserve();
        if (reserve >= capacity) {
            return refusal("full");
        }
        player.setAttached(
                SuitRegistry.SUIT, Versioned.of(state.get().withReserve(Math.min(capacity, reserve + seconds))));
        return Optional.empty();
    }

    private static Optional<Component> refusal(String reason) {
        return Optional.of(Component.translatable("message.deepcharter.suit." + reason));
    }
}
