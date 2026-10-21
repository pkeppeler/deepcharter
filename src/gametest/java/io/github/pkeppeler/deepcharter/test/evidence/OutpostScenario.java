package io.github.pkeppeler.deepcharter.test.evidence;

import io.github.pkeppeler.deepcharter.client.charter.ClientCharter;
import io.github.pkeppeler.deepcharter.client.fuel.FuelPumpScreen;
import io.github.pkeppeler.deepcharter.client.market.OreProcessorScreen;
import io.github.pkeppeler.deepcharter.client.transmission.TransmissionOverlay;
import io.github.pkeppeler.deepcharter.layer.BreachService;
import io.github.pkeppeler.deepcharter.layer.LayerChain;
import io.github.pkeppeler.deepcharter.layer.LayerTuning;
import io.github.pkeppeler.deepcharter.ore.OreRegistry;
import io.github.pkeppeler.deepcharter.ore.OreType;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodStats;
import io.github.pkeppeler.deepcharter.terminal.TerminalOpenPayload;
import io.github.pkeppeler.deepcharter.test.support.ClientLight;
import io.github.pkeppeler.deepcharter.test.support.ClientSession;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.OutpostScene;
import io.github.pkeppeler.deepcharter.test.support.OutpostScene.Scene;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.CameraType;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * Evidence scenario "layer-1-outpost" for #320: a charter's pilot stands at the bottom of the shaft on the layer 1 floor and uses an outpost
 * kit. A lit steel hall unfolds with a fuel pump and a sell terminal. A pod with an empty tank fills up at the pump at $2 a litre, the
 * ore of the pilot and the pod sells at 80 percent, and the pod drills the crust in the middle of the floor and drops into layer 2 with a full tank.
 */
public class OutpostScenario extends EvidenceScenario {
    private static final int TICKS_PER_FRAME = 3;
    private static final int TYPING_FRAMES = 45;
    private static final int HOLD_FRAMES = 8;
    private static final float FACE_NORTH = 180f;
    private static final float LOOK_DOWN = 35f;
    private static final int DROP_FRAME_LIMIT = 80;

    @Override
    protected String name() {
        return "layer-1-outpost";
    }

