package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.ClientChecks.require;

import io.github.pkeppeler.deepcharter.layer.LightSync;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.FarChunks;
import io.github.pkeppeler.deepcharter.test.support.ForceSends;
import io.github.pkeppeler.deepcharter.test.support.HeapHistogram;
import io.github.pkeppeler.deepcharter.test.support.SeamPodScene;
import io.github.pkeppeler.deepcharter.test.support.SeamSite.Site;
import io.github.pkeppeler.deepcharter.test.support.ServerTickets;
import io.github.pkeppeler.deepcharter.test.support.TestLayers;
import io.github.pkeppeler.deepcharter.test.support.TwoPlayerServer;
import io.github.pkeppeler.deepcharter.test.support.TwoSeatPods;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.core.SectionPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkLevel;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Trips down and up one bore (#1295): the real client and a mock ride a pod down through layer 1's floor (a swap) and
 * up through layer 2's ceiling (the teleport, since only a descent is streamed ahead), {@value #TRIPS} times at one
 * site, and what the client and the server hold is counted after each trip. A count that grows with the trips is a
 * leak.
 *
 * <p>This is the chain of separate layer dimensions, not the campaign world, whose layers are bands of one level and
 * whose crossings move nobody. The comparison is last trip against first (light watches: against the second), so it
 * misses an object kept once and never again. The server's chunks are counted through their tickets, so it also misses
 * a chunk whose ticket is gone but whose unload stalls; left to settle, the layers held 4418 chunks after trips 1, 2
 * and 10 (#1301), so none did then.
 */
public class BoreTripsClientTest implements FabricClientGameTest {
    private static final Logger LOGGER = LoggerFactory.getLogger("BoreTripsClientTest");
    /**
     * Trips made. Ten took about 125 s, more than a CI shard may hold (#1574); {@link #expectSteady} compares the last trip with the first
     * and the second, so any count from three up shows a count that grows, and a leak of one object a trip shows after six as it did
     * after ten.
     */
    private static final int TRIPS = 6;
    /**
     * The longest the last trip's settle waits. A settle takes about 4 s (the first, while the area generates, up to
     * 20 s, and keeps the default wait); the last starts some 120 s into the class's 180 s ({@code ClientTestWatchdog}),
     * so the default wait would end in the watchdog's dump instead of naming the chunk.
     */
    private static final int LAST_SETTLE_SECONDS = 30;

    private static final double X = 15000.5;
    private static final double Z = 3000.5;
    /** Where the test holds a ticket of its own in each layer, far from the site, so the census cannot read empty. */
    private static final ChunkPos CONTROL =
            new ChunkPos(SectionPos.blockToSectionCoord(X) + 64, SectionPos.blockToSectionCoord(Z));
    /**
     * The control ticket's radius: one level past {@code ChunkLevel.MAX_LEVEL}, so it loads nothing. At radius 0 it
     * loaded a chunk and its generation area, about 1000 tall chunks over the two layers. {@code DRAGON} has no
     * timeout and does not keep a dimension active, which {@code FORCED} does.
     */
    private static final int CONTROL_RADIUS = -(ChunkLevel.RADIUS_AROUND_FULL_CHUNK + 1);

    private static final String CONTROL_TYPE = "minecraft:dragon";
    /**
     * Ticket types left out of the count. A player's tickets follow the player, whose number the test holds constant,
     * and are added a few at a time after each crossing (the whole view area takes about 300 ticks), so their count
     * depends on when the sample falls. {@code UNKNOWN} is the one-tick ticket of any chunk read that misses the cache.
     */
    private static final Set<TicketType> UNCOUNTED =
            Set.of(TicketType.PLAYER_LOADING, TicketType.PLAYER_SIMULATION, TicketType.UNKNOWN);

    private static final String CLIENT_LEVEL = "net.minecraft.client.multiplayer.ClientLevel";
    private static final String NET_WATCH = "io.github.pkeppeler.deepcharter.layer.LightSync$NetWatch";
    private static final Set<String> TRACKED = Set.of(CLIENT_LEVEL, NET_WATCH);

    /** What is held after one trip. */
    private record Tripped(
            int trip,
            long clientLevels,
            long lightNetWatches,
            int ringWatches,
            int clientEntities,
            String particles,
            String sounds,
            double msptMillis,
            int forceSends,
            int layerChunks,
            Map<String, Integer> layerTickets,
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
            two.server()
                    .runOnServer(server -> List.of(site.one(), site.two())
                            .forEach(level -> level.getChunkSource()
                                    .addTicketWithRadius(TicketType.DRAGON, CONTROL, CONTROL_RADIUS)));
            try {
                for (int trip = 1; trip <= TRIPS; trip++) {
                    trip(context, two, clientId, site);
                    // Only the two trips the light-watch check compares are settled, for time; a watch that never
                    // ends is still there, and named, at the last trip's.
                    Tripped held = trip == 2
                            ? settledSample(context, two, trip, site, forceSendsBefore, FarChunks.WAIT_SECONDS)
                            : trip == TRIPS
                                    ? settledSample(context, two, trip, site, forceSendsBefore, LAST_SETTLE_SECONDS)
                                    : sample(context, two, trip, site, forceSendsBefore);
                    trips.add(held);
                    LOGGER.info("BORE-TRIP {}", held);
                }
            } finally {
                two.server()
                        .runOnServer(server -> List.of(site.one(), site.two())
                                .forEach(level -> level.getChunkSource()
                                        .removeTicketWithRadius(TicketType.DRAGON, CONTROL, CONTROL_RADIUS)));
            }
        } finally {
            TwoSeatPods.armClient(false);
        }
        expectSteady(trips);
    }

    /**
     * Fails on a count that grows with the trips. The counts are read after a full collection, so they are what is held
     * and not what is waiting to be freed. The last trip is compared with the first, which misses an object kept once
     * and never again. Light watches are counted once LightSync is done with the site ({@link #settledSample}), less
     * those on the outermost loaded ring ({@link #unsettled} says why the settle leaves it out), and the last trip is
     * compared with the second. The first trip is sampled unsettled, so its count, of the crossing's watches in flight,
     * shows the census sees them at all. The count is the whole server's: it also holds watches off the site, such as
     * those on the rim of the overworld's spawn area, which no trip adds to, so they can only fall.
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
        require(
                first.lightNetWatches() > 0,
                "no LightSync watch was counted in flight after the first trip: the count is blind");
        Tripped second = trips.get(1);
        require(
                last.lightNetWatches() - last.ringWatches() <= second.lightNetWatches() - second.ringWatches(),
                "LightSync holds " + last.lightNetWatches() + " watches (" + last.ringWatches()
                        + " on the outermost ring) once settled after " + TRIPS + " trips, "
                        + second.lightNetWatches() + " (" + second.ringWatches() + ") after the second");
        // A guard and not evidence: vanilla reaches this only for a player who is not a passenger, and the test's rider
        // stays seated, so it passes however the pod is dropped. It would catch a rider who ended up on foot.
        require(
                last.forceSends() == 0,
                "vanilla force-sent the blocks below a player " + last.forceSends() + " times in " + TRIPS + " trips");
        // The tickets and not the loaded chunks: each crossing moves both players' view areas, which load over
        // about 300 ticks, longer than a trip, so the loaded count is wherever that load had got to (#1301). A chunk
        // kept across trips by a ticket shows here as a ticket that stays. It misses a chunk whose ticket is gone but
        // whose unload stalls (a holder future that never completes); the settled count showed none.
        for (Tripped trip : trips) {
            for (String layer : List.of("layer1 ", "layer2 ")) {
                int control = trip.layerTickets().getOrDefault(layer + CONTROL_TYPE, 0);
                require(
                        control == 1,
                        "trip " + trip.trip() + " counted " + control + " of the test's own " + layer + CONTROL_TYPE
                                + " ticket, not 1: the census is blind");
            }
        }
        Map<String, Integer> early = new TreeMap<>();
        trips.stream()
                .limit(TRIPS * 2 / 3)
                .forEach(trip -> trip.layerTickets().forEach((type, count) -> early.merge(type, count, Math::max)));
        last.layerTickets()
                .forEach((type, count) -> require(
                        count <= early.getOrDefault(type, 0),
                        "the layers hold " + count + " " + type + " tickets after " + TRIPS + " trips, at most "
                                + early.getOrDefault(type, 0) + " in the first two thirds"));
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

    /**
     * Waits until LightSync is done with the site ({@link #unsettled} is empty), samples, and samples again if
     * something started on the site between the wait and the census: a hold or watch begun there would be counted as
     * one that stayed. A light watch lives for a window after its chunk can be sent, so a census straight after a
     * crossing counted the crossing's watches still in flight, a number that rose and fell with timing and failed the
     * steady check on a high last sample (#1405).
     */
    private static Tripped settledSample(
            ClientGameTestContext context,
            TwoPlayerServer two,
            int trip,
            Site site,
            int forceSendsBefore,
            int seconds) {
        for (int attempt = 1; ; attempt++) {
            long start = System.nanoTime();
            ClientWait.until(
                    context,
                    "LightSync to be done with the chunks round the site in both layers",
                    seconds,
                    () -> two.server()
                            .computeOnServer(server -> unsettled(server, site).isEmpty()),
                    () -> "unsettled: " + two.server().computeOnServer(server -> unsettled(server, site)));
            LOGGER.info("BORE-SETTLE {} ms", (System.nanoTime() - start) / 1_000_000);
            Tripped held = sample(context, two, trip, site, forceSendsBefore);
            List<String> since = two.server().computeOnServer(server -> unsettled(server, site));
            if (since.isEmpty()) {
                return held;
            }
            require(attempt < 3, "trip " + trip + ": the site kept unsettling during the census: " + since);
        }
    }

    /**
     * The chunks of both layers round the site, out to the last ring that ticks, that LightSync holds, watches or waits
     * on queued light tasks for, or whose send to a player is queued, each tagged with why. Not the outermost loaded
     * ring past it: a chunk there never ticks, so a watch on it lasts until the chunk unloads, and a settle that took
     * in the ring waited out its whole wait on a handful of them (#1394 drops such watches; once it lands, the ring can join the
     * settle). The last ticking ring is one past the simulation distance, which TwoPlayerServer leaves at the view
     * distance's default.
     */
    private static List<String> unsettled(MinecraftServer server, Site site) {
        List<String> unsettled = new ArrayList<>();
        for (LayerChunk chunk : around(site, tickingRadius(server))) {
            boolean queued = chunk.level().players().stream()
                    .anyMatch(player ->
                            player.connection.chunkSender.isPending(chunk.pos().pack()));
            if (queued || LightSync.watching(chunk.level(), chunk.pos())) {
                unsettled.add(chunk.level().dimension().identifier().getPath() + " " + chunk.pos()
                        + (queued ? " send queued" : " watched"));
            }
        }
        return unsettled;
    }

    /**
     * The chunks of both layers on the outermost loaded ring, the one past {@link #tickingRadius}, that LightSync is
     * settling: mostly watches, which is what the census subtracts them from.
     */
    private static int ringWatches(MinecraftServer server, Site site) {
        int ring = tickingRadius(server) + 1;
        return (int) around(site, ring).stream()
                .filter(chunk -> chunk.pos().getChessboardDistance(site.centre()) == ring
                        && LightSync.watching(chunk.level(), chunk.pos()))
                .count();
    }

    private static int tickingRadius(MinecraftServer server) {
        return server.getPlayerList().getSimulationDistance() + 1;
    }

    private record LayerChunk(ServerLevel level, ChunkPos pos) {}

    /** The chunks of both layers within {@code radius} chunks of the site's centre. */
    private static List<LayerChunk> around(Site site, int radius) {
        List<LayerChunk> chunks = new ArrayList<>();
        for (ServerLevel level : List.of(site.one(), site.two())) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    chunks.add(new LayerChunk(
                            level,
                            new ChunkPos(site.centre().x() + dx, site.centre().z() + dz)));
                }
            }
        }
        return chunks;
    }

    private static Tripped sample(
            ClientGameTestContext context, TwoPlayerServer two, int trip, Site site, int forceSendsBefore) {
        int chunks = two.server()
                .computeOnServer(server -> site.one().getChunkSource().getLoadedChunksCount()
                        + site.two().getChunkSource().getLoadedChunksCount());
        Map<String, Integer> tickets = two.server().computeOnServer(server -> heldTickets(site));
        Map<String, Long> live = HeapHistogram.liveCounts(TRACKED);
        int ring = two.server().computeOnServer(server -> ringWatches(server, site));
        long heapMb = (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / (1024 * 1024);
        double mspt = two.server().computeOnServer(MinecraftServer::getAverageTickTimeNanos) / 1_000_000.0;
        return context.computeOnClient(client -> new Tripped(
                trip,
                live.getOrDefault(CLIENT_LEVEL, 0L),
                live.getOrDefault(NET_WATCH, 0L),
                ring,
                client.level == null ? -1 : client.level.getEntityCount(),
                client.particleEngine.countParticles(),
                client.getSoundManager().getChannelDebugString(),
                mspt,
                ForceSends.total() - forceSendsBefore,
                chunks,
                tickets,
                heapMb));
    }

    /** The tickets both layers hold, by level and type, without the {@link #UNCOUNTED} types. */
    private static Map<String, Integer> heldTickets(Site site) {
        Map<String, Integer> held = new TreeMap<>();
        ServerTickets.byType(site.one(), type -> !UNCOUNTED.contains(type))
                .forEach((type, count) -> held.put("layer1 " + type, count));
        ServerTickets.byType(site.two(), type -> !UNCOUNTED.contains(type))
                .forEach((type, count) -> held.put("layer2 " + type, count));
        return held;
    }
}
