package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.ClientChecks.require;

import io.github.pkeppeler.deepcharter.client.layer.BreachEffects;
import io.github.pkeppeler.deepcharter.client.layer.SeamClient;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.SeamPodScene;
import io.github.pkeppeler.deepcharter.test.support.SeamSite.Site;
import io.github.pkeppeler.deepcharter.test.support.SwapProbes;
import io.github.pkeppeler.deepcharter.test.support.TestLayers;
import io.github.pkeppeler.deepcharter.test.support.TwoPlayerServer;
import io.github.pkeppeler.deepcharter.test.support.TwoSeatPods;
import java.util.Objects;
import java.util.UUID;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Client GameTest of the seamless swap (#1229, ADR 0089), on the shipped layer_1 to layer_2 crossing, with the real
 * client and a mock on one pod in a dedicated server. Three crossings. The swap: the mock accepts, the pod crosses, and
 * the client keeps its player, its vehicle object and id, the mock's player and its loaded state, and opens no loading
 * screen, stops no sound and handles no respawn. The fallback: the mock refuses, so the whole pod is teleported and the
 * client takes the respawn (each of those probes counts at least one, which shows the probes work) and adopts the level
 * held ahead. The recovery: the client's swap is made to fail, and the server respawns it alone, with the pod still
 * under it and the mock still riding. Each logs a {@code SEAM-TIMING} line.
 *
 * <p>The mock sits in the first seat, so the pod is moved by the server and the real client is a passenger.
 */
public class SeamSwapClientTest implements FabricClientGameTest {
    private static final Logger LOGGER = LoggerFactory.getLogger("SeamSwapClientTest");
    private static final double SWAP_X = 7000.5;
    private static final double FALLBACK_X = 9000.5;
    private static final double RECOVERY_X = 11000.5;
    private static final double Z = 3000.5;
    /** Client ticks after a swap that nothing may open a screen, stop a sound or handle a respawn. */
    private static final int QUIET_TICKS = 40;

    @Override
    public void runTest(ClientGameTestContext context) {
        ClientTestLog.start(this);
        SwapProbes.install();
        TwoSeatPods.armClient(true);
        try (TwoPlayerServer two = TwoPlayerServer.start(context)) {
            UUID clientId = two.server()
                    .computeOnServer(server -> realPlayer(server, two).getUUID());
            swap(context, two, clientId);
            fallback(context, two, clientId);
            recovery(context, two, clientId);
        } finally {
            TwoSeatPods.armClient(false);
            SwapProbes.disarm();
            SeamClient.failSwapsForTest(false);
        }
    }

    private static void swap(ClientGameTestContext context, TwoPlayerServer two, UUID clientId) {
        Site site = two.server().computeOnServer(server -> siteAt(server, SWAP_X));
        PodEntity pod = SeamPodScene.seat(context, two, clientId, site, true);
        SeamPodScene.Before before = SeamPodScene.holdArea(context, two, clientId, site, pod);
        SwapProbes.arm();
        long startNanos = System.nanoTime();
        SeamPodScene.drop(two, site, pod);
        ClientWait.until(
                context,
                "the client to be in the level it held for layer 2",
                client -> client.level == before.held(),
                ClientWait::describe);
        long swappedMillis = (System.nanoTime() - startNanos) / 1_000_000;
        context.waitTicks(QUIET_TICKS);
        SwapProbes.Counts counts = SwapProbes.disarm();
        require(
                context.computeOnClient(client -> client.player == before.player()),
                "the client's player is not the one it had: a new LocalPlayer means a respawn");
        require(
                context.computeOnClient(client -> SeamPodScene.vehicleOf(client) == before.vehicle()
                        && before.vehicle().getId() == before.vehicleId()),
                "the client's vehicle is not the object it had, with the id it had");
        require(
                context.computeOnClient(
                        client -> SeamPodScene.levelOf(client).getEntity(before.vehicleId()) == before.vehicle()
                                && SeamPodScene.levelOf(client)
                                                .getEntity(before.player().getId())
                                        == before.player()),
                "the new level does not hold the same vehicle and player");
        require(
                context.computeOnClient(client -> SeamPodScene.levelOf(client)
                                        .getPlayerByUUID(before.mockPlayer().getUUID())
                                == before.mockPlayer()
                        && before.mockPlayer().getVehicle() == before.vehicle()),
                "the mock's player is not the same object, still on the vehicle");
        require(context.computeOnClient(client -> SeamClient.background().isEmpty()), "the swap left a held level");
        require(counts.respawns() == 0, "the swap made the client handle " + counts.respawns() + " respawns");
        require(
                counts.loadingScreens() == 0,
                "the swap opened the loading screen " + counts.loadingScreens() + " times");
        require(counts.soundStops() == 0, "the swap stopped every sound " + counts.soundStops() + " times");
        require(!counts.everUnloaded(), "the client was not loaded at some point of the swap");
        waitMeshed(context, "swapped");
        logTiming(context, "swapped", swappedMillis, counts);
        SeamPodScene.finish(two, pod);
    }

    private static void fallback(ClientGameTestContext context, TwoPlayerServer two, UUID clientId) {
        Site site = two.server().computeOnServer(server -> siteAt(server, FALLBACK_X));
        PodEntity pod = SeamPodScene.seat(context, two, clientId, site, false);
        SeamPodScene.Before before = SeamPodScene.holdArea(context, two, clientId, site, pod);
        SwapProbes.arm();
        long startNanos = System.nanoTime();
        SeamPodScene.drop(two, site, pod);
        ClientWait.until(
                context,
                "the client to be in the level held for layer 2, by adoption",
                client -> client.level == before.held()
                        && client.player != null
                        && client.player != before.player()
                        && client.getConnection() != null
                        && client.getConnection().hasClientLoaded(),
                ClientWait::describe);
        long fellBackMillis = (System.nanoTime() - startNanos) / 1_000_000;
        waitMeshed(context, "fallback");
        SwapProbes.Counts counts = SwapProbes.disarm();
        require(counts.respawns() >= 1, "the fallback made the client handle no respawn: the probe is blind");
        require(counts.loadingScreens() >= 1, "the fallback opened no loading screen: the probe is blind");
        require(counts.soundStops() >= 1, "the fallback stopped no sound: the probe is blind");
        require(counts.everUnloaded(), "the client was loaded throughout the fallback: the probe is blind");
        ClientWait.until(
                context,
                "the client back on the pod, with the mock",
                client -> SeamPodScene.ridesWith(client, before.vehicleUuid()),
                ClientWait::describe);
        logTiming(context, "fallback", fellBackMillis, counts);
        SeamPodScene.finish(two, pod);
    }

    private static void recovery(ClientGameTestContext context, TwoPlayerServer two, UUID clientId) {
        Site site = two.server().computeOnServer(server -> siteAt(server, RECOVERY_X));
        PodEntity pod = SeamPodScene.seat(context, two, clientId, site, true);
        SeamPodScene.Before before = SeamPodScene.holdArea(context, two, clientId, site, pod);
        context.runOnClient(client -> SeamClient.failSwapsForTest(true));
        SwapProbes.arm();
        long startNanos = System.nanoTime();
        SeamPodScene.drop(two, site, pod);
        ClientWait.until(
                context,
                "the server to respawn the client that could not swap, and the client to be mounted again",
                client -> client.player != null
                        && client.player != before.player()
                        && SeamPodScene.inLayer(client, 2)
                        && client.getConnection() != null
                        && client.getConnection().hasClientLoaded()
                        && SeamPodScene.ridesWith(client, before.vehicleUuid()),
                ClientWait::describe);
        long recoveredMillis = (System.nanoTime() - startNanos) / 1_000_000;
        SwapProbes.Counts counts = SwapProbes.disarm();
        context.runOnClient(client -> SeamClient.failSwapsForTest(false));
        require(counts.respawns() == 1, "the failed swap was recovered by " + counts.respawns() + " respawns, not 1");
        require(counts.loadingScreens() >= 1, "the recovery showed the client no loading screen");
        two.server().runOnServer(server -> {
            Entity vehicle = Objects.requireNonNull(
                    site.two().getEntity(before.vehicleUuid()), "the pod is not in layer 2 on the server");
            require(
                    vehicle == pod && vehicle.isAlive(),
                    "the server's pod was not left whole: it is another or removed");
            require(vehicle.getId() == before.vehicleId(), "the server's pod changed its id");
            require(
                    vehicle.getPassengers().contains(SeamPodScene.player(server, clientId))
                            && vehicle.getPassengers().contains(two.mock().player()),
                    "the server's pod does not carry both riders: " + vehicle.getPassengers());
        });
        logTiming(context, "recovery", recoveredMillis, counts);
        SeamPodScene.finish(two, pod);
    }

    private static void waitMeshed(ClientGameTestContext context, String what) {
        ClientWait.until(
                context,
                "the section of the camera to be meshed after the " + what + " crossing",
                client -> client.getCameraEntity() != null
                        && client.levelRenderer.isSectionCompiledAndVisible(
                                client.getCameraEntity().blockPosition(), 0L),
                ClientWait::describe);
    }

    private static void logTiming(ClientGameTestContext context, String kind, long millis, SwapProbes.Counts counts) {
        long heapMb = (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / (1024 * 1024);
        LOGGER.info(
                "SEAM-TIMING {} drop_to_level_ms={} respawns={} loading_screens={} sound_stops={} ever_unloaded={} fade_hold_ticks={} heap_mb={} seam_bytes={}",
                kind,
                millis,
                counts.respawns(),
                counts.loadingScreens(),
                counts.soundStops(),
                counts.everUnloaded(),
                context.computeOnClient(client -> BreachEffects.heldTicks()),
                heapMb,
                context.computeOnClient(client -> SeamClient.bytesReceived()));
    }

    private static Site siteAt(MinecraftServer server, double x) {
        return new Site(TestLayers.layerDimension(server, 1), TestLayers.layerDimension(server, 2), x, Z);
    }

    private static ServerPlayer realPlayer(MinecraftServer server, TwoPlayerServer two) {
        return server.getPlayerList().getPlayers().stream()
                .filter(player -> player != two.mock().player())
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("The real client's player is not on the server"));
    }
}