    @Override
    protected void run(ClientGameTestContext context) {
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            context.waitTicks(40);
            Scene scene = singleplayer.getServer().computeOnServer(OutpostScene::stage);
            ClientWait.until(
                    context,
                    "the player in layer 1 with the charter's balance",
                    client -> client.player != null
                            && ClientLight.clientLevel(client).dimension().equals(LayerChain.dimension(1))
                            && ClientCharter.view().isPresent());
            singleplayer.getServer().runOnServer(server -> {
                ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
                player.setXRot(LOOK_DOWN);
            });
            context.waitTicks(40);
            // Founding the charter queued the employer's transmission; it would cover the view this scenario shows.
            clearTransmissions(context);
            hold(context);
            screenshot(context, "outpost-1-shaft-bottom");

            singleplayer.getServer().runOnServer(server -> OutpostScene.build(server, scene));
            context.waitTicks(10);
            singleplayer.getServer().runOnServer(server -> {
                OutpostScene.standIn(server, scene, 0.5, 3.5, FACE_NORTH, 8f);
                PodEntity pod = OutpostScene.parkPod(server, scene, 3.0, 0.5, 0f);
                pod.setYRot(FACE_NORTH);
                ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
                for (int i = 0; i < 3; i++) {
                    player.getInventory().add(OreRegistry.stack(OreType.SILVERIUM));
                    player.getInventory().add(OreRegistry.stack(OreType.IRONIUM));
                }
            });
            ClientWait.until(
                    context,
                    "the hall's chunks and the pod",
                    client -> ClientLight.clientLevel(client)
                            .getBlockState(
                                    ClientSession.player(client).blockPosition().above(3))
                            .isAir());
            context.waitTicks(30);
            clearTransmissions(context);
            hold(context);
            screenshot(context, "outpost-2-hall");
            singleplayer
                    .getServer()
                    .runOnServer(server -> OutpostScene.standIn(server, scene, 0.5, 0.5, FACE_NORTH, 5f));
            context.waitTicks(10);

            context.runOnClient(client -> ClientPlayNetworking.send(new TerminalOpenPayload(scene.pump())));
            ClientWait.screen(context, FuelPumpScreen.class);
            FuelPumpScreen pump = context.computeOnClient(client -> ClientSession.screen(client, FuelPumpScreen.class));
            for (int i = 0; i < TYPING_FRAMES && !pump.typewriter().done(); i++) {
                context.waitTicks(TICKS_PER_FRAME);
                frame(context);
            }
            hold(context);
            screenshot(context, "outpost-3-pump");
            long tank = singleplayer
                    .getServer()
                    .computeOnServer(server -> Math.round(PodStats.base().tankLitres()));
            long after = OutpostScene.START_BALANCE - 2 * tank;
            context.clickScreenButton("FILL UP");
            ClientWait.until(
                    context,
                    "the balance after a fill at $2 a litre",
                    client -> ClientCharter.view()
                            .map(view -> view.balance() == after)
                            .orElse(false),
                    client -> "charter " + ClientCharter.view());
            hold(context);
            screenshot(context, "outpost-4-pump-full");
            context.setScreen(() -> null);

            context.runOnClient(client -> ClientPlayNetworking.send(new TerminalOpenPayload(scene.processor())));
            ClientWait.screen(context, OreProcessorScreen.class);
            OreProcessorScreen processor =
                    context.computeOnClient(client -> ClientSession.screen(client, OreProcessorScreen.class));
            for (int i = 0; i < TYPING_FRAMES && !processor.typewriter().done(); i++) {
                context.waitTicks(TICKS_PER_FRAME);
                frame(context);
            }
            hold(context);
            screenshot(context, "outpost-5-processor");
            long sold = 3L * (OreType.SILVERIUM.value() * 80 / 100) + 3L * (OreType.IRONIUM.value() * 80 / 100);
            context.clickScreenButton("SELL ALL CARRIED ORE");
            ClientWait.until(
                    context,
                    "the balance after the sale at 80 percent",
                    client -> ClientCharter.view()
                            .map(view -> view.balance() == after + sold)
                            .orElse(false),
                    client -> "charter " + ClientCharter.view());
            hold(context);
            screenshot(context, "outpost-6-sold");
            context.setScreen(() -> null);

            // The pilot boards and drills the crust of the breach in the middle of the floor.
            context.runOnClient(client -> client.options.setCameraType(CameraType.THIRD_PERSON_BACK));
            singleplayer.getServer().runOnServer(server -> {
                ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
                ServerLevel one = ClientLight.serverLevel(server, LayerChain.dimension(1));
                PodEntity pod = one.getEntitiesOfClass(
                                PodEntity.class, player.getBoundingBox().inflate(8))
                        .getFirst();
                pod.setPos(
                        scene.floor().getX() + 1.0,
                        scene.floor().getY() + 1,
                        scene.floor().getZ() + 1.0);
                if (!player.startRiding(pod)) {
                    throw new AssertionError("the pilot could not mount the pod");
                }
                for (int dx = 0; dx <= 1; dx++) {
                    for (int dz = 0; dz <= 1; dz++) {
                        for (int dy = 0; dy < LayerTuning.DEFAULT.crustThickness(); dy++) {
                            BreachService.breakCrust(one, scene.floor().offset(dx, -dy, dz));
                        }
                    }
                }
            });
            for (int i = 0; i < DROP_FRAME_LIMIT; i++) {
                context.waitTicks(2);
                frame(context);
                boolean down = context.computeOnClient(client ->
                        client.level != null && client.level.dimension().equals(LayerChain.dimension(2)));
                if (down) {
                    break;
                }
            }
            ClientWait.until(
                    context,
                    "the client in layer 2",
                    client -> client.level != null && client.level.dimension().equals(LayerChain.dimension(2)));
            context.waitTicks(40);
            clearTransmissions(context);
            hold(context);
            screenshot(context, "outpost-7-layer-2-full-tank");
        }
    }

    private static void clearTransmissions(ClientGameTestContext context) {
        context.runOnClient(client -> TransmissionOverlay.reset());
    }

    private void hold(ClientGameTestContext context) {
        for (int i = 0; i < HOLD_FRAMES; i++) {
            context.waitTicks(TICKS_PER_FRAME);
            frame(context);
        }
    }
}
