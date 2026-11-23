package io.github.pkeppeler.deepcharter.pod;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;

/**
 * The tether, sold at the repair station ({@link io.github.pkeppeler.deepcharter.repair.Consumable#TETHER}) and never used up. Used on a
 * pod it clips on or hooks a tow; used in the air it works the winch ({@link PodTether}).
 */
public final class TetherItem extends Item {
    public TetherItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        if (!(player instanceof ServerPlayer server)) {
            return InteractionResult.PASS;
        }
        return PodTether.useInAir(server);
    }
}
