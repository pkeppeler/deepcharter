package io.github.pkeppeler.deepcharter.client.pod;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.pkeppeler.deepcharter.client.handbook.HandbookKeys;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodScreenPayload;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.KeyMapping;

/**
 * The key (I by default, free in vanilla and in the mod) that opens the pod screen from a seat, for anyone aboard. The
 * server decides whether it opens ({@link io.github.pkeppeler.deepcharter.pod.PodMenu#open}). From outside, sneak and
 * use opens it.
 */
public final class PodScreenKeys {
    public static final KeyMapping OPEN = new KeyMapping(
            "key.deepcharter.pod_screen",
            InputConstants.Type.KEYBOARD,
            InputConstants.KEY_I,
            HandbookKeys.OPEN.getCategory());

    private PodScreenKeys() {}

    public static void register() {
        KeyMappingHelper.registerKeyMapping(OPEN);
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (OPEN.consumeClick()) {
                if (client.player != null
                        && client.gui.screen() == null
                        && client.player.getVehicle() instanceof PodEntity) {
                    ClientPlayNetworking.send(new PodScreenPayload());
                }
            }
        });
    }
}
