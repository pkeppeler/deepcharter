package io.github.pkeppeler.deepcharter.pod;

import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec3;

/**
 * Server-authoritative pod movement: treads on the ground, a rotor to climb. The server reads the
 * pilot's vanilla input, so there is no packet of our own; clients only see the pod move.
 */
public final class PodMovement {
	private PodMovement() {
	}

	public static void init() {
	}

	/** Called every pod tick, on both sides; only the server moves the pod. */
	public static void tick(PodEntity pod) {
		if (pod.level().isClientSide()) {
			return;
		}
		PodTuning.Movement tuning = PodTuning.DEFAULT.movement();
		Input input = pilotInput(pod);

		Vec3 velocity = pod.getDeltaMovement();
		Direction drive = driveDirection(input, pod);
		double vx = drive == null ? 0 : drive.getStepX() * tuning.horizontalSpeed();
		double vz = drive == null ? 0 : drive.getStepZ() * tuning.horizontalSpeed();

		float lift = Math.max(0f, tuning.enginePower() - pod.cargoMass());
		boolean thrusting = input.jump() && lift > 0f;
		double vy = velocity.y;
		if (thrusting) {
			vy += tuning.thrustAcceleration() * lift / tuning.enginePower();
		}
		vy = Math.min((vy - tuning.gravity()) * tuning.verticalDrag(), tuning.maxClimbSpeed());

		pod.setFlying(thrusting);
		pod.setDeltaMovement(vx, vy, vz);
		pod.move(MoverType.SELF, pod.getDeltaMovement());
		if (pod.verticalCollision) {
			// Entity.move does not clear the speed it ran into, so a landing would keep its fall speed.
			pod.setDeltaMovement(pod.getDeltaMovement().multiply(1, 0, 1));
		}
	}

	/** Called when the pod lands: a fall past the threshold damages the hull in proportion to the excess. */
	public static void onLanding(PodEntity pod, double fallDistance, float damageMultiplier, DamageSource source) {
		if (pod.level().isClientSide()) {
			return;
		}
		PodTuning.Movement tuning = PodTuning.DEFAULT.movement();
		double excess =fallDistance - tuning.hardLandingDistance();
		if (excess > 0) {
			pod.setHull(Math.max(0f, pod.hull() - (float) (excess * tuning.hullDamagePerBlock() * damageMultiplier)));
		}
	}

	/** The pilot's input, or none when the pod has no pilot or is stranded (powered off, so it ignores the pilot). */
	private static Input pilotInput(PodEntity pod) {
		if (pod.stranded() || !(pod.getControllingPassenger() instanceof ServerPlayer pilot)) {
			return Input.EMPTY;
		}
		return pilot.getLastClientInput();
	}

	/**
	 * Snap WASD to one horizontal axis, relative to where the pilot looks. Forward and back win over
	 * strafing, so a diagonal press never moves diagonally. Null when no direction key is held.
	 */
	private static Direction driveDirection(Input input, PodEntity pod) {
		Direction facing = Direction.fromYRot(pod.getControllingPassenger() == null ? pod.getYRot() : pod.getControllingPassenger().getYRot());
		if (input.forward() != input.backward()) {
			return input.forward() ? facing : facing.getOpposite();
		}
		if (input.left() != input.right()) {
			return input.right() ? facing.getClockWise() : facing.getCounterClockWise();
		}
		return null;
	}
}
