package io.github.pkeppeler.deepcharter.client.continuity.mixin;

import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.world.entity.Avatar;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * What the crew see of a person's death (#1337, ADR 0100): the body goes still and dissolves, as a Retained's does, with no red flash and
 * no fall. Target: the tail of {@code AvatarRenderer.extractRenderState}, which fills a player's render state; vanilla's renderer reads
 * the death time for the fall and the red overlay from it. For the second half of the dying time the body is drawn as vanilla draws an
 * invisible team-mate, see-through, before vanilla removes it in its puff. The lamp's click and the ash come from the server.
 */
@Mixin(AvatarRenderer.class)
public abstract class AvatarRendererMixin {
    /** The dying ticks after which the body is see-through: half of vanilla's twenty. */
    private static final float SOLID_TICKS = 10f;

    @Inject(
            method =
                    "extractRenderState(Lnet/minecraft/world/entity/Avatar;Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;F)V",
            at = @At("TAIL"))
    private void deepcharter$stillBody(Avatar avatar, AvatarRenderState state, float partialTick, CallbackInfo ci) {
        if (!(avatar instanceof Player) || state.deathTime <= 0f) {
            return;
        }
        boolean fading = state.deathTime >= SOLID_TICKS;
        state.deathTime = 0f;
        state.hasRedOverlay = false;
        if (fading) {
            state.isInvisible = true;
            state.isInvisibleToPlayer = false;
        }
    }
}
