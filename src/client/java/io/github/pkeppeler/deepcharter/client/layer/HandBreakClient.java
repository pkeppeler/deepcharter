package io.github.pkeppeler.deepcharter.client.layer;

import io.github.pkeppeler.deepcharter.layer.HandBreak;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;

/**
 * The client's half of the swing rule for pod-only blocks (ADR 0069): a swing with an item that is no tool (a bare
 * hand) is refused here, so no crack starts, and since the server never hears of it the client says so itself, once a
 * session. A tool goes on to the server, which tells it the first-try line.
 */
final class HandBreakClient {
    /**
     * Whether this session has said an item that is no tool cannot do it. Client thread only; a disconnect starts the
     * session again.
     */
    private static boolean toldCannotBite;

    private HandBreakClient() {}

    static void init() {
        AttackBlockCallback.EVENT.register((player, level, hand, pos, direction) -> {
            // The event is shared with an integrated server, which runs its own swings through it.
            if (!level.isClientSide()
                    || !HandBreak.governs(player, level, pos, level.getBlockState(pos))
                    || HandBreak.isTool(player.getMainHandItem())) {
                return InteractionResult.PASS;
            }
            if (!toldCannotBite) {
                toldCannotBite = true;
                player.sendSystemMessage(Component.translatable(HandBreak.CANNOT_BITE_KEY));
            }
            return InteractionResult.FAIL;
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> toldCannotBite = false);
    }
}
