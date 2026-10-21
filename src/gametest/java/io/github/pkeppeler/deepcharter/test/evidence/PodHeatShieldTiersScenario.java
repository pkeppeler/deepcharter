package io.github.pkeppeler.deepcharter.test.evidence;

import static io.github.pkeppeler.deepcharter.test.evidence.PodHeatShieldScenario.X;
import static io.github.pkeppeler.deepcharter.test.evidence.PodHeatShieldScenario.Z;

import io.github.pkeppeler.deepcharter.client.transmission.TransmissionOverlay;
import io.github.pkeppeler.deepcharter.client.upgrade.UpgradeScreen;
import io.github.pkeppeler.deepcharter.ore.SlagBrick;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodLining;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.terminal.TerminalOpenPayload;
import io.github.pkeppeler.deepcharter.test.UpgradeTerminalClientTest;
import io.github.pkeppeler.deepcharter.test.support.ClientLight;
import io.github.pkeppeler.deepcharter.test.support.ClientSession;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.ScannerPods;
import io.github.pkeppeler.deepcharter.test.support.TestEntities;
import io.github.pkeppeler.deepcharter.test.support.TestLayers;
import io.github.pkeppeler.deepcharter.test.support.UpgradeShown;
import io.github.pkeppeler.deepcharter.upgrade.ComponentTrack;
import java.util.Set;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.CameraType;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;

/**
 * Evidence scenario "pod-heat-shield-tiers" (#712): the upgrade terminal lists the heat shield's tiers with their prices, a Mole buys tier 2 and a tier 3 it works as tier 2.
 * Then a Prospector with a tier 3 shield, a rack of 4 bricks and a pack of 16 sits in lava: the HUD counts the bricks down, and the pack burns while the rack stays at 4.
 */
public class PodHeatShieldTiersScenario extends EvidenceScenario {
    /** Well above the layer floor, where the console would lose its signal. */
    private static final int FLOOR_Y = LAYER_ROOM_Y;

    private static final int TICKS_PER_FRAME = 4;
    private static final int HOLD_FRAMES = 8;
    private static final int MAX_TICKS = 900;
    private static final int RACK = 4;
    private static final int PACK = 16;
    private static final float LOOK_DOWN = 25f;

    @Override
    protected String name() {
        return "pod-heat-shield-tiers";
    }

