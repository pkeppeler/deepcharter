package io.github.pkeppeler.deepcharter.client.creature;

import com.geckolib.renderer.base.GeoRenderState;
import io.github.pkeppeler.deepcharter.creature.DrifterVariant;
import net.minecraft.client.renderer.entity.state.EntityRenderState;

/** Per-frame copy of what the renderer needs. */
public class DrifterRenderState extends EntityRenderState {
    /** The drifter is drawn to a noise: its pulse quickens. */
    public boolean drawn;
    /** The form it is drawn as: its model, texture, glowmask and animations. */
    public DrifterVariant variant = DrifterVariant.JELLYFISH;

    /** The form of the drifter a GeckoLib state was made for; GeckoLib hands its callbacks the base type. */
    static DrifterVariant variantOf(GeoRenderState state) {
        return state instanceof DrifterRenderState drifter ? drifter.variant : DrifterVariant.JELLYFISH;
    }
}
