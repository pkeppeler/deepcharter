package io.github.pkeppeler.deepcharter.client.handbook;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.handbook.HandbookItems;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionResult;

/** What opens the handbook screen: the key (H by default), the second key (L by default, the quest map's) and use of the handbook item. */
public final class HandbookKeys {
    public static final KeyMapping OPEN = new KeyMapping(
            "key.deepcharter.handbook",
            InputConstants.Type.KEYBOARD,
            InputConstants.KEY_H,
            KeyMapping.Category.register(Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "main")));

    /** Opens the handbook too, so a player who thinks of it as the quest log finds it on the usual key. */
    public static final KeyMapping OPEN_QUEST_MAP = new KeyMapping(
            "key.deepcharter.handbook_quest_map",
            InputConstants.Type.KEYBOARD,
            InputConstants.KEY_L,
            OPEN.getCategory());

    private HandbookKeys() {}

    /** Whether {@code event} is either key that opens the handbook, which closes it again from inside. */
    static boolean opens(KeyEvent event) {
        return OPEN.matches(event) || OPEN_QUEST_MAP.matches(event);
    }

    /**
     * Vanilla's advancements screen is on L, and the directives are advancements the handbook already shows as the quest map, so an
     * advancements key still on its default is unbound: both would otherwise fire on one press. A key the player has chosen is left.
     */
    private static void freeTheQuestMapKey(Minecraft client) {
        KeyMapping advancements = client.options.keyAdvancements;
        if (advancements.isDefault() && advancements.getDefaultKey().equals(OPEN_QUEST_MAP.getDefaultKey())) {
            advancements.setKey(InputConstants.UNKNOWN);
            KeyMapping.resetMapping();
            client.options.save();
        }
    }

    public static void register() {
        KeyMappingHelper.registerKeyMapping(OPEN);
        KeyMappingHelper.registerKeyMapping(OPEN_QUEST_MAP);
        ClientLifecycleEvents.CLIENT_STARTED.register(HandbookKeys::freeTheQuestMapKey);
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            // both keys are drained, so one press of each in a tick opens the book once
            boolean pressed = false;
            while (OPEN.consumeClick()) {
                pressed = true;
            }
            while (OPEN_QUEST_MAP.consumeClick()) {
                pressed = true;
            }
            if (pressed && client.player != null && client.gui.screen() == null) {
                HandbookScreen.open(client);
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
