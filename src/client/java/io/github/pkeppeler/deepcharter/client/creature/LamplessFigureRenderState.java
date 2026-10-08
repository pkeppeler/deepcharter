package io.github.pkeppeler.deepcharter.client.creature;

import net.minecraft.client.renderer.entity.state.HumanoidRenderState;

/** Per-frame copy of what the placeholder renderer needs. */
public class LamplessFigureRenderState extends HumanoidRenderState {
	/** 0 when whole, up to 1 as the figure fades out. */
	public float fade;
}
