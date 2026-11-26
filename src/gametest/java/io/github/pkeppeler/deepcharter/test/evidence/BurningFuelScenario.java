package io.github.pkeppeler.deepcharter.test.evidence;

import io.github.pkeppeler.deepcharter.attachment.Versioned;
import io.github.pkeppeler.deepcharter.client.charter.ClientCharter;
import io.github.pkeppeler.deepcharter.client.fuel.FuelPumpScreen;
import io.github.pkeppeler.deepcharter.client.pod.CockpitHud;
import io.github.pkeppeler.deepcharter.client.pod.PodStatusHud;
import io.github.pkeppeler.deepcharter.client.transmission.TransmissionOverlay;
import io.github.pkeppeler.deepcharter.fuel.EngineSoot;
import io.github.pkeppeler.deepcharter.fuel.FuelTuning;
import io.github.pkeppeler.deepcharter.handbook.ReadMarks;
import io.github.pkeppeler.deepcharter.pod.PodComponents;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.terminal.TerminalOpenPayload;
import io.github.pkeppeler.deepcharter.terminal.Terminals;
import io.github.pkeppeler.deepcharter.test.FuelPumpClientTest;
import io.github.pkeppeler.deepcharter.test.support.ClientLight;
import io.github.pkeppeler.deepcharter.test.support.ClientSession;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.DrawnText;
import io.github.pkeppeler.deepcharter.test.support.EvidencePins;
import io.github.pkeppeler.deepcharter.test.support.UseEntity;
import java.util.Set;
import java.util.function.Predicate;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;

/**
 * Evidence scenario "burning-fuel" (#1438), beside the colony's pump: the pilot feeds the pod three charcoal and the cockpit's plate says BURNING CHARCOAL 3 L;
 * the engine soots past the grace and the one-time line names the cause; the charcoal burns out and the plate's row goes, the plain readout saying BURNING PUMP
 * FUEL; then, with charcoal fed again and a sooted engine, the pump's screen says both after FILL UP. Each still at GUI scale 2 and 1, the cockpit's with a zoom of
 * the plate, eight times, and the plain readout's on a GUI too small for the Ribbon.
 */
public class BurningFuelScenario extends EvidenceScenario {
    private static final int HOLD_FRAMES = 8;
    private static final int TICKS_PER_FRAME = 3;
    /** The plate's corner of the screen: under the scanner's bezel, at the right. */
    private static final int PLATE_ZOOM_WIDTH = 160;

    private static final int PLATE_ZOOM_TOP = 118;
    private static final int PLATE_ZOOM_HEIGHT = 56;
    /** A GUI scale at which the evidence window's GUI is too small for the Ribbon, so the plain readout shows. */
    private static final int PLAIN_GUI_SCALE = 3;

    @Override
    protected String name() {
        return "burning-fuel";
    }

    private static PodEntity pod(MinecraftServer server) {
        if (server.getPlayerList().getPlayers().getFirst().getVehicle() instanceof PodEntity pod) {
            return pod;
        }
        throw new AssertionError("the player is not riding a pod");
    }

    /** What the client's readout says burns: empty until the player is seen riding, and for an empty tank. */
    private static String burning(Minecraft client) {
        return client.player != null && client.player.getVehicle() instanceof PodEntity pod
                ? PodStatusHud.burnsLine(pod).map(Component::getString).orElse("")
                : "";
    }

