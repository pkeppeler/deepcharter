package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.ClientChecks.require;

import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.ForceSends;
import io.github.pkeppeler.deepcharter.test.support.HeapHistogram;
import io.github.pkeppeler.deepcharter.test.support.SeamPodScene;
import io.github.pkeppeler.deepcharter.test.support.SeamSite.Site;
import io.github.pkeppeler.deepcharter.test.support.TestLayers;
import io.github.pkeppeler.deepcharter.test.support.TwoPlayerServer;
import io.github.pkeppeler.deepcharter.test.support.TwoSeatPods;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Trips down and up one bore (#1295): the real client and a mock ride a pod down through layer 1's floor (a swap) and up
 * through layer 2's ceiling (the teleport, since only a descent is streamed ahead), {@value #TRIPS} times at one site, and
 * what the client and the server hold is counted after each trip. A count that grows with the trips is a leak.
 */
public class BoreTripsClientTest implements FabricClientGameTest {
    private static final Logger LOGGER = LoggerFactory.getLogger("BoreTripsClientTest");
    private static final int TRIPS = 10;
    private static final double X = 15000.5;
    private static final double Z = 3000.5;
    /** Chunks the last trip may hold over the two thirds mark: tickets lapse on a timer, so the count wanders a little. */
    private static final double CHUNK_SLACK = 1.1;

    private static final String CLIENT_LEVEL = "net.minecraft.client.multiplayer.ClientLevel";
    private static final String HOLD = "io.github.pkeppeler.deepcharter.layer.LightSync$Hold";
    private static final String NET_WATCH = "io.github.pkeppeler.deepcharter.layer.LightSync$NetWatch";
    private static final String OFFER = "io.github.pkeppeler.deepcharter.layer.LightSync$RingOffer";
    private static final Set<String> TRACKED = Set.of(CLIENT_LEVEL, HOLD, NET_WATCH, OFFER);

    /** What is held after one trip. */
    private record Tripped(
            int trip,
            long clientLevels,
            long lightHolds,
            long lightNetWatches,
            long lightOffers,
            int clientEntities,
            String particles,
            String sounds,
            double msptMillis,
            int forceSends,
            int layerChunks,
            long heapMb) {}

    @Override
    public void runTest(ClientGameTestContext context) {
        ClientTestLog.start(this);
        TwoSeatPods.armClient(true);
        List<Tripped> trips = new ArrayList<>();
        try (TwoPlayerServer two = TwoPlayerServer.start(context)) {
            UUID clientId = two.server()
                    .computeOnServer(server -> server.getPlayerList().getPlayers().stream()
                            .filter(player -> player != two.mock().player())
                            .findFirst()
                            .orElseThrow()
                            .getUUID());
            Site site = two.server()
                    .computeOnServer(server ->
                            new Site(TestLayers.layerDimension(server, 1), TestLayers.layerDimension(server, 2), X, Z));
            for (int trip = 1; trip <= TRIPS; trip++) {
                trip(context, two, clientId, site);
                Tripped held = sample(context, two, trip, site);
                trips.add(held);
                LOGGER.info("BORE-TRIP {}", held);
            }
        } finally {
            TwoSeatPods.armClient(false);
        }
        expectSteady(trips);
    }

    /**
     * Fails on a count that grows with the trips. The counts are read after a full collection, so they are what is held and
     * not what is waiting to be freed. What is compared is the last trip with the first (light watches come and go within a
     * few seconds of a trip, so those are compared with the most of the first three).
     */
    private static void expectSteady(List<Tripped> trips) {
        Tripped first = trips.getFirst();
        Tripped last = trips.getLast();
        require(trips.size() == TRIPS, "only " + trips.size() + " of " + TRIPS + " trips ran");
        require(
                last.clientLevels() <= first.clientLevels(),
                "the client holds " + last.clientLevels() + " levels after " + TRIPS + " trips, " + first.clientLevels()
                        + " after the first: a level is kept for each crossing");
        require(
                last.clientEntities() <= first.clientEntities(),
                "the client holds " + last.clientEntities() + " entities after " + TRIPS + " trips, "
                        + first.clientEntities() + " after the first");
        long watches = trips.stream()
                .limit(3)
                .mapToLong(Tripped::lightNetWatches)
                .max()
                .orElse(0);
        require(
                last.lightHolds() <= first.lightHolds() && last.lightNetWatches() <= watches && last.lightOffers() <= 1,
                "LightSync holds more after " + TRIPS + " trips than in the first three: " + last + " against "
                        + first);
        require(
                last.forceSends() == 0,
                "vanilla force-sent the blocks below a player " + last.forceSends() + " times in " + TRIPS + " trips");
        require(
                last.layerChunks() <= trips.get(TRIPS * 2 / 3).layerChunks() * CHUNK_SLACK,
                "the server holds more chunks after " + TRIPS + " trips than after the first two thirds: "
                        + last.layerChunks() + " against "
                        + trips.get(TRIPS * 2 / 3).layerChunks());
    }

    private static void trip(ClientGameTestContext context, TwoPlayerServer two, UUID clientId, Site site) {
        PodEntity pod = SeamPodScene.seat(context, two, clientId, site, true);
        SeamPodScene.holdArea(context, two, clientId, site, pod);
        SeamPodScene.drop(two, site, pod);
        ClientWait.until(
                context,
                "the client in layer 2 on the pod",
                client -> SeamPodScene.inLayer(client, 2)
                        && SeamPodScene.vehicleOf(client) != null
                        && client.getConnection() != null
                        && client.getConnection().hasClientLoaded(),
                ClientWait::describe);
        context.waitTicks(20);
        // The climb: the mock steps off (a Mole has one seat, and the crossing recreates the pod with that one), then
        // the pod
        // rises through layer 2's ceiling.
        two.server().runOnServer(server -> {
            two.mock().player().stopRiding();
            ServerPlayer real = SeamPodScene.player(server, clientId);
            real.getRootVehicle()
                    .setPos(site.x(), TestLayers.dimensionBand(site.two()).maxY() + 1.5, site.z());
        });
        ClientWait.until(
                context,
                "the client back in layer 1 on the pod",
                () -> context.computeOnClient(client -> SeamPodScene.inLayer(client, 1)
                        && SeamPodScene.vehicleOf(client) != null
                        && client.getConnection() != null
                        && client.getConnection().hasClientLoaded()),
                () -> context.computeOnClient(ClientWait::describe)
                        + "; server: "
                        + two.server().computeOnServer(server -> {
                            ServerPlayer real = SeamPodScene.player(server, clientId);
                            return "real in " + real.level().dimension().identifier() + " at " + real.position()
                                    + " vehicle " + real.getVehicle() + " mock vehicle "
                                    + two.mock().player().getVehicle();
                        }));
        context.waitTicks(20);
        SeamPodScene.finish(two, pod);
    }

    private static Tripped sample(ClientGameTestContext context, TwoPlayerServer two, int trip, Site site) {
        int chunks = two.server()
                .computeOnServer(server -> site.one().getChunkSource().getLoadedChunksCount()
                        + site.two().getChunkSource().getLoadedChunksCount());
        Map<String, Long> live = HeapHistogram.liveCounts(TRACKED);
        long heapMb = (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / (1024 * 1024);
        double mspt = two.server().computeOnServer(MinecraftServer::getAverageTickTimeNanos) / 1_000_000.0;
        return context.computeOnClient(client -> new Tripped(
                trip,
                live.getOrDefault(CLIENT_LEVEL, 0L),
                live.getOrDefault(HOLD, 0L),
                live.getOrDefault(NET_WATCH, 0L),
                live.getOrDefault(OFFER, 0L),
                client.level == null ? -1 : client.level.getEntityCount(),
                client.particleEngine.countParticles(),
                client.getSoundManager().getChannelDebugString(),
                mspt,
                ForceSends.total(),
                chunks,
                heapMb));
    }
}
