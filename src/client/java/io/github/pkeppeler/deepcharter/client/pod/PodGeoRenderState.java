package io.github.pkeppeler.deepcharter.client.pod;

import net.minecraft.client.renderer.entity.state.EntityRenderState;

/** Per-frame copy of what a pod model's bones need ({@link PodMotion} fills it). Angles are degrees, distances model pixels. */
public class PodGeoRenderState extends EntityRenderState {
	/** Where the pod faces, as a vanilla yaw. */
	public float heading;
	/** The drill mount's x rotation, replacing the file's: 0 is level, 90 points the drill down. */
	public float mountPitch;
	public float drillSpin;
	public float rotorSpin;
	public float fanSpin;
	/** 0 with the thrusters stowed, 1 with them swung down to lift. */
	public float thrust;
	/** How far the pod has driven, for wheels, treads and legs. */
	public float travel;
	/** 0 standing, 1 in full stride. */
	public float walk;
	public boolean flying;
	public boolean drilling;
	/** The lamps are on: the pod has a pilot and power. */
	public boolean lit;
}
