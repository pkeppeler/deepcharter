package io.github.pkeppeler.deepcharter.pod;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec3;

/**
 * Server-authoritative pod movement: treads on the ground, a rotor to climb. The server reads the pilot's vanilla
 * input, so there is no packet of our own; clients only see the pod move. W, S, A and D steer it as a boat is steered
 * ({@link PodSteering}).
 */
public final class PodMovement {
    private PodMovement() {}

    public static void init() {}

    /** Called every pod tick, on the server only. */
    public static void tick(PodEntity pod, PodStats stats) {
        if (pod.cargoMass() < 0) {
            throw new IllegalStateException("pod cargo mass must not be negative, got " + pod.cargoMass());
        }
        PodTuning.Movement tuning = PodTuning.DEFAULT.movement();
        // A pod without power (stranded, or a PodEvents listener says so) ignores its pilot.
        ServerPlayer pilot = PodEvents.isPowered(pod) && pod.getControllingPassenger() instanceof ServerPlayer player
                ? player
                : null;
        Input input = pilot == null ? Input.EMPTY : pilot.getLastClientInput();

        // Steer first: the throttle drives along the facing it gives. The drill and a bore as wide as the pod
        // hold the grid, so neither takes A and D freely (PodSteering).
        if (pilot == null || pod.drilling()) {
            PodSteering.stopTurning(pod);
            pod.setTurnTarget(null);
        } else if (!PodSteering.boreTurn(pod, input)) {
            PodSteering.steer(pod, input);
        }
        PodSteering.noteTurnKey(pod, input);
        Vec3 facing = Vec3.directionFromRotation(0, pod.getYRot());
        double throttle = pilot == null ? 0 : PodSteering.throttle(input, stats);
        double vx = facing.x * throttle;
        double vz = facing.z * throttle;

        float lift = Math.max(0f, stats.enginePower() - pod.cargoMass() - PodEvents.extraMass(pod));
        boolean thrusting = input.jump() && lift > 0f;
        double vy = pod.getDeltaMovement().y;
        if (thrusting) {
            vy += stats.thrustAcceleration() * lift / stats.enginePower();
        }
        // No gravity drops only the gravity term: thrust, drag and the climb cap still apply.
        vy = Math.min((vy - pod.gravityPull(tuning.gravity())) * tuning.verticalDrag(), stats.maxClimbSpeed());

        pod.setFlying(thrusting);
        pod.setDeltaMovement(vx, vy, vz);
        // noPhysics makes Entity.move skip block collision; only this hook sets it on a pod.
        pod.noPhysics = PodEvents.ignoresBlockCollision(pod);
        pod.move(MoverType.SELF, pod.getDeltaMovement());
        // Entity.move leaves the speed it ran into. Clear it only if it still points into the surface, so a bounce
        // survives.
        double after = pod.getDeltaMovement().y;
        if (pod.verticalCollision && (pod.verticalCollisionBelow ? after < 0 : after > 0)) {
            pod.setDeltaMovement(pod.getDeltaMovement().multiply(1, 0, 1));
        }
    }
}
