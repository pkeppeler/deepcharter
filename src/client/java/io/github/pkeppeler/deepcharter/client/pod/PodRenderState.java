package io.github.pkeppeler.deepcharter.client.pod;

import net.minecraft.client.renderer.block.BlockModelRenderState;
import net.minecraft.client.renderer.entity.state.EntityRenderState;

/** Per-frame copy of what the placeholder renderer needs. */
public class PodRenderState extends EntityRenderState {
	public final BlockModelRenderState hull = new BlockModelRenderState();
}
