package io.github.pkeppeler.deepcharter.client.transmission;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.pkeppeler.deepcharter.client.handbook.HandbookKeys;
import io.github.pkeppeler.deepcharter.transmission.TransmissionLogPayload;
import io.github.pkeppeler.deepcharter.transmission.TransmissionLogRequest;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.KeyMapping;

/**
 * Opens the comms log (#1615): J by default, from the world with no screen open, seated or on foot. The key asks the
 * server for the player's log, and the answer opens {@link TransmissionLogScreen} unless a screen has opened meanwhile.
 */
public final class TransmissionLogKeys {
    public static final KeyMapping OPEN = new KeyMapping(
            "key.deepcharter.transmission_log",
            InputConstants.Type.KEYBOARD,
            InputConstants.KEY_J,
            HandbookKeys.OPEN.getCategory());

    private TransmissionLogKeys() {}

    static void register() {
        KeyMappingHelper.registerKeyMapping(OPEN);
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (OPEN.consumeClick()) {
                if (client.player != null
                        && client.gui.screen() == null
                        && ClientPlayNetworking.canSend(TransmissionLogRequest.TYPE)) {
                    ClientPlayNetworking.send(new TransmissionLogRequest());
                }
            }
        });
        ClientPlayNetworking.registerGlobalReceiver(TransmissionLogPayload.TYPE, (payload, context) -> {
            if (context.client().gui.screen() == null) {
                context.client().gui.setScreen(new TransmissionLogScreen(payload));
            }
        });
    }
}
