package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.ClientChecks.require;

import io.github.pkeppeler.deepcharter.client.pod.PodChase;
import io.github.pkeppeler.deepcharter.pod.Chassis;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.pod.PodSeat;
import io.github.pkeppeler.deepcharter.test.support.ClientSession;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.TestEntities;
import io.github.pkeppeler.deepcharter.test.support.TestLayers;
import io.github.pkeppeler.deepcharter.test.support.TwoPlayerServer;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.CameraType;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;

/**
 * Client GameTest for #1281 (and #1268, #1680): the crew's camera. Taking any seat switches to the third-person chase
 * view and leaving it brings back the view the player had; F5 still cycles while seated. In first person the view turns
 * with the pod, as a vanilla boat's rider does, and the angle between the view and the pod stays where the mouse put
 * it. In third person the camera settles behind a pod that drives.
 */
public class PodChaseClientTest implements FabricClientGameTest {
    /** How far the player has looked away from the pod's nose with the mouse. */
    private static final float LOOK_AWAY = 40f;

    private static final int TURN_TICKS = 15;
    private static final int SETTLE_DRIVE_TICKS = 100;
    /** Degrees the view may differ from the angle it had, once the pod has turned: rounding of the packed yaw. */
    private static final float YAW_TOLERANCE = 3f;

    private static final float SETTLED = 8f;
    /** Blocks under the top of layer 1, which is open air, that the pilot is moved to. */
    private static final int HEADROOM = 3;
    /**
     * A first-person drive longer than the mouse's 20 still ticks plus a settle, short of the end of the open ground.
     */
    private static final int NEVER_SETTLES_TICKS = 50;