    private static void feed(ServerPlayer player, PodEntity pod, int charcoal) {
        for (int i = 0; i < charcoal; i++) {
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.CHARCOAL));
            UseEntity.on(player, pod.level(), InteractionHand.MAIN_HAND, pod);
        }
    }

    /** Puts {@code soot} litres in the engine and keeps the tank's account as it is. */
    private static void soot(PodEntity pod, float soot) {
        EngineSoot.State state = Versioned.orThrow(pod, EngineSoot.STATE);
        pod.setAttached(
                EngineSoot.STATE,
                Versioned.of(new EngineSoot.State(soot, state.tankLitres(), state.tankSoot(), state.solid())));
    }

    @Override
    protected void run(ClientGameTestContext context) {
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            FuelPumpClientTest.Scene scene = singleplayer.getServer().computeOnServer(FuelPumpClientTest::setUp);
            singleplayer.getServer().runOnServer(server -> {
                ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
                player.setGameMode(GameType.SURVIVAL);
                PodEntity pod = server.overworld()
                        .getEntitiesOfClass(
                                PodEntity.class,
                                player.getBoundingBox().inflate(6),
                                candidate -> PodComponents.ownerCharter(candidate)
                                        .map(owner -> owner.id().equals(scene.charter()))
                                        .orElse(false))
                        .getFirst();
                if (!player.startRiding(pod)) {
                    throw new AssertionError("the player could not board the pod");
                }
                feed(player, pod, 3);
            });
            EvidencePins.handsHidden = true;
            try {
                await(context, "BURNING CHARCOAL 3 L", client -> burning(client).equals("BURNING CHARCOAL 3 L"));
                cockpitStills(context, "burning-charcoal");

                // A quantum of charcoal burns past the grace: the one-time line, in the chat, names the cause.
                singleplayer.getServer().runOnServer(server -> {
                    PodEntity pod = pod(server);
                    soot(pod, FuelTuning.DEFAULT.soot().graceLitres() - 0.05f);
                    EngineSoot.burned(pod, EngineSoot.QUANTUM);
                });
                ClientWait.until(
                        context,
                        "the first soot line sent",
                        () -> singleplayer
                                .getServer()
                                .computeOnServer(server -> ReadMarks.isRead(
                                        server.getPlayerList().getPlayers().getFirst(), EngineSoot.FIRST_SOOT_MARK)),
                        () -> "the first soot mark is not set");
                hold(context);
                cockpitStills(context, "first-soot");
                context.runOnClient(client -> client.gui.hud.getChat().clearMessages(false));

                // The rest of the charcoal burns: pump fuel, and the plate's row goes.
                singleplayer.getServer().runOnServer(server -> {
                    PodEntity pod = pod(server);
                    EngineSoot.burned(pod, 3f);
                    EngineSoot.flush(pod);
                });
                await(context, "BURNING PUMP FUEL", client -> burning(client).equals("BURNING PUMP FUEL"));
                cockpitStills(context, "burning-pump-fuel");
                plainStill(context, "burning-pump-fuel-plain");

                // Charcoal again, and a sooted engine: the pump says both, after a fill as before it.
                singleplayer.getServer().runOnServer(server -> {
                    ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
                    PodEntity pod = pod(server);
                    feed(player, pod, 2);
                    soot(pod, 7.5f);
                });
                await(context, "BURNING CHARCOAL 2 L", client -> burning(client).equals("BURNING CHARCOAL 2 L"));
                plainStill(context, "burning-charcoal-plain");
                // Off the pod, and back where the pump's scene stood the player, in reach of the pump.
                singleplayer.getServer().runOnServer(server -> {
                    ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
                    player.stopRiding();
                    Vec3 stand = Vec3.atBottomCenterOf(scene.pump().relative(Direction.WEST, 2));
                    player.teleportTo(server.overworld(), stand.x, stand.y, stand.z, Set.of(), -90f, 0f, true);
                });
                ClientWait.until(
                        context,
                        "the player on foot",
                        client -> ClientSession.player(client).getVehicle() == null);
                context.runOnClient(client -> ClientPlayNetworking.send(new TerminalOpenPayload(scene.pump())));
                ClientWait.screen(context, FuelPumpScreen.class);
                ClientWait.until(
                        context,
                        "the charter view and a pod parked at the pump",
                        client -> ClientCharter.view().isPresent()
                                && !Terminals.parkedPods(ClientLight.clientLevel(client), scene.pump())
                                        .isEmpty());
                context.clickScreenButton("FILL UP");
                ClientWait.until(
                        context,
                        "the pump's two notices after the fill",
                        client -> client.gui.screen() instanceof FuelPumpScreen screen
                                && screen.typewriter().done()
                                && drawn(screen, "STILL IN THE TANK BURNS FIRST")
                                && drawn(screen, "SERVICE AT THE REPAIR STATION")
                                && Terminals.parkedPods(ClientLight.clientLevel(client), scene.pump())
                                                .getFirst()
                                                .fuel()
                                        >= 99f);
                for (int scale : new int[] {2, 1}) {
                    context.runOnClient(client -> client.options.guiScale().set(scale));
                    settle(context);
                    screenshot(context, "pump-notice-gui" + scale);
                }
                context.setScreen(() -> null);
                context.runOnClient(client -> client.options.guiScale().set(2));
            } finally {
                EvidencePins.reset();
            }
        }
    }

    /** Whether {@code screen} draws a string with {@code text} in it. */
    private static boolean drawn(FuelPumpScreen screen, String text) {
        return DrawnText.of(screen).stream().anyMatch(string -> string.text().contains(text));
    }

    private void await(ClientGameTestContext context, String what, Predicate<Minecraft> condition) {
        ClientWait.until(context, what, condition);
        hold(context);
    }

    /** Clears what lands over the HUD a few ticks after a change (a directive's toast, the welcome transmission). */
    private static void settle(ClientGameTestContext context) {
        context.waitTicks(2); // tick-wait: the frame after a scale change lays the GUI out again
        context.runOnClient(client -> {
            client.gui.toastManager().clear();
            TransmissionOverlay.reset();
        });
        context.waitTicks(2); // tick-wait: a frame without the cleared toast
    }

    /** The seated cockpit at GUI scale 2 and 1, each with a zoom of the plate. */
    private void cockpitStills(ClientGameTestContext context, String name) {
        for (int scale : new int[] {2, 1}) {
            context.runOnClient(client -> client.options.guiScale().set(scale));
            settle(context);
            ClientWait.until(context, "the Ribbon at GUI scale " + scale, CockpitHud::showsReadout);
            String still = name + "-gui" + scale;
            screenshot(context, still);
            int width = context.computeOnClient(client -> client.getWindow().getGuiScaledWidth());
            zoomStill(
                    context,
                    still,
                    still + "-zoom-plate",
                    width - PLATE_ZOOM_WIDTH,
                    PLATE_ZOOM_TOP,
                    PLATE_ZOOM_WIDTH,
                    PLATE_ZOOM_HEIGHT);
        }
        context.runOnClient(client -> client.options.guiScale().set(2));
    }

    /** The plain readout, which a GUI under the Ribbon's 320 x 240 shows: 284 x 160 here, the evidence window at GUI scale 3. */
    private void plainStill(ClientGameTestContext context, String name) {
        context.runOnClient(client -> client.getWindow().setGuiScale(PLAIN_GUI_SCALE));
        try {
            settle(context);
            ClientWait.until(context, "the plain readout", client -> !CockpitHud.showsReadout(client));
            screenshot(context, name);
        } finally {
            context.runOnClient(client -> client.getWindow().setGuiScale(2));
        }
    }

    private void hold(ClientGameTestContext context) {
        for (int i = 0; i < HOLD_FRAMES; i++) {
            context.waitTicks(TICKS_PER_FRAME);
            frame(context);
        }
    }
}
