package io.github.pkeppeler.deepcharter.test.support;

import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

/** A player's use of an entity as the interaction packet delivers it: through Fabric's {@link UseEntityCallback}, with no hit position. */
public final class UseEntity {
    private UseEntity() {}

    @SuppressWarnings(
            "NullAway") // Fabric passes a null hitResult for an interaction with no hit position, but leaves the
    // parameter unannotated
    public static InteractionResult on(Player player, Level level, InteractionHand hand, Entity entity) {
        return UseEntityCallback.EVENT.invoker().interact(player, level, hand, entity, null);
    }
}
