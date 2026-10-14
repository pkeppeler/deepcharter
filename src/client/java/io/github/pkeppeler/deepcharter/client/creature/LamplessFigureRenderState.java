package io.github.pkeppeler.deepcharter.client.creature;

import net.minecraft.client.renderer.entity.state.EntityRenderState;

/** Per-frame copy of what the renderer needs. */
public class LamplessFigureRenderState extends EntityRenderState {
    /** 0 when whole, up to 1 as a dead figure dissolves. */
    public float fade;
    /** The figure holds a pod's hull. */
    public boolean clinging;
    /** The lamp in the figure's helmet is lit. */
    public boolean lamp;
}
