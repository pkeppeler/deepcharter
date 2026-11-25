package io.github.pkeppeler.deepcharter.test.evidence;

import io.github.pkeppeler.deepcharter.attachment.Versioned;
import io.github.pkeppeler.deepcharter.charter.Charters;
import io.github.pkeppeler.deepcharter.client.repair.RepairStationScreen;
import io.github.pkeppeler.deepcharter.fuel.EngineSoot;
import io.github.pkeppeler.deepcharter.fuel.FuelTuning;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.terminal.TerminalOpenPayload;
import io.github.pkeppeler.deepcharter.test.RepairStationClientTest;
import io.github.pkeppeler.deepcharter.test.support.ClientChecks;
import io.github.pkeppeler.deepcharter.test.support.ClientSession;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.ScreenScroll;
import io.github.pkeppeler.deepcharter.test.support.UseEntity;
import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;

/**
 * Evidence scenario "fuel-soot" for #1280: a player carries two stacks of coal (the Ore/Fuel status), refuels the pod
 * with charcoal, and the engine soots: the sooted line, then limp mode. At the colony a pod in limp mode will not
 * launch (sprint drills; the line says why). At the repair station with no money, the paid row has nothing to press
 * and SCRAPE FLUE, free, counts down a minute and leaves the engine just under the limit. A first haul's money buys a
 * part service in whole litres, more money the rest, and the pod drills again.
 */
public class FuelSootScenario extends EvidenceScenario {
    private static final int TICKS_PER_FRAME = 3;
    private static final int HOLD_FRAMES = 8;
    /** Frames over the minute of the flue scrape, one every 1.5 s of it. */
    private static final int SCRAPE_FRAMES = 40;

    @Override
    protected String name() {
        return "fuel-soot";
    }

    private static void soot(TestSingleplayerContext singleplayer, float litres) {
        singleplayer.getServer().runOnServer(server -> {
            PodEntity pod = podOf(server.getPlayerList().getPlayers().getFirst());
            pod.setAttached(EngineSoot.STATE, Versioned.of(new EngineSoot.State(litres, 0f, 0f)));
        });
    }

    private static PodEntity podOf(ServerPlayer player) {
        if (player.getVehicle() instanceof PodEntity pod) {
            return pod;
        }
        throw new AssertionError("the player is not riding a pod");
    }

