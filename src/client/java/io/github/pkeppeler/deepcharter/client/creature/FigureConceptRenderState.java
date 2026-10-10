package io.github.pkeppeler.deepcharter.client.creature;

import net.minecraft.client.renderer.entity.state.EntityRenderState;

/** Per-frame copy of what a concept figure needs. */
public class FigureConceptRenderState extends EntityRenderState {
	/** 0 when whole, up to 1 as the figure fades out. */
	public float fade;
}
