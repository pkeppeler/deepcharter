package io.github.pkeppeler.deepcharter.client.creature;

import net.minecraft.client.renderer.entity.state.EntityRenderState;

/** Per-frame copy of what the renderer needs. */
public class LamplessFigureRenderState extends EntityRenderState {
	/** 0 when whole, up to 1 as the figure fades out. */
	public float fade;
	/** The figure holds a pod's hull. */
	public boolean clinging;
	/** The figure carries a lamp. */
	public boolean lamp;
}
