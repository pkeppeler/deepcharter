package io.github.pkeppeler.deepcharter.client.continuity.mixin;

import io.github.pkeppeler.deepcharter.client.theme.ContinuityLook;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.world.entity.Avatar;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * What the crew see of a person's death (#1337, ADR 0100): the body goes still and dissolves, as a Retained's does, with no red flash and
 * no fall. Target: the tail of {@code AvatarRenderer.extractRenderState}, which fills a player's render state; vanilla's renderer reads
 * the death time for the fall and the red overlay from it. After the theme's {@code bodySolidTicks} of the dying time the whole body is
 * drawn as vanilla draws an invisible team-mate, see-through, with no armour, nothing in the hands or on the head, no cape, no parrots and
 * no name over it, before vanilla removes it in its puff. The lamp's click and the ash come from the server.
 */
@Mixin(AvatarRenderer.class)
public abstract class AvatarRendererMixin {
    @Inject(
            method =
                    "extractRenderState(Lnet/minecraft/world/entity/Avatar;Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;F)V",
            at = @At("TAIL"))
    private void deepcharter$stillBody(Avatar avatar, AvatarRenderState state, float partialTick, CallbackInfo ci) {
        if (!(avatar instanceof Player) || state.deathTime <= 0f) {
            return;
        }
        boolean fading = state.deathTime >= ContinuityLook.current().bodySolidTicks();
        state.deathTime = 0f;
        state.hasRedOverlay = false;
        if (fading) {
            state.isInvisible = true;
            state.isInvisibleToPlayer = false;
            state.headEquipment = ItemStack.EMPTY;
            state.chestEquipment = ItemStack.EMPTY;
            state.legsEquipment = ItemStack.EMPTY;
            state.feetEquipment = ItemStack.EMPTY;
            state.rightHandItemStack = ItemStack.EMPTY;
            state.leftHandItemStack = ItemStack.EMPTY;
            state.rightHandItemState.clear();
            state.leftHandItemState.clear();
            state.heldOnHead.clear();
            state.headItem.clear();
            state.wornHeadType = null;
            state.showCape = false;
            state.parrotOnLeftShoulder = null;
            state.parrotOnRightShoulder = null;
            state.arrowCount = 0;
            state.stingerCount = 0;
            state.displayFireAnimation = false;
            state.nameTag = null;
            state.scoreText = null;
        }
    }
}
