package io.github.pkeppeler.deepcharter.client.pod;

import net.minecraft.core.Direction;
import net.minecraft.util.Mth;

import io.github.pkeppeler.deepcharter.pod.PodEntity;

/**
 * One pod's animation, advanced every frame from what the pod does: phases keep running across frames, so a part never jumps when
 * its speed changes. Client thread only. Rates are per tick; 20 ticks are a second.
 */
final class PodMotion {
	private static final float TURN_DEGREES = 15f;
	private static final float DRILL_SPIN_DEGREES = 40f;
	private static final float MOUNT_DEGREES = 8f;
	private static final float ROTOR_IDLE_DEGREES = 6f;
	private static final float ROTOR_FLYING_DEGREES = 60f;
	/** How fast the rotor's speed changes, degrees per tick, per tick. */
	private static final float ROTOR_SPIN_UP = 3f;
	private static final float FAN_DEGREES = 24f;
	private static final float THRUST_PER_TICK = 0.1f;
	private static final float WALK_PER_TICK = 0.2f;
	/** Blocks per tick below which the pod counts as standing still. */
	private static final double STILL = 0.01;
	/** Blocks per tick past which a move is a teleport (a breach crossing, a command), not driving: five times a pod's speed. */
	private static final double TELEPORT_SPEED = 1.0;
	/** The least step a speed is measured over, so two frames in one tick never read as a jump. */
	private static final float MIN_STEP = 0.25f;
	/** A frame after a long pause advances at most this many ticks. */
	private static final float MAX_STEP = 5f;
	private static final float PIXELS_PER_BLOCK = 16f;

	private boolean started;
	private float lastAge;
	private double lastX;
	private double lastZ;
	private float heading;
	private float mountPitch;
	private float drillSpin;
	private float rotorSpeed;
	private float rotorSpin;
	private float fanSpin;
	private float thrust;
	private float travel;
	private float walk;

	/**
	 * Advances to the state's frame and writes the pose into it. {@code mountRestPitch} is the drill mount's idle angle in the model,
	 * and {@code drillSpinScale} its share of the full drill spin ({@link PodGeoModel#drillSpinScale}).
	 */
	void advance(PodEntity pod, PodGeoRenderState state, float mountRestPitch, float drillSpinScale) {
		if (!started) {
			started = true;
			lastAge = state.ageInTicks;
			lastX = state.x;
			lastZ = state.z;
			heading = pod.getYRot();
			mountPitch = mountRestPitch;
		}
		float step = Mth.clamp(state.ageInTicks - lastAge, 0f, MAX_STEP);
		double dx = state.x - lastX;
		double dz = state.z - lastZ;
		lastAge = state.ageInTicks;
		lastX = state.x;
		lastZ = state.z;
		if (Math.sqrt(dx * dx + dz * dz) > TELEPORT_SPEED * Math.max(step, MIN_STEP)) {
			// A jump, not a drive: no turn, no stride.
			dx = 0;
			dz = 0;
		}

		boolean piloted = pod.getControllingPassenger() != null;
		boolean powered = piloted && pod.fuel() > 0f && !pod.stranded();
		boolean drilling = pod.drilling();
		Direction drill = pod.drillDirection();
		boolean moving = step > 0f && Math.sqrt(dx * dx + dz * dz) > STILL * step;

		if (drilling && drill.getAxis().isHorizontal()) {
			heading = Mth.approachDegrees(heading, drill.toYRot(), TURN_DEGREES * step);
		} else if (moving) {
			heading = Mth.approachDegrees(heading, (float) (Mth.atan2(-dx, dz) * Mth.RAD_TO_DEG), TURN_DEGREES * step);
		} else if (!piloted) {
			// A parked pod faces where it was set down, or where a command turns it.
			heading = pod.getYRot();
		}
		float facingX = -Mth.sin(heading * Mth.DEG_TO_RAD);
		float facingZ = Mth.cos(heading * Mth.DEG_TO_RAD);
		travel += (float) (dx * facingX + dz * facingZ) * PIXELS_PER_BLOCK;

		float mountTarget = !drilling ? mountRestPitch : drill == Direction.DOWN ? 90f : 0f;
		mountPitch = Mth.approach(mountPitch, mountTarget, MOUNT_DEGREES * step);
		if (drilling) {
			drillSpin = (drillSpin + DRILL_SPIN_DEGREES * drillSpinScale * step) % 360f;
		}
		float rotorTarget = pod.flying() ? ROTOR_FLYING_DEGREES : powered ? ROTOR_IDLE_DEGREES : 0f;
		rotorSpeed = Mth.approach(rotorSpeed, rotorTarget, ROTOR_SPIN_UP * step);
		rotorSpin = (rotorSpin + rotorSpeed * step) % 360f;
		if (powered) {
			fanSpin = (fanSpin + FAN_DEGREES * step) % 360f;
		}
		thrust = Mth.approach(thrust, pod.flying() ? 1f : 0f, THRUST_PER_TICK * step);
		walk = Mth.approach(walk, moving ? 1f : 0f, WALK_PER_TICK * step);

		state.heading = heading;
		state.mountPitch = mountPitch;
		state.drillSpin = drillSpin;
		state.rotorSpin = rotorSpin;
		state.fanSpin = fanSpin;
		state.thrust = thrust;
		state.travel = travel;
		state.walk = walk;
		state.flying = pod.flying();
		state.drilling = drilling;
		state.lit = powered;
	}
}