    @Override
    protected void run(ClientGameTestContext context) {
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            context.waitTicks(40);
            RepairStationClientTest.Scene scene =
                    singleplayer.getServer().computeOnServer(RepairStationClientTest::setUp);
            singleplayer.getServer().runOnServer(server -> {
                ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
                player.setGameMode(GameType.SURVIVAL);
                player.getInventory().add(new ItemStack(Items.COAL, 16));
                player.getInventory().add(new ItemStack(Items.COAL, 16));
                scene.pod().setHull(scene.pod().maxHull());
            });
            // The weight status, on foot.
            ClientWait.until(
                    context,
                    "the Ore/Fuel status on the client",
                    client -> ClientSession.player(client).getActiveEffects().stream()
                            .anyMatch(effect -> effect.getEffect()
                                    .value()
                                    .getDescriptionId()
                                    .contains("overburdened")));
            hold(context);
            screenshot(context, "ore-fuel-status");
            // The status's name is in the inventory's effect list.
            context.getInput().pressKey(options -> options.keyInventory);
            context.waitTicks(10);
            hold(context);
            screenshot(context, "ore-fuel-status-inventory");
            context.getInput().pressKey(options -> options.keyInventory);
            context.waitTicks(10);

            // Board the pod, empty its tank, and refuel it with ten charcoal, the real way.
            singleplayer.getServer().runOnServer(server -> {
                ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
                PodEntity pod = scene.pod();
                player.startRiding(pod, true, false);
                pod.setFuel(0f);
                for (int i = 0; i < 10; i++) {
                    player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.CHARCOAL));
                    UseEntity.on(player, pod.level(), InteractionHand.MAIN_HAND, pod);
                }
            });
            ClientWait.until(
                    context,
                    "the player riding the refuelled pod",
                    client -> client.player != null
                            && client.player.getVehicle() instanceof PodEntity pod
                            && pod.fuel() > 90f);
            hold(context);
            screenshot(context, "charcoal-refuelled");

            float limp = FuelTuning.DEFAULT.soot().limpLitres();
            soot(singleplayer, 7.5f);
            ClientWait.until(
                    context,
                    "the sooted line",
                    client -> client.player != null
                            && client.player.getVehicle() instanceof PodEntity pod
                            && EngineSoot.soot(pod) == 7.5f);
            hold(context);
            screenshot(context, "sooted");

            soot(singleplayer, limp + 1);
            ClientWait.until(
                    context,
                    "the limp line",
                    client -> client.player != null
                            && client.player.getVehicle() instanceof PodEntity pod
                            && EngineSoot.isLimp(pod));
            hold(context);
            screenshot(context, "limp");

            // Sprint is drill: at the colony, in limp mode, it will not launch.
            context.getInput().holdKey(options -> options.keySprint);
            for (int i = 0; i < 20; i++) {
                context.waitTicks(TICKS_PER_FRAME);
                frame(context);
            }
            screenshot(context, "launch-refused");
            context.getInput().releaseKey(options -> options.keySprint);

            // At the station with no money: the paid row has nothing to press, the free scrape does.
            singleplayer.getServer().runOnServer(server -> {
                ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
                player.stopRiding();
                long account = Charters.findOrThrow(server, scene.charter().id())
                        .orElseThrow()
                        .account();
                Charters.spend(server, scene.charter().id(), account).ifPresent(refusal -> {
                    throw new AssertionError("emptying the account: " + refusal);
                });
            });
            context.waitTicks(10);
            context.runOnClient(client -> ClientPlayNetworking.send(new TerminalOpenPayload(scene.station())));
            ClientWait.screen(context, RepairStationScreen.class);
            RepairStationScreen screen =
                    context.computeOnClient(client -> ClientSession.screen(client, RepairStationScreen.class));
            // Rows: three hull repairs and REPAIR ALL, then the service and the scrape.
            context.runOnClient(client -> ScreenScroll.scrollTo(screen, 2));
            ClientWait.until(
                    context,
                    "the broke station's soot rows",
                    client -> labels(screen).contains("SERVICE: $10 A LITRE")
                            && labels(screen).contains("SCRAPE FLUE (FREE, SLOW)"));
            hold(context);
            screenshot(context, "broke-rows");

            // The free scrape: a minute's wait at the station, counted down on the row.
            RepairStationClientTest.clickRow(context, "SCRAPE FLUE (FREE, SLOW)");
            float leaves = FuelTuning.DEFAULT.soot().scrapeLeavesLitres();
            for (int i = 0; i < SCRAPE_FRAMES; i++) {
                context.waitTicks(FuelTuning.DEFAULT.soot().scrapeTicks() / SCRAPE_FRAMES);
                frame(context);
                if (i == 2) {
                    screenshot(context, "scraping");
                }
            }
            ClientWait.until(
                    context,
                    "the flue scraped",
                    () -> singleplayer.getServer().computeOnServer(server -> EngineSoot.soot(scene.pod()) == leaves),
                    () -> "soot " + singleplayer.getServer().computeOnServer(server -> EngineSoot.soot(scene.pod())));
            hold(context);
            screenshot(context, "scraped");

            // A first haul's money: a part service buys the whole litres it pays for.
            deposit(singleplayer, scene, 35);
            String part = "SERVICE 3/" + Math.round(leaves) + " L ($30)";
            ClientWait.until(
                    context, "the part service row", client -> labels(screen).contains(part));
            context.runOnClient(client -> ScreenScroll.scrollTo(screen, 2));
            hold(context);
            screenshot(context, "part-service-row");
            RepairStationClientTest.clickRow(context, part);
            ClientWait.until(
                    context,
                    "the part service",
                    () -> singleplayer
                            .getServer()
                            .computeOnServer(server -> EngineSoot.soot(scene.pod()) == leaves - 3),
                    () -> "soot " + singleplayer.getServer().computeOnServer(server -> EngineSoot.soot(scene.pod())));
            hold(context);
            screenshot(context, "part-serviced");

            // More money: the rest, for a clean engine.
            deposit(singleplayer, scene, 1_000);
            String rest = "SERVICE ALL " + Math.round(leaves - 3) + " L ($"
                    + Math.round(leaves - 3) * FuelTuning.DEFAULT.soot().servicePerLitre() + ")";
            ClientWait.until(
                    context, "the full service row", client -> labels(screen).contains(rest));
            RepairStationClientTest.clickRow(context, rest);
            ClientWait.until(
                    context,
                    "the engine serviced",
                    () -> singleplayer.getServer().computeOnServer(server -> EngineSoot.soot(scene.pod()) == 0f),
                    () -> "soot " + singleplayer.getServer().computeOnServer(server -> EngineSoot.soot(scene.pod())));
            hold(context);
            screenshot(context, "serviced");
            context.setScreen(() -> null);

            // Board again: the pod launches.
            singleplayer
                    .getServer()
                    .runOnServer(server ->
                            server.getPlayerList().getPlayers().getFirst().startRiding(scene.pod(), true, false));
            context.waitTicks(10);
            context.getInput().holdKey(options -> options.keySprint);
            for (int i = 0; i < 20; i++) {
                context.waitTicks(TICKS_PER_FRAME);
                frame(context);
            }
            screenshot(context, "drills-again");
            context.getInput().releaseKey(options -> options.keySprint);
        }
    }

    private static void deposit(
            TestSingleplayerContext singleplayer, RepairStationClientTest.Scene scene, long amount) {
        singleplayer
                .getServer()
                .runOnServer(server -> Charters.deposit(server, scene.charter().id(), amount)
                        .ifPresent(refusal -> {
                            throw new AssertionError("funding: " + refusal);
                        }));
    }

    /** The labels of the rows the station shows now. */
    private static List<String> labels(RepairStationScreen screen) {
        return ClientChecks.buttons(screen).stream()
                .map(button -> button.getMessage().getString())
                .toList();
    }

    private void hold(ClientGameTestContext context) {
        for (int i = 0; i < HOLD_FRAMES; i++) {
            context.waitTicks(TICKS_PER_FRAME);
            frame(context);
        }
    }
}
