package io.github.pkeppeler.deepcharter.layer.mixin;

import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Sets the flag that makes the server treat a player as mid-way through a change of dimension until its client answers
 * the teleport (#1229, ADR 0089): vanilla sets it in {@code ServerPlayer.teleport}, which a swap does not run.
 */
@Mixin(ServerPlayer.class)
public interface ServerPlayerSeamAccess {
    @Accessor("isChangingDimension")
    void deepcharter$setChangingDimension(boolean changing);
}
