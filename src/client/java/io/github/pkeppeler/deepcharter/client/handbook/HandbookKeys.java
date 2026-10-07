package io.github.pkeppeler.deepcharter.client.handbook;

import com.mojang.blaze3d.platform.InputConstants;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.event.player.UseItemCallback;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionResult;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.handbook.HandbookItems;

/** What opens the handbook screen: the key (H by default) and use of the handbook item. */
public final class HandbookKeys {
	public static final KeyMapping OPEN = new KeyMapping("key.deepcharter.handbook", InputConstants.Type.KEYBOARD, InputConstants.KEY_H,
			KeyMapping.Category.register(Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "main")));

	private HandbookKeys() {
	}

	public static void register() {
		KeyMappingHelper.registerKeyMapping(OPEN);
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (OPEN.consumeClick()) {
				if (client.player != null && client.gui.screen() == null) {
					HandbookScreen.open(client);
				}
			}
		});
		UseItemCallback.EVENT.register((player, level, hand) -> {
			if (level.isClientSide() && HandbookItems.isHandbook(player.getItemInHand(hand))) {
				HandbookScreen.open(Minecraft.getInstance());
				return InteractionResult.SUCCESS;
			}
			return InteractionResult.PASS;
		});
	}
}
