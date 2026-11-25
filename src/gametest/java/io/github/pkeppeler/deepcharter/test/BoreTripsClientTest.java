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
 * Trips down and up one bore (#1295): the real client and a mock ride a pod down through layer 1's floor (a swap) and
 * up through layer 2's ceiling (the teleport, since only a descent is streamed ahead), {@value #TRIPS} times at one
 * site, and what the client and the server hold is counted after each trip. A count that grows with the trips is a
 * leak.
 *
 * <p>This is the chain of separate layer dimensions, not the campaign world, whose layers are bands of one level and
 * whose crossings move nobody. The comparison is last trip against first, so it misses an object kept once and never
 * again.
 */
public class BoreTripsClientTest implements FabricClientGameTest {
    private static final Logger LOGGER = LoggerFactory.getLogger("BoreTripsClientTest");
    private static final int TRIPS = 10;
    private static final double X = 15000.5;
    private static final double Z = 3000.5;
    /** Chunks the last trip may hold over the two-thirds mark: tickets lapse on a timer, so the count wanders. */
    private static final double CHUNK_SLACK = 1.1;

    private static final String CLIENT_LEVEL = "net.minecraft.client.multiplayer.ClientLevel";
    private static final String NET_WATCH = "io.github.pkeppeler.deepcharter.layer.LightSync$NetWatch";
    private static final Set<String> TRACKED = Set.of(CLIENT_LEVEL, NET_WATCH);

    /** What is held after one trip. */
    private record Tripped(
            int trip,
            long clientLevels,
            long lightNetWatches,
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
        TRACKED.forEach(BoreTripsClientTest::requireClass);
        int forceSendsBefore = ForceSends.total();
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
                Tripped held = sample(context, two, trip, site, forceSendsBefore);
                trips.add(held);
                LOGGER.info("BORE-TRIP {}", held);
            }
        } finally {
            TwoSeatPods.armClient(false);
        }
        expectSteady(trips);
    }

    /**
     * Fails on a count that grows with the trips. The counts are read after a full collection, so they are what is held
     * and not what is waiting to be freed. The last trip is compared with the first, which misses an object kept once
     * and never again; light watches come and go within seconds of a trip, so they are compared with the most of the
     * first three.
     */
    private static void expectSteady(List<Tripped> trips) {
        Tripped first = trips.getFirst();
        Tripped last = trips.getLast();
        require(trips.size() == TRIPS, "only " + trips.size() + " of " + TRIPS + " trips ran");
        require(first.clientLevels() > 0, "no ClientLevel was counted: the count is blind");
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
        require(watches > 0, "no LightSync watch was counted in the first three trips: the count is blind");
        require(
                last.lightNetWatches() <= watches,
                "LightSync holds " + last.lightNetWatches() + " watches after " + TRIPS + " trips, at most " + watches
                        + " in the first three");
        // A guard and not evidence: vanilla reaches this only for a player who is not a passenger, and the test's rider
        // stays seated, so it passes however the pod is dropped. It would catch a rider who ended up on foot.
        require(
                last.forceSends() == 0,
                "vanilla force-sent the blocks below a player " + last.forceSends() + " times in " + TRIPS + " trips");
        Tripped third = trips.get(TRIPS * 2 / 3);
        require(
                last.layerChunks() <= third.layerChunks() * CHUNK_SLACK,
                "the server holds more chunks after " + TRIPS + " trips than after the first two thirds: "
                        + last.layerChunks() + " against " + third.layerChunks());
    }

    private static void requireClass(String name) {
        try {
            Class.forName(name, false, BoreTripsClientTest.class.getClassLoader());
        } catch (ClassNotFoundException missing) {
            throw new AssertionError(
                    "The tracked class " + name + " does not exist: a rename left the count at 0", missing);
        }
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
        // the pod rises through layer 2's ceiling.
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

    private static Tripped sample(
            ClientGameTestContext context, TwoPlayerServer two, int trip, Site site, int forceSendsBefore) {
        int chunks = two.server()
                .computeOnServer(server -> site.one().getChunkSource().getLoadedChunksCount()
                        + site.two().getChunkSource().getLoadedChunksCount());
        Map<String, Long> live = HeapHistogram.liveCounts(TRACKED);
        long heapMb = (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / (1024 * 1024);
        double mspt = two.server().computeOnServer(MinecraftServer::getAverageTickTimeNanos) / 1_000_000.0;
        return context.computeOnClient(client -> new Tripped(
                trip,
                live.getOrDefault(CLIENT_LEVEL, 0L),
                live.getOrDefault(NET_WATCH, 0L),
                client.level == null ? -1 : client.level.getEntityCount(),
                client.particleEngine.countParticles(),
                client.getSoundManager().getChannelDebugString(),
                mspt,
                ForceSends.total() - forceSendsBefore,
                chunks,
                heapMb));
    }
}
