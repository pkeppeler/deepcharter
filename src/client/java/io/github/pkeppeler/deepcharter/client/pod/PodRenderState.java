package io.github.pkeppeler.deepcharter.client.pod;

import net.minecraft.client.renderer.block.BlockModelRenderState;
import net.minecraft.client.renderer.entity.state.EntityRenderState;

/** What the placeholder pod renderer needs, copied off the entity once per frame. */
public class PodRenderState extends EntityRenderState {
	/** The hull block, resolved by the model resolver. */
	public final BlockModelRenderState hull = new BlockModelRenderState();
}
