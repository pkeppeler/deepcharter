package io.github.pkeppeler.deepcharter.client.pod;

import com.mojang.blaze3d.platform.InputConstants;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import net.minecraft.client.KeyMapping;

import io.github.pkeppeler.deepcharter.client.handbook.HandbookKeys;
import io.github.pkeppeler.deepcharter.pod.LiningPayload;
import io.github.pkeppeler.deepcharter.pod.PodEntity;

/** The key (R by default) with which the pilot lines the pod's slab with slag brick, or stops lining. The server decides what it does. */
public final class LiningKeys {
	public static final KeyMapping LINE = new KeyMapping("key.deepcharter.line_slab", InputConstants.Type.KEYBOARD, InputConstants.KEY_R,
			HandbookKeys.OPEN.getCategory());

	private LiningKeys() {
	}

	public static void register() {
		KeyMappingHelper.registerKeyMapping(LINE);
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (LINE.consumeClick()) {
				if (client.player != null && client.gui.screen() == null && client.player.getVehicle() instanceof PodEntity) {
					ClientPlayNetworking.send(new LiningPayload());
				}
			}
		});
	}
}
