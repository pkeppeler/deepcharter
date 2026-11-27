package io.github.pkeppeler.deepcharter.suit;

import io.github.pkeppeler.deepcharter.attachment.Versioned;
import io.github.pkeppeler.deepcharter.suit.SuitState.Mode;
import java.util.Optional;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/** Adds to a person's reserve outside the suit's tick (#1194): the air canister. */
public final class SuitReserve {
    private SuitReserve() {}

    /**
     * Adds {@code seconds} to {@code player}'s reserve, up to the suit's capacity; empty when it did, else the refusal.
     * A suit that is already refilling (a powered seat or a sound hall) refuses it, so the canister is not wasted.
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
        if (SuitTicker.modeNow(player, tracks.get()).refills()) {
            return refusal("refilling");
        }
        SuitState filled = state.get().withReserve(Math.min(capacity, reserve + seconds));
        // The breach hit reads the stored mode, so a breached suit leaves BREACHED now, not at the next second.
        player.setAttached(SuitRegistry.SUIT, Versioned.of(filled.mode() == Mode.BREACHED ? filled.exposed() : filled));
        return Optional.empty();
    }

    private static Optional<Component> refusal(String reason) {
        return Optional.of(Component.translatable("message.deepcharter.suit." + reason));
    }
}
