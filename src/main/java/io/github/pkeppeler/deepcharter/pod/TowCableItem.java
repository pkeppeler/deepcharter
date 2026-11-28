package io.github.pkeppeler.deepcharter.pod;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;

/**
 * The tow cable. Used on a pod it is {@link PodTowing}'s; used on nothing (the air or a block) it puts away the pod the person picked
 * first, so a click on empty ground clears the pick.
 */
public final class TowCableItem extends Item {
    public TowCableItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        if (PodTowing.pickedPodId(player).isEmpty()) {
            return InteractionResult.PASS;
        }
        if (player instanceof ServerPlayer person) {
            PodTowing.putAway(person);
        }
        return InteractionResult.SUCCESS;
    }
}
