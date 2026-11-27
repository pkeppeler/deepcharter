package io.github.pkeppeler.deepcharter.client.handbook;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.handbook.HandbookItems;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.advancements.AdvancementsScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionResult;
import org.jspecify.annotations.Nullable;

/**
 * What opens the handbook screen: the key (H by default), use of the handbook item, and anything that would open vanilla's advancements
 * screen, which the handbook replaces: the advancements key (L) and the pause menu's Advancements button (ADR 0067).
 */
public final class HandbookKeys {
    public static final KeyMapping OPEN = new KeyMapping(
            "key.deepcharter.handbook",
            InputConstants.Type.KEYBOARD,
            InputConstants.KEY_H,
            KeyMapping.Category.register(Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "main")));

    private static @Nullable Screen closedPauseScreen;

    private HandbookKeys() {}

    /** The pause menu's Advancements button opens the handbook, so it says so. */
    private static void relabelAdvancementsButton(Screen pauseScreen) {
        for (AbstractWidget widget : Screens.getWidgets(pauseScreen)) {
            if (widget instanceof Button button
                    && button.getMessage().getContents() instanceof TranslatableContents contents
                    && contents.getKey().equals("gui.advancements")) {
                button.setMessage(Component.translatable("deepcharter.handbook.pause_button"));
            }
        }
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
        // The pause menu closes into the advancements screen within one call, so the pause screen it removed is the one
        // to return to.
        // A pause screen closed any other way is forgotten at the next tick, before the advancements key can read it.
        ClientTickEvents.START_CLIENT_TICK.register(client -> closedPauseScreen = null);
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            if (screen instanceof PauseScreen) {
                relabelAdvancementsButton(screen);
                ScreenEvents.remove(screen).register(removed -> closedPauseScreen = removed);
            } else if (screen instanceof AdvancementsScreen) {
                Screen parent = closedPauseScreen;
                closedPauseScreen = null;
                HandbookScreen.open(client, parent);
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
