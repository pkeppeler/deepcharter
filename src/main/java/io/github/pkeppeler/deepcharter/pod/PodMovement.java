package io.github.pkeppeler.deepcharter.pod;

import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.player.Input;

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
		if (pod.cargoMass() < 0) {
			throw new IllegalStateException("pod cargo mass must not be negative, got " + pod.cargoMass());
		}
		PodTuning.Movement tuning = PodTuning.DEFAULT.movement();
		PodStats stats = PodStats.of(pod);
		// A pod without power (stranded, or a PodEvents listener says so) ignores its pilot.
		ServerPlayer pilot = PodEvents.isPowered(pod) && pod.getControllingPassenger() instanceof ServerPlayer player ? player : null;
		Input input = pilot == null ? Input.EMPTY : pilot.getLastClientInput();

		Direction drive = pilot == null ? null : driveDirection(input, pilot.getYRot());
		double vx = drive == null ? 0 : drive.getStepX() * stats.horizontalSpeed();
		double vz = drive == null ? 0 : drive.getStepZ() * stats.horizontalSpeed();

		float lift = Math.max(0f, stats.enginePower() - pod.cargoMass() - PodEvents.extraMass(pod));
		boolean thrusting = input.jump() && lift > 0f;
		double vy = pod.getDeltaMovement().y;
		if (thrusting) {
			vy += stats.thrustAcceleration() * lift / stats.enginePower();
		}
		vy = Math.min((vy - tuning.gravity()) * tuning.verticalDrag(), stats.maxClimbSpeed());

		pod.setFlying(thrusting);
		pod.setDeltaMovement(vx, vy, vz);
		// noPhysics makes Entity.move skip block collision; only this hook sets it on a pod.
		pod.noPhysics = PodEvents.ignoresBlockCollision(pod);
		pod.move(MoverType.SELF, pod.getDeltaMovement());
		// Entity.move leaves the speed it ran into. Clear it only if it still points into the surface, so a bounce survives.
		double after = pod.getDeltaMovement().y;
		if (pod.verticalCollision && (pod.verticalCollisionBelow ? after < 0 : after > 0)) {
			pod.setDeltaMovement(pod.getDeltaMovement().multiply(1, 0, 1));
		}
	}

	/** Called when the pod lands: a fall past the threshold damages the hull in proportion to the excess. */
	public static void onLanding(PodEntity pod, double fallDistance, float damageMultiplier, DamageSource source) {
		if (pod.level().isClientSide()) {
			return;
		}
		PodStats stats = PodStats.of(pod);
		double excess = fallDistance - stats.hardLandingDistance();
		if (excess > 0) {
			pod.damageHull((float) (excess * stats.hullDamagePerBlock() * damageMultiplier));
		}
	}

	/**
	 * Snap WASD to one horizontal axis, relative to where the pilot looks. Forward and back win over
	 * strafing, so a diagonal press never moves diagonally. Null when no direction key is held.
	 */
	static Direction driveDirection(Input input, float pilotYaw) {
		Direction facing = Direction.fromYRot(pilotYaw);
		if (input.forward() != input.backward()) {
			return input.forward() ? facing : facing.getOpposite();
		}
		if (input.left() != input.right()) {
			return input.right() ? facing.getClockWise() : facing.getCounterClockWise();
		}
		return null;
	}
}
