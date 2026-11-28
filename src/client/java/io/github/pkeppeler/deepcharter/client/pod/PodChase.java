package io.github.pkeppeler.deepcharter.client.pod;

import io.github.pkeppeler.deepcharter.pod.PodEntity;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import org.jspecify.annotations.Nullable;

/**
 * The crew's camera (#1281, #1268, #1680; ADR 0095). Taking any seat, the pilot's or a crew seat behind it, switches to
 * the third-person chase view, with no opt-out, and leaving it restores the view the player had; F5 still cycles in
 * between. A seat swap inside one pod (the pilot leaves and the navigator takes the controls) keeps the view. While
 * seated the view's yaw turns with the pod by the pod's own turn each tick, as a vanilla boat adds its rotation to its
 * riders', so the mouse look the player has stays: it is not clamped, as a boat's is. In the third-person view the
 * angle also eases back behind a pod that is driving, once the mouse has been still for a moment. Client thread only.
 */
public final class PodChase {
    /** Ticks the mouse is still before the chase view starts to settle behind the pod. */
    static final int LOOK_HOLD_TICKS = 20;

    /** Degrees a tick the chase view settles at: under the pod's own steering turn, so it eases. */
    static final float SETTLE_DEGREES = 4f;

    /** Blocks a tick the pod must be driving at for the chase view to settle behind it. */
    private static final double DRIVING = 0.02;

    /** Off only while a test or scenario that stages its own cockpit view runs, which {@link #setAutomatic} sets. */
    private static boolean automatic = true;

    /** The pod the local player sits in, null when none. */
    private static @Nullable PodEntity seat;
    /** The view the player had before taking the seat. */
    private static @Nullable CameraType before;
    /** The pod's yaw at the last tick, to take this tick's turn from. */
    private static float lastPodYaw;

    /**
     * The view's yaw as this class left it at the end of the last tick. A different yaw at the next tick is the
     * mouse's, which holds off settling and is kept.
     */
    private static float lastViewYaw;

    /** Ticks since the mouse last moved the view. */
    private static int stillTicks;

    private PodChase() {}

    public static void init() {
        ClientTickEvents.END_CLIENT_TICK.register(PodChase::tick);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> leave(client));
    }

    // test-seam: client tests and recorded scenarios stage a first-person cockpit and keep the camera they set, so each
    // one starts with the chase off (ClientTestLog); PodChaseClientTest and the pod-controls scenario run it as shipped
    public static void setAutomatic(boolean automatic) {
        PodChase.automatic = automatic;
        forget();
    }

    private static void tick(Minecraft client) {
        if (!automatic) {
            return;
        }
        LocalPlayer player = client.player;
        if (player == null || !(player.getVehicle() instanceof PodEntity seated)) {
            leave(client);
            return;
        }
        if (seated != seat) {
            // A pod after a pod keeps the view the player first had.
            if (seat == null) {
                before = client.options.getCameraType();
                client.options.setCameraType(CameraType.THIRD_PERSON_BACK);
            }
            seat = seated;
            lastPodYaw = seated.getYRot();
            lastViewYaw = player.getYRot();
            stillTicks = 0;
            return;
        }
        float yaw = player.getYRot();
        stillTicks = Math.abs(Mth.wrapDegrees(yaw - lastViewYaw)) > 0f ? 0 : stillTicks + 1;
        float turned = Mth.wrapDegrees(seated.getYRot() - lastPodYaw);
        lastPodYaw = seated.getYRot();
        boolean chasing = client.options.getCameraType() != CameraType.FIRST_PERSON;
        float settle = chasing && stillTicks >= LOOK_HOLD_TICKS && drives(seated)
                ? settleTurn(Mth.wrapDegrees(yaw + turned - seated.getYRot()))
                : 0f;
        lastViewYaw = yaw + turned - settle;
        player.setYRot(lastViewYaw);
    }

    /** The turn that brings a view {@code offset} degrees off the pod's nose a step nearer it. */
    static float settleTurn(float offset) {
        return Mth.clamp(offset, -SETTLE_DEGREES, SETTLE_DEGREES);
    }

    private static boolean drives(PodEntity pod) {
        return pod.getDeltaMovement().horizontalDistanceSqr() > DRIVING * DRIVING;
    }

    private static void leave(Minecraft client) {
        if (seat != null && before != null) {
            client.options.setCameraType(before);
        }
        forget();
    }

    private static void forget() {
        seat = null;
        before = null;
    }
}