    @Override
    public void runTest(ClientGameTestContext context) {
        ClientTestLog.start(this);
        PodChase.setAutomatic(true);
        // Put back the view this test found: the next class in this client JVM renders from it.
        CameraType original = camera(context);
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
            firstPersonNeverSettlesWhileDriving(context);

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

            theSecondSeatChasesToo(context, two);
            leavingByDimensionSwapAndByDeathRestoresTheView(context, two);
        } finally {
            setCamera(context, original);
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

    /**
     * The first-person view is the pilot's own: a pod that drives (backing, as the open ground ahead is spent) never
     * swings it round behind, however long the mouse is still.
     */
    private static void firstPersonNeverSettlesWhileDriving(ClientGameTestContext context) {
        setCamera(context, CameraType.FIRST_PERSON);
        context.waitTicks(30);
        float before = relativeYaw(context);
        context.getInput().holdKey(options -> options.keyDown);
        context.waitTicks(NEVER_SETTLES_TICKS);
        context.getInput().releaseKey(options -> options.keyDown);
        float after = relativeYaw(context);
        require(
                Math.abs(Mth.wrapDegrees(after - before)) < YAW_TOLERANCE,
                "driving must not settle a first-person view, it went from " + before + " to " + after
                        + " degrees off the nose");
    }

    /** A pilot moved to another level, and a pilot who dies, both lose the seat: the view they had comes back. */
    private static void leavingByDimensionSwapAndByDeathRestoresTheView(
            ClientGameTestContext context, TwoPlayerServer two) {
        UUID mock = two.mock().player().getUUID();
        boardNewPod(context, two, mock);
        require(
                camera(context) == CameraType.THIRD_PERSON_BACK,
                "boarding again should chase, it is " + camera(context));
        two.server().runOnServer(server -> {
            ServerLevel layer = TestLayers.level(server, 1);
            realPlayer(server, mock)
                    .teleportTo(layer, 0.5, TestLayers.band(server, 1).maxY() - HEADROOM, 0.5, Set.of(), 0f, 0f, true);
        });
        ClientWait.until(
                context,
                "the player in layer 1, out of the pod",
                client -> TestLayers.clientIsIn(client, 1)
                        && !(ClientSession.player(client).getVehicle() instanceof PodEntity));
        context.waitTicks(3);
        require(
                camera(context) == CameraType.THIRD_PERSON_FRONT,
                "moving to another level should restore the view the player had, it is " + camera(context));

        boardNewPod(context, two, mock);
        require(
                camera(context) == CameraType.THIRD_PERSON_BACK,
                "boarding in layer 1 should chase, it is " + camera(context));
        two.server().runOnServer(server -> realPlayer(server, mock).kill(TestLayers.level(server, 1)));
        ClientWait.until(
                context,
                "the dead pilot out of the pod",
                client -> !(ClientSession.player(client).getVehicle() instanceof PodEntity));
        context.waitTicks(3);
        require(
                camera(context) == CameraType.THIRD_PERSON_FRONT,
                "dying in the seat should restore the view the player had, it is " + camera(context));
    }

    /**
     * #1680: the navigator's seat chases as the pilot's does. The mock takes a Prospector's pilot seat and the real
     * player its second seat. When the mock gets out the player moves up to the pilot's seat and keeps the view;
     * leaving brings back the view the player had.
     */
    private static void theSecondSeatChasesToo(ClientGameTestContext context, TwoPlayerServer two) {
        UUID mock = two.mock().player().getUUID();
        two.server().runOnServer(server -> {
            ServerPlayer real = realPlayer(server, mock);
            PodEntity pod = TestEntities.create(PodRegistry.typeOf(Chassis.PROSPECTOR), real.level());
            pod.setPos(real.position());
            pod.setNoGravity(true);
            real.level().addFreshEntity(pod);
            ServerPlayer pilot = two.mock().player();
            pilot.stopRiding();
            require(pilot.startRiding(pod), "the mock could not take the Prospector's pilot seat");
            require(real.startRiding(pod), "the player could not take the Prospector's second seat");
        });
        ClientWait.until(
                context,
                "the client in the Prospector's second seat",
                client -> ClientSession.player(client).getVehicle() instanceof PodEntity pod
                        && PodSeat.isNavigator(pod, ClientSession.player(client)));
        context.waitTicks(5);
        require(
                camera(context) == CameraType.THIRD_PERSON_BACK,
                "taking the navigator's seat should switch to the third-person chase view, it is " + camera(context));

        // The pilot gets out: the navigator is now the pilot, in the same pod, and keeps the view.
        two.server().runOnServer(server -> two.mock().player().stopRiding());
        ClientWait.until(
                context,
                "the client moved up to the Prospector's pilot seat",
                client -> ClientSession.player(client).getVehicle() instanceof PodEntity pod
                        && PodSeat.find(pod, ClientSession.player(client)).equals(Optional.of(PodSeat.PILOT)));
        context.waitTicks(5);
        require(
                camera(context) == CameraType.THIRD_PERSON_BACK,
                "moving up from the navigator's seat to the pilot's should keep the chase view, it is "
                        + camera(context));

        two.server().runOnServer(server -> realPlayer(server, mock).stopRiding());
        ClientWait.until(
                context,
                "the client out of the Prospector",
                client -> !(ClientSession.player(client).getVehicle() instanceof PodEntity));
        context.waitTicks(3);
        require(
                camera(context) == CameraType.THIRD_PERSON_FRONT,
                "leaving the navigator's seat should restore the view the player had, it is " + camera(context));
    }

    /** Seats the real player in a new pod beside them, with no gravity, and waits for the client to chase. */
    private static void boardNewPod(ClientGameTestContext context, TwoPlayerServer two, UUID mock) {
        two.server().runOnServer(server -> {
            ServerPlayer real = realPlayer(server, mock);
            PodEntity pod = TestEntities.create(PodRegistry.POD, real.level());
            pod.setPos(real.position());
            pod.setNoGravity(true);
            real.level().addFreshEntity(pod);
            require(real.startRiding(pod), "the player could not mount the new pod");
        });
        ClientWait.until(
                context,
                "the client chasing a pod",
                client -> ClientSession.player(client).getVehicle() instanceof PodEntity
                        && client.options.getCameraType() == CameraType.THIRD_PERSON_BACK);
        context.waitTicks(3);
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
        context.getInput().holdKey(options -> options.keyDown);
        context.waitTicks(SETTLE_DRIVE_TICKS);
        context.getInput().releaseKey(options -> options.keyDown);
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
