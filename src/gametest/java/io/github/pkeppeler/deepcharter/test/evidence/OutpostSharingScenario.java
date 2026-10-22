package io.github.pkeppeler.deepcharter.test.evidence;

import io.github.pkeppeler.deepcharter.charter.Charters;
import io.github.pkeppeler.deepcharter.charter.terminal.ContractActions;
import io.github.pkeppeler.deepcharter.charter.terminal.ContractTerminal;
import io.github.pkeppeler.deepcharter.client.charter.ClientCharter;
import io.github.pkeppeler.deepcharter.client.charter.terminal.ContractScreen;
import io.github.pkeppeler.deepcharter.client.fuel.FuelPumpScreen;
import io.github.pkeppeler.deepcharter.client.transmission.TransmissionOverlay;
import io.github.pkeppeler.deepcharter.outpost.OutpostBuilder;
import io.github.pkeppeler.deepcharter.pod.PodStats;
import io.github.pkeppeler.deepcharter.terminal.TerminalOpenPayload;
import io.github.pkeppeler.deepcharter.terminal.Terminals;
import io.github.pkeppeler.deepcharter.test.support.ClientLight;
import io.github.pkeppeler.deepcharter.test.support.ClientSession;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.ContractShown;
import io.github.pkeppeler.deepcharter.test.support.MockPlayer;
import io.github.pkeppeler.deepcharter.test.support.MockPlayers;
import io.github.pkeppeler.deepcharter.test.support.OutpostScene;
import io.github.pkeppeler.deepcharter.test.support.OutpostScene.Scene;
import io.github.pkeppeler.deepcharter.test.support.TestLayers;
import java.util.Set;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;

/**
 * Evidence scenario "outpost-sharing" for #752. A second charter's Director has built an outpost at the layer 1 floor. The real player is
 * the Director of a charter with no outpost, with an empty pod parked in that hall. Stills: the guest refused at the pump while the owner
 * keeps the outposts to itself; a Director's contract screen showing the sharing button off, then on (the player's own charter,
 * which has no outpost: the same screen the owner has); then the owner's Director presses the real "open outposts" action and the guest
 * fills up at the outpost's price.
 */
public class OutpostSharingScenario extends EvidenceScenario {
    private static final int TICKS_PER_FRAME = 3;
    private static final int TYPING_FRAMES = 45;
    private static final int HOLD_FRAMES = 8;
    private static final float FACE_NORTH = 180f;
    private static final String HOST = "Deep Haulers Ltd";
    private static final BlockPos TERMINAL_OFFSET = new BlockPos(3, 1, 2);

    @Override
    protected String name() {
        return "outpost-sharing";
    }

