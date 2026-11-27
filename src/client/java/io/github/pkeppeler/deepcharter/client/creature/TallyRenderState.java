package io.github.pkeppeler.deepcharter.client.creature;

import net.minecraft.client.renderer.entity.state.EntityRenderState;

/** Per-frame copy of what the Tally's renderer needs. */
public class TallyRenderState extends EntityRenderState {
    /** 0 when whole, up to 1 as a dead Tally comes apart. */
    public float fade;
    /** The Tally holds a hatch or a pack. */
    public boolean clamped;
}
