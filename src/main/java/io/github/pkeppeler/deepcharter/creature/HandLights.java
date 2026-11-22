package io.github.pkeppeler.deepcharter.creature;

import io.github.pkeppeler.deepcharter.DeepCharter;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;

/**
 * The one rule for a light in a person's hand: the Retained ({@link FootGrips}) and the Tallies ({@link Tally}) both keep off a person
 * on foot who holds one. A light taken up after a creature has latched
 * does not free the person; only the draw is gated, never the hold.
 */
public final class HandLights {
    /** The items that keep a creature off when held: data, so a pack, and later the suit lamp and flares, add to it. */
    public static final TagKey<Item> TAG =
            TagKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "hand_lights"));

    private HandLights() {}

    /** Whether either hand of {@code person} holds an item of {@link #TAG}, as a torch or a lantern is. */
    public static boolean held(Player person) {
        for (InteractionHand hand : InteractionHand.values()) {
            if (person.getItemInHand(hand).is(TAG)) {
                return true;
            }
        }
        return false;
    }
}
