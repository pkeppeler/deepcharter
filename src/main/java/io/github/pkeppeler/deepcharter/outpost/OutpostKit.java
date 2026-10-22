package io.github.pkeppeler.deepcharter.outpost;

import java.util.Optional;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.UseOnContext;

/**
 * The outpost kit: used on the top of a block at the floor of layer 1, it unfolds the charter's outpost there ({@link OutpostBuilder}).
 * A refused use tells the player why and keeps the kit.
 */
public final class OutpostKit extends Item {
    OutpostKit(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        if (!(context.getLevel() instanceof ServerLevel level)
                || !(context.getPlayer() instanceof ServerPlayer player)) {
            // The server decides; the client only shows the swing.
            return InteractionResult.SUCCESS;
        }
        Optional<Component> refusal =
                OutpostBuilder.build(player, level, context.getClickedPos(), context.getClickedFace());
        if (refusal.isPresent()) {
            player.sendOverlayMessage(refusal.get());
            return InteractionResult.FAIL;
        }
        context.getItemInHand().consume(1, player);
        player.sendOverlayMessage(Component.translatable("message.deepcharter.outpost.built"));
        return InteractionResult.SUCCESS;
    }
}
