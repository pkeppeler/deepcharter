package io.github.pkeppeler.deepcharter.client.creature;

import net.minecraft.client.renderer.entity.state.EntityRenderState;

/** Per-frame copy of what the renderer needs. */
public class DrifterRenderState extends EntityRenderState {
    /** The drifter is drawn to a noise: its pulse quickens. */
    public boolean drawn;
}
