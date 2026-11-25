package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.ClientChecks.require;

import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.test.support.ClientSession;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.TestEntities;
import io.github.pkeppeler.deepcharter.test.support.TwoPlayerServer;
import java.util.UUID;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.CameraType;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;

/**
 * Client GameTest for #1281 (and #1268): the pilot's camera. Taking the seat switches to the third-person chase view and leaving it
 * brings back the view the player had; F5 still cycles while seated. In first person the view turns with the pod, as a vanilla boat's
 * rider does, and the angle between the view and the pod stays where the mouse put it. In third person the camera settles behind a
 * pod that drives.
 */
public class PodChaseClientTest implements FabricClientGameTest {
    /** How far the player has looked away from the pod's nose with the mouse. */
    private static final float LOOK_AWAY = 40f;

    private static final int TURN_TICKS = 15;
    private static final int SETTLE_DRIVE_TICKS = 100;
    /** Degrees the view may differ from the angle it had, once the pod has turned: rounding of the packed yaw. */
    private static final float YAW_TOLERANCE = 3f;

    private static final float SETTLED = 8f;

    @Override
    public void runTest(ClientGameTestContext context) {
        ClientTestLog.start(this);
        try (TwoPlayerServer two = TwoPlayerServer.start(context)) {
            PodMovementClientTest.moveToOpenGround(context, two);
            // A view that is neither the default nor the chase view, so a restore cannot pass by luck.
            setCamera(context, CameraType.THIRD_PERSON_FRONT);
            PodMovementClientTest.mountBoth(two);
            ClientWait.until(
                    context,
                    "the client riding a pod",
                    client -> ClientSession.player(client).getVehicle() instanceof PodEntity);
            context.waitTicks(5);

            require(
                    camera(context) == CameraType.THIRD_PERSON_BACK,
                    "taking the pilot seat should switch to the third-person chase view, it is " + camera(context));
            setCamera(context, CameraType.FIRST_PERSON);
            context.waitTicks(5);
            require(
                    camera(context) == CameraType.FIRST_PERSON,
                    "F5 still cycles the view while seated, but the camera went to " + camera(context));

            firstPersonTurnsWithThePod(context);
            thirdPersonSettlesBehindTheDrive(context);

            two.server()
                    .runOnServer(server ->
                            realPlayer(server, two.mock().player().getUUID()).stopRiding());
            ClientWait.until(
                    context,
                    "the client out of the pod",
                    client -> !(ClientSession.player(client).getVehicle() instanceof PodEntity));
            context.waitTicks(3);
            require(
                    camera(context) == CameraType.THIRD_PERSON_FRONT,
                    "leaving the pod should restore the view the player had before it, it is " + camera(context));
        }
    }

    /** Turning the pod with A turns a first-person view by the same angle, and mouse look is kept on top. */
    private static void firstPersonTurnsWithThePod(ClientGameTestContext context) {
        setCamera(context, CameraType.FIRST_PERSON);
        context.runOnClient(client -> {
            var player = ClientSession.player(client);
            player.setYRot(player.getYRot() + LOOK_AWAY);
        });
        context.waitTicks(2);
        float podBefore = podYaw(context);
        float relativeBefore = relativeYaw(context);
        context.getInput().holdKey(options -> options.keyLeft);
        context.waitTicks(TURN_TICKS);
        context.getInput().releaseKey(options -> options.keyLeft);
        context.waitTicks(8);
        float turned = Mth.wrapDegrees(podYaw(context) - podBefore);
        float relativeAfter = relativeYaw(context);
        require(turned < -20f, "A should turn the pod left by a good angle, it turned " + turned);
        require(
                Math.abs(Mth.wrapDegrees(relativeAfter - relativeBefore)) < YAW_TOLERANCE,
                "the first-person view should turn with the pod (it turned " + turned
                        + "), keeping its angle to the nose " + relativeBefore + ", but it is " + relativeAfter);
    }

    /** A pod that drives pulls a third-person camera that looks away round behind it. */
    private static void thirdPersonSettlesBehindTheDrive(ClientGameTestContext context) {
        setCamera(context, CameraType.THIRD_PERSON_BACK);
        context.runOnClient(client -> {
            var player = ClientSession.player(client);
            player.setYRot(player.getYRot() + 2 * LOOK_AWAY);
        });
        context.waitTicks(2);
        float before = Math.abs(relativeYaw(context));
        require(before > SETTLED, "the test should start with the camera looking away from the pod, it is " + before);
        context.getInput().holdKey(options -> options.keyUp);
        context.waitTicks(SETTLE_DRIVE_TICKS);
        context.getInput().releaseKey(options -> options.keyUp);
        float after = Math.abs(relativeYaw(context));
        require(
                after < SETTLED,
                "the chase camera should settle behind a driving pod, it is " + after + " degrees off");
    }

    private static void setCamera(ClientGameTestContext context, CameraType type) {
        context.runOnClient(client -> client.options.setCameraType(type));
    }

    private static CameraType camera(ClientGameTestContext context) {
        return context.computeOnClient(client -> client.options.getCameraType());
    }

    private static float podYaw(ClientGameTestContext context) {
        return context.computeOnClient(
                client -> TestEntities.vehicle(ClientSession.player(client)).getYRot());
    }

    /** The view's yaw from the pod's nose, in degrees: 0 looks along the pod. */
    private static float relativeYaw(ClientGameTestContext context) {
        return context.computeOnClient(client -> {
            var player = ClientSession.player(client);
            return Mth.wrapDegrees(
                    player.getYRot() - TestEntities.vehicle(player).getYRot());
        });
    }

    private static ServerPlayer realPlayer(MinecraftServer server, UUID mockId) {
        return server.getPlayerList().getPlayers().stream()
                .filter(player -> !player.getUUID().equals(mockId))
                .findFirst()
                .orElseThrow(() -> new AssertionError("the real player is not on the server"));
    }
}
