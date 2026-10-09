package io.github.pkeppeler.deepcharter.client.pod;

import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.core.Direction;

/** Per-frame copy of what the pod renderer needs. */
public class PodRenderState extends EntityRenderState {
	/** The hull, or the wreck's hull. */
	public final ItemStackRenderState hull = new ItemStackRenderState();
	/** The drill part. Empty on a wreck. */
	public final ItemStackRenderState drill = new ItemStackRenderState();
	public boolean drilling;
	public Direction drillDirection = Direction.DOWN;
}
