package io.github.pkeppeler.deepcharter.client.transmission;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.pkeppeler.deepcharter.client.handbook.HandbookKeys;
import io.github.pkeppeler.deepcharter.transmission.TransmissionLogPayload;
import io.github.pkeppeler.deepcharter.transmission.TransmissionLogRequest;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;

/**
 * Opens the comms log (#1615): K by default, from the world with no screen open, seated or on foot. The key asks the
 * server for the player's log, and the answer opens {@link TransmissionLogScreen}. When it cannot (a screen is open,
 * or the server does not run the mod), the action bar says so.
 */
public final class TransmissionLogKeys {
    public static final KeyMapping OPEN = new KeyMapping(
            "key.deepcharter.transmission_log",
            InputConstants.Type.KEYBOARD,
            InputConstants.KEY_K,
            HandbookKeys.OPEN.getCategory());

    /** The refusal while another screen is open, said in the action bar rather than dropped. */
    private static final String SCREEN_OPEN = "deepcharter.transmission.log_screen_open";

    private TransmissionLogKeys() {}

    static void register() {
        KeyMappingHelper.registerKeyMapping(OPEN);
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (OPEN.consumeClick()) {
                LocalPlayer player = client.player;
                if (player == null) {
                    continue;
                }
                if (client.gui.screen() != null) {
                    player.sendOverlayMessage(Component.translatable(SCREEN_OPEN));
                } else if (!ClientPlayNetworking.canSend(TransmissionLogRequest.TYPE)) {
                    player.sendOverlayMessage(Component.translatable("deepcharter.transmission.log_unavailable"));
                } else {
                    ClientPlayNetworking.send(new TransmissionLogRequest());
                }
            }
        });
        ClientPlayNetworking.registerGlobalReceiver(TransmissionLogPayload.TYPE, (payload, context) -> {
            if (context.client().gui.screen() == null) {
                context.client().gui.setScreen(new TransmissionLogScreen(payload));
            } else {
                // A screen opened while the answer was on its way: it stays, and the player is told why no log came.
                context.player().sendOverlayMessage(Component.translatable(SCREEN_OPEN));
            }
        });
    }
}
