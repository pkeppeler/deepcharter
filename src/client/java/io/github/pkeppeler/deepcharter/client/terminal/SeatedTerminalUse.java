package io.github.pkeppeler.deepcharter.client.terminal;

import io.github.pkeppeler.deepcharter.terminal.SeatedTerminalPayload;
import io.github.pkeppeler.deepcharter.terminal.SeatedTerminals;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.world.InteractionResult;

/**
 * The seated way into a terminal (#1447). A seated player's crosshair always picks their own pod (its box holds their
 * eyes), so a use from the seat never reaches a terminal block. An empty-hand use of that pod asks the server for the
 * terminal they face instead ({@link SeatedTerminals}). It answers {@code FAIL}, so the use sends no interaction and
 * swings no arm.
 */
final class SeatedTerminalUse {
    private SeatedTerminalUse() {}

    static void init() {
        UseEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
            // The event fires on the integrated server too, where the request is not the client's to send.
            if (!level.isClientSide() || !SeatedTerminals.wantsTerminal(player, hand, entity)) {
                return InteractionResult.PASS;
            }
            ClientPlayNetworking.send(SeatedTerminalPayload.INSTANCE);
            return InteractionResult.FAIL;
        });
    }
}