    @Override
    protected void run(ClientGameTestContext context) {
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            UpgradeTerminalClientTest.Scene scene =
                    singleplayer.getServer().computeOnServer(UpgradeTerminalClientTest::setUp);
            context.runOnClient(client -> ClientPlayNetworking.send(new TerminalOpenPayload(scene.terminal())));
            ClientWait.screen(context, UpgradeScreen.class);
            UpgradeScreen screen = context.computeOnClient(client -> ClientSession.screen(client, UpgradeScreen.class));
            ClientWait.until(
                    context,
                    "the upgrade screen finished typing",
                    client -> screen.typewriter().done(),
                    client -> "typewriter text '" + screen.typewriter().text() + "'");
            context.runOnClient(client -> UpgradeShown.reveal(screen, ComponentTrack.HEAT_SHIELD));
            context.clickScreenButton("HEAT SHIELD  T0");
            hold(context);
            screenshot(context, "heat-shield-prices");
            context.clickScreenButton("BUY TIER 2  $1250");
            ClientWait.until(
                    context,
                    "the heat shield tier 2 installed",
                    client -> screen.upgrade().orElseThrow().pod().orElseThrow().slots().stream()
                            .anyMatch(slot -> slot.track() == ComponentTrack.HEAT_SHIELD && slot.installed() == 2));
            hold(context);
            screenshot(context, "heat-shield-tier-2");
            context.clickScreenButton("BUY TIER 3  $2500  (WORKS AS TIER 2)");
            ClientWait.until(
                    context,
                    "the heat shield tier 3 installed",
                    client -> screen.upgrade().orElseThrow().pod().orElseThrow().slots().stream()
                            .anyMatch(slot -> slot.track() == ComponentTrack.HEAT_SHIELD && slot.installed() == 3));
            hold(context);
            screenshot(context, "heat-shield-tier-3-on-a-mole");
            context.setScreen(() -> null);

            singleplayer.getServer().runOnServer(server -> {
                ServerLevel one = TestLayers.level(server, 1);
                PodHeatShieldScenario.carveRoom(one, FLOOR_Y);
                ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
                player.setGameMode(GameType.SURVIVAL);
                player.teleportTo(one, X, FLOOR_Y, Z, Set.of(), 0, LOOK_DOWN, true);
                PodEntity pod = TestEntities.create(PodRegistry.PROSPECTOR, one);
                pod.setPos(X, FLOOR_Y, Z);
                one.addFreshEntity(pod);
                ScannerPods.fit(server, player, pod, ComponentTrack.HEAT_SHIELD, 3);
                PodLining.modify(pod, state -> new PodLining.State(0, RACK, 0, false, false));
                player.getInventory().add(new ItemStack(SlagBrick.item(), PACK));
                if (!player.startRiding(pod)) {
                    throw new AssertionError("the player could not mount the pod");
                }
            });
            ClientWait.until(
                    context,
                    "the pilot seated in the Prospector in layer 1",
                    client -> client.player != null
                            && client.player.getVehicle() instanceof PodEntity
                            && TestLayers.isIn(ClientLight.clientLevel(client), 1));
            context.runOnClient(client -> {
                client.options.setCameraType(CameraType.THIRD_PERSON_BACK);
                ClientSession.player(client).setXRot(LOOK_DOWN);
            });
            context.waitTicks(30);
            context.runOnClient(client -> TransmissionOverlay.reset());
            for (int i = 0; i < 6; i++) {
                frame(context);
                context.waitTicks(TICKS_PER_FRAME);
            }
            screenshot(context, "pod-heat-shield-tiers-before");
            singleplayer.getServer().runOnServer(server -> {
                ServerLevel one = TestLayers.level(server, 1);
                for (int x = X - 2; x <= X + 2; x++) {
                    for (int z = Z - 2; z <= Z + 2; z++) {
                        one.setBlock(new BlockPos(x, FLOOR_Y, z), Blocks.LAVA.defaultBlockState(), 3);
                    }
                }
            });
            ClientWait.until(
                    context,
                    "the glaze holding",
                    client -> ClientSession.player(client).getVehicle() instanceof PodEntity pod
                            && pod.hullBurning()
                            && pod.glazeBricks() > 0);
            context.runOnClient(client -> TransmissionOverlay.reset());
            int ticks = 0;
            boolean shot = false;
            while (rackAndPack(singleplayer)[1] > 0 && ticks < MAX_TICKS) {
                context.waitTicks(TICKS_PER_FRAME);
                ticks += TICKS_PER_FRAME;
                frame(context);
                if (!shot && rackAndPack(singleplayer)[1] <= PACK / 2) {
                    shot = true;
                    screenshot(context, "pod-heat-shield-tiers-pack-first");
                }
            }
            int[] left = rackAndPack(singleplayer);
            if (!shot || left[0] != RACK || PodHeatShieldScenario.podHull(singleplayer) < 99f) {
                throw new AssertionError("A tier 3 shield should burn the pack and keep the rack and the hull: rack "
                        + left[0] + ", pack " + left[1] + ", hull " + PodHeatShieldScenario.podHull(singleplayer));
            }
            for (int i = 0; i < 6; i++) {
                context.waitTicks(TICKS_PER_FRAME);
                frame(context);
            }
        }
    }

    private void hold(ClientGameTestContext context) {
        for (int i = 0; i < HOLD_FRAMES; i++) {
            context.waitTicks(TICKS_PER_FRAME);
            frame(context);
        }
    }

    /** The bricks in the pod's rack and in the pilot's pack. */
    private static int[] rackAndPack(TestSingleplayerContext singleplayer) {
        return singleplayer.getServer().computeOnServer(server -> {
            ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
            return new int[] {
                PodLining.of(TestEntities.vehicle(player, PodEntity.class)).bricks(),
                player.getInventory().countItem(SlagBrick.item())
            };
        });
    }
}