    @Override
    protected void run(ClientGameTestContext context) {
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            context.waitTicks(40);
            Scene scene = singleplayer.getServer().computeOnServer(OutpostScene::stage);
            BlockPos terminal = scene.floor().offset(TERMINAL_OFFSET);
            singleplayer.getServer().runOnServer(server -> {
                ServerLevel one = TestLayers.level(server, 1);
                ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
                // Out of the hall's volume while the owner's Director builds it.
                player.setGameMode(GameType.CREATIVE);
                player.teleportTo(
                        one,
                        scene.floor().getX() + 7.5,
                        scene.floor().getY() + 3,
                        scene.floor().getZ() + 7.5,
                        Set.of(),
                        0f,
                        0f,
                        true);
                player.getInventory().setItem(player.getInventory().getSelectedSlot(), ItemStack.EMPTY);
                MockPlayer owner = MockPlayers.join(
                        server,
                        "HostDirector",
                        one,
                        Vec3.atBottomCenterOf(scene.floor().above()),
                        FACE_NORTH);
                if (Charters.found(server, owner.player().getUUID(), HOST).isPresent()) {
                    throw new AssertionError("founding the owner's charter should succeed");
                }
                owner.player().setGameMode(GameType.SURVIVAL);
                if (OutpostBuilder.build(owner.player(), one, scene.floor(), Direction.UP)
                        .isPresent()) {
                    throw new AssertionError("the owner's outpost should be built");
                }
                one.setBlock(terminal, ContractTerminal.TYPE.block().defaultBlockState(), 3);
                owner.teleportTo(
                        one, new Vec3(terminal.getX() - 0.5, terminal.getY(), terminal.getZ() - 0.5), FACE_NORTH, 0f);
                player.setGameMode(GameType.SURVIVAL);
                OutpostScene.standIn(server, scene, 0.5, 0.5, FACE_NORTH, 5f);
                OutpostScene.parkPod(server, scene, -3.0, 0.0, 0f);
            });
            ClientWait.until(
                    context,
                    "the player in layer 1 in the built hall",
                    client -> client.player != null
                            && TestLayers.isIn(ClientLight.clientLevel(client), 1)
                            && ClientCharter.view().isPresent()
                            && !ClientLight.clientLevel(client)
                                    .getBlockState(scene.pump())
                                    .isAir());
            context.waitTicks(40);
            clearTransmissions(context);
            hold(context);
            screenshot(context, "sharing-1-hall");

            // The guest is refused at the pump: the outposts of the owner are closed.
            openPump(context, scene);
            context.clickScreenButton("FILL UP");
            context.waitTicks(6);
            context.setScreen(() -> null);
            context.waitTicks(4);
            clearTransmissions(context);
            hold(context);
            screenshot(context, "sharing-2-guest-refused");
            requireBalance(context, OutpostScene.START_BALANCE, "a refused fill takes no money");

            // A Director's contract screen: the setting, closed, and the press that opens it.
            context.runOnClient(client -> ClientPlayNetworking.send(new TerminalOpenPayload(terminal)));
            ClientWait.screen(context, ContractScreen.class);
            ContractScreen screen =
                    context.computeOnClient(client -> ClientSession.screen(client, ContractScreen.class));
            awaitButton(context, "SHARE OUTPOSTS: OFF");
            for (int i = 0; i < TYPING_FRAMES && !screen.typewriter().done(); i++) {
                context.waitTicks(TICKS_PER_FRAME);
                frame(context);
            }
            hold(context);
            screenshot(context, "sharing-3-contract-closed");
            context.clickScreenButton("SHARE OUTPOSTS: OFF");
            awaitButton(context, "SHARE OUTPOSTS: ON");
            hold(context);
            screenshot(context, "sharing-4-contract-open");
            context.clickScreenButton("SHARE OUTPOSTS: ON");
            awaitButton(context, "SHARE OUTPOSTS: OFF");
            context.setScreen(() -> null);

            // The owner's Director opens the outposts, as the same screen's button does for their charter.
            singleplayer.getServer().runOnServer(server -> {
                ServerPlayer owner = server.getPlayerList().getPlayerByName("HostDirector");
                if (owner == null
                        || Terminals.act(owner, terminal, ContractActions.SHARE_OUTPOSTS, new CompoundTag())
                                .isPresent()) {
                    throw new AssertionError("the owner's Director should open the outposts");
                }
            });
            context.waitTicks(10);

            // The guest is served, at the outpost's $2 a litre, and the owner takes no cut.
            long tank = singleplayer
                    .getServer()
                    .computeOnServer(server -> Math.round(PodStats.base().tankLitres()));
            openPump(context, scene);
            context.clickScreenButton("FILL UP");
            requireBalance(context, OutpostScene.START_BALANCE - 2 * tank, "a guest fills at the outpost's price");
            hold(context);
            screenshot(context, "sharing-5-guest-served");
        }
    }

    private void openPump(ClientGameTestContext context, Scene scene) {
        context.runOnClient(client -> ClientPlayNetworking.send(new TerminalOpenPayload(scene.pump())));
        ClientWait.screen(context, FuelPumpScreen.class);
        FuelPumpScreen pump = context.computeOnClient(client -> ClientSession.screen(client, FuelPumpScreen.class));
        for (int i = 0; i < TYPING_FRAMES && !pump.typewriter().done(); i++) {
            context.waitTicks(TICKS_PER_FRAME);
            frame(context);
        }
    }

    private static void awaitButton(ClientGameTestContext context, String label) {
        ClientWait.until(
                context,
                "the contract screen to offer " + label,
                client -> client.gui.screen() instanceof ContractScreen shown && ContractShown.hasButton(shown, label),
                client -> String.valueOf(client.gui.screen()));
    }

    private static void requireBalance(ClientGameTestContext context, long expected, String what) {
        ClientWait.until(
                context,
                "the balance " + expected + " (" + what + ")",
                client -> ClientCharter.view()
                        .map(view -> view.balance() == expected)
                        .orElse(false),
                client -> "charter " + ClientCharter.view());
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
