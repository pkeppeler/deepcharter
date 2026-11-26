package io.github.pkeppeler.deepcharter.test.evidence;

import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.test.support.ClientSession;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.EvidencePins;
import io.github.pkeppeler.deepcharter.test.support.EvidenceWorld;
import io.github.pkeppeler.deepcharter.test.support.TestEntities;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.CameraType;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/**
 * Evidence scenario "parked-no-fuel" for #1434: a full pod's fuel gauge from the seat, the pilot gets out and leaves
 * it parked for a minute, and the gauge still reads full when they climb back in; then a minute idling with the pilot
 * aboard, which burns. Stills: {@code aboard-full}, {@code parked}, {@code back-after-a-parked-minute} and
 * {@code after-a-minute-aboard}.
 */
public class ParkedNoFuelScenario extends EvidenceScenario {
    private static final int MINUTE_TICKS = 1200;
    private static final int FRAMES_A_MINUTE = 20;
    private static final int SETTLE_TICKS = 20;
    /** How far behind the pod the pilot stands once out, so the parked pod fills the view. */
    private static final double STEP_BACK = 4.5;

    @Override
    protected String name() {
        return "parked-no-fuel";
    }

    @Override
    protected void run(ClientGameTestContext context) {
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            EvidenceWorld.pin(context, singleplayer);
            context.runOnClient(client -> client.options.setCameraType(CameraType.FIRST_PERSON));
            EvidencePins.handsHidden = true;
            try {
                PodEntity pod = singleplayer.getServer().computeOnServer(server -> {
                    ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
                    PodEntity created = TestEntities.create(PodRegistry.POD, player.level());
                    created.setPos(player.position());
                    created.setYRot(player.getYRot());
                    player.level().addFreshEntity(created);
                    return created;
                });
                board(context, singleplayer, pod);
                screenshot(context, "aboard-full");
                float full = fuel(singleplayer, pod);

                singleplayer.getServer().runOnServer(server -> {
                    ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
                    player.stopRiding();
                    Vec3 back = pod.position().add(pod.getLookAngle().scale(-STEP_BACK));
                    player.teleportTo(back.x, pod.getY(), back.z);
                    player.lookAt(EntityAnchorArgument.Anchor.EYES, pod.position());
                });
                ClientWait.until(
                        context,
                        "the pilot on foot",
                        client -> ClientSession.player(client).getVehicle() == null);
                context.waitTicks(SETTLE_TICKS); // tick-wait: the camera settles behind the pod before the still
                screenshot(context, "parked");
                minute(context);
                float parked = fuel(singleplayer, pod);
                if (parked != full) {
                    throw new AssertionError(
                            "a minute parked with nobody aboard burned fuel: " + full + " to " + parked);
                }

                board(context, singleplayer, pod);
                screenshot(context, "back-after-a-parked-minute");
                minute(context);
                screenshot(context, "after-a-minute-aboard");
                float aboard = fuel(singleplayer, pod);
                if (aboard >= full) {
                    throw new AssertionError(
                            "a minute idling with the pilot aboard should burn fuel, it reads " + aboard);
                }
            } finally {
                EvidencePins.reset();
            }
        }
    }

    private static void board(ClientGameTestContext context, TestSingleplayerContext singleplayer, PodEntity pod) {
        singleplayer.getServer().runOnServer(server -> {
            if (!server.getPlayerList().getPlayers().getFirst().startRiding(pod)) {
                throw new AssertionError("the player could not board the pod");
            }
        });
        ClientWait.until(
                context,
                "the player riding the pod",
                client -> ClientSession.player(client).getVehicle() instanceof PodEntity);
        context.waitTicks(SETTLE_TICKS); // tick-wait: the ribbon's gauges settle before the still
    }

    /** A minute of game time, a frame every few seconds of it. */
    private void minute(ClientGameTestContext context) {
        for (int i = 0; i < FRAMES_A_MINUTE; i++) {
            // tick-wait: the minute is game time, which is what fuel burns by
            context.waitTicks(MINUTE_TICKS / FRAMES_A_MINUTE);
            frame(context);
        }
    }

    private static float fuel(TestSingleplayerContext singleplayer, PodEntity pod) {
        return singleplayer.getServer().computeOnServer(server -> pod.fuel());
    }
}
