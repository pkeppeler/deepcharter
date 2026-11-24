package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.ClientChecks.require;
import static io.github.pkeppeler.deepcharter.test.support.SeamSite.HOLD_HEIGHT;

import io.github.pkeppeler.deepcharter.client.layer.BreachEffects;
import io.github.pkeppeler.deepcharter.client.layer.SeamClient;
import io.github.pkeppeler.deepcharter.layer.BreachPayload;
import io.github.pkeppeler.deepcharter.layer.SeamAcceptPayload;
import io.github.pkeppeler.deepcharter.layer.SeamChunkPayload;
import io.github.pkeppeler.deepcharter.layer.SeamDiscardPayload;
import io.github.pkeppeler.deepcharter.layer.SeamPreparePayload;
import io.github.pkeppeler.deepcharter.layer.SeamRefusePayload;
import io.github.pkeppeler.deepcharter.layer.SeamRefuseReason;
import io.github.pkeppeler.deepcharter.layer.SeamSender;
import io.github.pkeppeler.deepcharter.layer.SeamSwapPayload;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodEvents;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.MockPlayer;
import io.github.pkeppeler.deepcharter.test.support.SeamSite.Site;
import io.github.pkeppeler.deepcharter.test.support.SwapProbes;
import io.github.pkeppeler.deepcharter.test.support.TestEntities;
import io.github.pkeppeler.deepcharter.test.support.TestLayers;
import io.github.pkeppeler.deepcharter.test.support.TwoPlayerServer;
import io.github.pkeppeler.deepcharter.test.support.TwoSeatPods;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Client GameTest of the seamless swap (#1229, ADR 0089), on the shipped layer_1 to layer_2 crossing, with the real client and a mock
 * on one pod in a dedicated server. Three crossings. The swap: the mock accepts, the pod crosses, and the client keeps its player, its
 * vehicle object and id, the mock's player and its loaded state, and opens no loading screen, stops no sound and handles no respawn.
 * The fallback: the mock refuses, so the whole pod is teleported and the client takes the respawn (each of those probes counts at least
 * one, which shows the probes work) and adopts the level held ahead. The recovery: the client's swap is made to fail, and the server
 * respawns it alone, with the pod still under it and the mock still riding. Each logs a {@code SEAM-TIMING} line.
 *
 * <p>The mock sits in the first seat, so the pod is moved by the server and the real client is a passenger.
 */
public class SeamSwapClientTest implements FabricClientGameTest {
    private static final Logger LOGGER = LoggerFactory.getLogger("SeamSwapClientTest");
    private static final double SWAP_X = 7000.5;
    private static final double FALLBACK_X = 9000.5;
    private static final double RECOVERY_X = 11000.5;
    private static final double Z = 3000.5;
    private static final int AREA_SIDE = 2 * SeamSender.SEND_RADIUS + 1;
    /** Client ticks the held level's light gets to drain after its last chunk arrives. */
    private static final int LIGHT_DRAIN_TICKS = 40;
    /** Client ticks after a swap that nothing may open a screen, stop a sound or handle a respawn. */
    private static final int QUIET_TICKS = 40;

    /** Where each pod is pinned until its crossing, by UUID: pod movement ignores no-gravity (#554), so the hold is an after-tick pin. */
    private static final Map<UUID, Vec3> PINS = new ConcurrentHashMap<>();
    /** The mock that answers each prepare it is sent, and how. Read and written on the server thread. */
    private static @Nullable MockPlayer answering;

    private static boolean answersAccept;
    private static int answered;

    static {
        PodEvents.AFTER_TICK.register(pod -> {
            Vec3 pin = PINS.get(pod.getUUID());
            if (pin != null) {
                pod.setPos(pin);
                pod.setDeltaMovement(Vec3.ZERO);
            }
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> answer());
    }

    /** Answers each prepare the mock was sent once, as a client does: an accept of its generation, or a refusal. */
    private static void answer() {
        MockPlayer mock = answering;
        if (mock == null) {
            return;
        }
        List<SeamPreparePayload> prepares = mock.payloadsOf(SeamPreparePayload.class);
        while (answered < prepares.size()) {
            SeamPreparePayload prepare = prepares.get(answered++);
            mock.send(
                    answersAccept
                            ? new SeamAcceptPayload(prepare.dimension(), prepare.generation())
                            : new SeamRefusePayload(
                                    prepare.dimension(), prepare.generation(), SeamRefuseReason.RELEASED));
        }
    }

    /** What the client held before a crossing, to compare with what it holds after. */
    private record Before(
            LocalPlayer player,
            Entity vehicle,
            int vehicleId,
            UUID vehicleUuid,
            Entity mockPlayer,
            ClientLevel shown,
            ClientLevel held) {}

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
            answering = null;
        }
    }

    private static void swap(ClientGameTestContext context, TwoPlayerServer two, UUID clientId) {
        Site site = two.server().computeOnServer(server -> siteAt(server, SWAP_X));
        PodEntity pod = seat(context, two, clientId, site, true);
        Before before = holdArea(context, two, clientId, site, pod);
        SwapProbes.arm();
        long startNanos = System.nanoTime();
        drop(two, site, pod);
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
                context.computeOnClient(client -> vehicleOf(client) == before.vehicle()
                        && before.vehicle().getId() == before.vehicleId()),
                "the client's vehicle is not the object it had, with the id it had");
        require(
                context.computeOnClient(client -> levelOf(client).getEntity(before.vehicleId()) == before.vehicle()
                        && levelOf(client).getEntity(before.player().getId()) == before.player()),
                "the new level does not hold the same vehicle and player");
        require(
                context.computeOnClient(client ->
                        levelOf(client).getPlayerByUUID(before.mockPlayer().getUUID()) == before.mockPlayer()
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
        finish(two, pod);
    }

    private static void fallback(ClientGameTestContext context, TwoPlayerServer two, UUID clientId) {
        Site site = two.server().computeOnServer(server -> siteAt(server, FALLBACK_X));
        PodEntity pod = seat(context, two, clientId, site, false);
        Before before = holdArea(context, two, clientId, site, pod);
        SwapProbes.arm();
        long startNanos = System.nanoTime();
        drop(two, site, pod);
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
                client -> ridesWith(client, before.vehicleUuid(), 2),
                ClientWait::describe);
        logTiming(context, "fallback", fellBackMillis, counts);
        finish(two, pod);
    }

    private static void recovery(ClientGameTestContext context, TwoPlayerServer two, UUID clientId) {
        Site site = two.server().computeOnServer(server -> siteAt(server, RECOVERY_X));
        PodEntity pod = seat(context, two, clientId, site, true);
        Before before = holdArea(context, two, clientId, site, pod);
        context.runOnClient(client -> SeamClient.failSwapsForTest(true));
        SwapProbes.arm();
        long startNanos = System.nanoTime();
        drop(two, site, pod);
        ClientWait.until(
                context,
                "the server to respawn the client that could not swap, and the client to be mounted again",
                client -> client.player != null
                        && client.player != before.player()
                        && inLayer(client, 2)
                        && client.getConnection() != null
                        && client.getConnection().hasClientLoaded()
                        && ridesWith(client, before.vehicleUuid(), 2),
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
                    vehicle.getPassengers().contains(player(server, clientId))
                            && vehicle.getPassengers().contains(two.mock().player()),
                    "the server's pod does not carry both riders: " + vehicle.getPassengers());
        });
        logTiming(context, "recovery", recoveredMillis, counts);
        finish(two, pod);
    }

    /** A fresh pod over {@code site} with the mock in its first seat and the real client in its second, pinned at the hold height. */
    private static PodEntity seat(
            ClientGameTestContext context, TwoPlayerServer two, UUID clientId, Site site, boolean mockAccepts) {
        MockPlayer mock = two.mock();
        PodEntity[] made = new PodEntity[1];
        Vec3 hold = two.server().computeOnServer(server -> site.at(HOLD_HEIGHT));
        // The riders go first, and the pod joins once its chunk ticks entities: an entity added to a chunk that does
        // not yet is not in
        // the level's lookup, so no rider could board it and no client would be told of it.
        two.server().runOnServer(server -> {
            site.carveShaft(site.x(), HOLD_HEIGHT + 4);
            ServerPlayer real = player(server, clientId);
            real.stopRiding();
            mock.player().stopRiding();
            mock.teleportTo(site.one(), hold, 0, 0);
            real.teleportTo(site.one(), hold.x, hold.y, hold.z, Set.of(), 0, 0, true);
            // Held in the air until the pod is there: nothing else would stop a fall through the floor, which is a
            // crossing.
            mock.player().setNoGravity(true);
            real.setNoGravity(true);
            mock.announceChannel(SeamPreparePayload.TYPE);
            mock.announceChannel(SeamChunkPayload.TYPE);
            mock.announceChannel(SeamDiscardPayload.TYPE);
            mock.announceChannel(SeamSwapPayload.TYPE);
            mock.announceChannel(BreachPayload.TYPE);
            answersAccept = mockAccepts;
            answered = 0;
            answering = mock;
        });
        ClientWait.until(
                context,
                "the chunk of the hold to tick entities in layer 1 and reach the client",
                () -> two.server()
                                .computeOnServer(
                                        server -> site.one().isPositionEntityTicking(BlockPos.containing(hold)))
                        && context.computeOnClient(client -> client.level != null
                                && inLayer(client, 1)
                                && client.level
                                        .getChunkSource()
                                        .hasChunk(
                                                site.centre().x(), site.centre().z())),
                () -> "hold " + hold + ", "
                        + two.server().computeOnServer(server -> diagnose(server, clientId, site, hold))
                        + ", client: "
                        + context.computeOnClient(client -> ClientWait.describe(client) + ", chunks "
                                + (client.level == null
                                        ? -1
                                        : client.level.getChunkSource().getLoadedChunksCount())
                                + ", client loaded "
                                + (client.getConnection() != null
                                        && client.getConnection().hasClientLoaded())));
        two.server().runOnServer(server -> {
            PodEntity pod = TestEntities.create(PodRegistry.POD, site.one());
            TwoSeatPods.add(pod);
            pod.setPos(hold);
            site.one().addFreshEntity(pod);
            PINS.put(pod.getUUID(), hold);
            made[0] = pod;
            mock.player().setNoGravity(false);
            player(server, clientId).setNoGravity(false);
            require(mock.player().startRiding(pod, true, false), "the mock could not board the pod");
            require(player(server, clientId).startRiding(pod, true, false), "the real client could not board the pod");
        });
        ClientWait.until(
                context,
                "the client to ride the pod over layer 1's floor",
                () -> context.computeOnClient(client -> client.player != null
                        && inLayer(client, 1)
                        && vehicleOf(client) instanceof PodEntity pod
                        && pod.getPassengers().size() == 2),
                () -> context.computeOnClient(client -> ClientWait.describe(client) + ", client knows the pod "
                                + (client.level == null ? null : client.level.getEntity(made[0].getId()))
                                + ", entities "
                                + (client.level == null ? -1 : client.level.getEntityCount())
                                + ", client pod passengers "
                                + (client.level == null ? null : vehicleCarry(client, made[0].getId()))
                                + ", player passenger " + (client.player != null && client.player.isPassenger()))
                        + "; server pod: "
                        + two.server()
                                .computeOnServer(server -> made[0] + " removed " + made[0].isRemoved() + " passengers "
                                        + made[0].getPassengers() + " real vehicle "
                                        + player(server, clientId).getVehicle()));
        return made[0];
    }

    /** Waits until the held level has the area and both riders are ready, and reads what the client holds before the crossing. */
    private static Before holdArea(
            ClientGameTestContext context, TwoPlayerServer two, UUID clientId, Site site, PodEntity pod) {
        int area = AREA_SIDE * AREA_SIDE;
        ClientWait.until(
                context,
                "the held level to hold the " + area + " chunks of the arrival area",
                client -> SeamClient.background()
                        .filter(level -> level.getChunkSource().getLoadedChunksCount() >= area)
                        .isPresent(),
                client -> "held chunks "
                        + SeamClient.background()
                                .map(level -> level.getChunkSource().getLoadedChunksCount())
                                .map(String::valueOf)
                                .orElse("none (no level prepared)")
                        + ", " + ClientWait.describe(client));
        ClientWait.until(
                context,
                "both riders to be ready, or the mock to refuse",
                () -> two.server()
                        .computeOnServer(server -> SeamSender.readyToSwap(
                                        player(server, clientId), site.two(), site.centre())
                                && (!answersAccept
                                        || SeamSender.readyToSwap(two.mock().player(), site.two(), site.centre()))),
                () -> "server pod at " + pod.position());
        context.waitTicks(LIGHT_DRAIN_TICKS);
        UUID mockId = two.mock().player().getUUID();
        return context.computeOnClient(client -> new Before(
                Objects.requireNonNull(client.player),
                Objects.requireNonNull(vehicleOf(client)),
                Objects.requireNonNull(vehicleOf(client)).getId(),
                Objects.requireNonNull(vehicleOf(client)).getUUID(),
                Objects.requireNonNull(levelOf(client).getPlayerByUUID(mockId)),
                levelOf(client),
                SeamClient.background().orElseThrow()));
    }

    /** Lets the pod go and puts it under the floor, as one tick of a fall at speed would. */
    private static void drop(TwoPlayerServer two, Site site, PodEntity pod) {
        two.server().runOnServer(server -> {
            PINS.remove(pod.getUUID());
            pod.setPos(site.x(), site.floorY() - 1, site.z());
        });
    }

    /** Empties the pod and removes it, and clears the mock's answers, so the next leg starts clean. */
    private static void finish(TwoPlayerServer two, PodEntity pod) {
        two.server().runOnServer(server -> {
            answering = null;
            PINS.remove(pod.getUUID());
            TwoSeatPods.forget(pod);
            Entity current = null;
            for (ServerPlayer rider : server.getPlayerList().getPlayers()) {
                if (rider.getVehicle() != null) {
                    current = rider.getVehicle();
                    rider.stopRiding();
                }
            }
            if (current != null) {
                current.discard();
            }
            pod.discard();
        });
    }

    private static String diagnose(MinecraftServer server, UUID clientId, Site site, Vec3 hold) {
        ServerPlayer real = player(server, clientId);
        return "server: entity ticking " + site.one().isPositionEntityTicking(BlockPos.containing(hold))
                + ", real player at " + real.position() + " in "
                + real.level().dimension().identifier()
                + ", changing dimension " + real.isChangingDimension() + ", client loaded "
                + real.connection.hasClientLoaded() + ", chunk pending "
                + real.connection.chunkSender.isPending(site.centre().pack());
    }

    /** Whether the client's player is in the layer dimension of {@code layer}. */
    private static boolean inLayer(Minecraft client, int layer) {
        return client.player != null && TestLayers.isInDimensionOf(client.player, layer);
    }

    private static String vehicleCarry(Minecraft client, int id) {
        Entity entity = levelOf(client).getEntity(id);
        return entity == null ? "none" : String.valueOf(entity.getPassengers());
    }

    private static @Nullable Entity vehicleOf(Minecraft client) {
        return client.player == null ? null : client.player.getVehicle();
    }

    private static ClientLevel levelOf(Minecraft client) {
        return Objects.requireNonNull(client.level, "the client has no level");
    }

    /** Whether the client's player rides the vehicle {@code uuid}, which carries {@code riders} entities: it, the mock and nothing else. */
    private static boolean ridesWith(Minecraft client, UUID uuid, int riders) {
        Entity vehicle = vehicleOf(client);
        return vehicle != null
                && vehicle.getUUID().equals(uuid)
                && vehicle.getPassengers().size() == riders
                && vehicle.getPassengers().contains(client.player);
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

    private static ServerPlayer player(MinecraftServer server, UUID id) {
        return Objects.requireNonNull(server.getPlayerList().getPlayer(id), () -> "player " + id + " is not online");
    }

    private static ServerPlayer realPlayer(MinecraftServer server, TwoPlayerServer two) {
        return server.getPlayerList().getPlayers().stream()
                .filter(player -> player != two.mock().player())
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("The real client's player is not on the server"));
    }
}
