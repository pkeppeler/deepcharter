package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.ClientChecks.require;
import static io.github.pkeppeler.deepcharter.test.support.SeamSite.HOLD_HEIGHT;

import io.github.pkeppeler.deepcharter.client.layer.BreachEffects;
import io.github.pkeppeler.deepcharter.client.layer.SeamClient;
import io.github.pkeppeler.deepcharter.layer.SeamSender;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.test.support.ChunkPacketLog;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.SeamPodScene;
import io.github.pkeppeler.deepcharter.test.support.SeamSite.Site;
import io.github.pkeppeler.deepcharter.test.support.TestLayers;
import io.github.pkeppeler.deepcharter.test.support.TwoPlayerServer;
import io.github.pkeppeler.deepcharter.test.support.TwoSeatPods;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Client GameTest of the seam's arrival data (#1185, ADR 0089), on the shipped layer_1 to layer_2 crossing, with a real
 * client on a dedicated server. Two crossings. In the first the client rides a pod over the floor with a mock that
 * refuses the swap, until its background level holds the arrival area, and the pod falls through: the whole pod is
 * teleported (a vehicle never splits), the client's level after the crossing is the level it held, the section the
 * camera is in is meshed, and the chunk packets vanilla sent at the crossing are only those near the pocket. In the
 * second there is no lead, so the crossing finds no background level and falls back to the vanilla path: a fresh level,
 * the camera's section meshed, and the whole area sent by vanilla. Each logs a {@code SEAM-TIMING} line; no frame
 * budget is asserted, since CI renders in software.
 *
 */
public class SeamAdoptionClientTest implements FabricClientGameTest {
    private static final Logger LOGGER = LoggerFactory.getLogger("SeamAdoptionClientTest");
    private static final double ADOPTED_X = 3000.5;
    private static final double FALLBACK_X = 5000.5;
    private static final double Z = 3000.5;
    /** Chunks the area sent ahead covers on a side. */
    private static final int AREA_SIDE = 2 * SeamSender.SEND_RADIUS + 1;
    /** Client ticks since the first test of the class, for the timing log: the level's game time is the server's, and restarts per level. */
    private static final AtomicLong CLIENT_TICKS = new AtomicLong();

    private static final Crossing CROSSING = new Crossing();

    private static boolean counting;

    /**
     * Watches the client's ticks for a crossing into one level: the first tick the client's level is that one is the respawn handled,
     * and the first tick after it that the connection says the client is loaded ends the load. Written on the client thread, read by the test.
     */
    private static final class Crossing {
        private volatile @Nullable ResourceKey<Level> target;
        private volatile long crossedNanos = -1;
        private volatile long crossedTicks = -1;
        private volatile long loadedTicks = -1;

        void arm(ResourceKey<Level> dimension) {
            crossedNanos = -1;
            crossedTicks = -1;
            loadedTicks = -1;
            target = dimension;
        }

        boolean crossed() {
            return crossedTicks >= 0;
        }

        void tick(Minecraft client, long ticks) {
            ResourceKey<Level> dimension = target;
            if (dimension == null
                    || client.level == null
                    || !client.level.dimension().equals(dimension)) {
                return;
            }
            if (crossedTicks < 0) {
                crossedNanos = System.nanoTime();
                crossedTicks = ticks;
            }
            ClientPacketListener connection = client.getConnection();
            if (loadedTicks < 0 && connection != null && connection.hasClientLoaded()) {
                loadedTicks = ticks;
            }
        }
    }

    @Override
    public void runTest(ClientGameTestContext context) {
        ClientTestLog.start(this);
        if (!counting) {
            counting = true;
            // An event cannot be unregistered; it only counts.
            ClientTickEvents.END_CLIENT_TICK.register(client -> CROSSING.tick(client, CLIENT_TICKS.incrementAndGet()));
        }
        TwoSeatPods.armClient(true);
        try (TwoPlayerServer two = TwoPlayerServer.start(context)) {
            UUID clientId = two.server()
                    .computeOnServer(server -> realPlayer(server, two).getUUID());
            adopted(context, two, clientId);
            fallback(context, two, clientId);
        } finally {
            TwoSeatPods.armClient(false);
            ChunkPacketLog.disarm();
        }
    }

    private static void adopted(ClientGameTestContext context, TwoPlayerServer two, UUID clientId) {
        Site site = two.server().computeOnServer(server -> siteAt(server, ADOPTED_X));
        // The pod carries a mock that refuses the swap, so the vehicle is teleported whole and the real client adopts
        // the level it held.
        PodEntity pod = SeamPodScene.seat(context, two, clientId, site, false);
        SeamPodScene.Before before = SeamPodScene.holdArea(context, two, clientId, site, pod);
        ChunkPacketLog.arm();
        CROSSING.arm(site.two().dimension());
        SeamPodScene.drop(two, site, pod);
        ClientWait.until(
                context,
                "the client to fall into layer 2",
                client -> SeamPodScene.inLayer(client, 2),
                ClientWait::describe);
        require(
                context.computeOnClient(client -> client.level == before.held()),
                "the client's level after the crossing is not the background level it was sent");
        waitCrossed(context);
        waitMeshed(context, "adopted");
        logTiming(context, "adopted");
        require(
                context.computeOnClient(client -> {
                    ClientLevel shown = Objects.requireNonNull(client.level, "the client has no level");
                    return SeamClient.background().isEmpty() && holdsWholeArea(shown, pocketOf(client));
                }),
                "the adopted level does not hold the whole area that was sent ahead");
        ChunkPos pocket = context.computeOnClient(SeamAdoptionClientTest::pocketOf);
        List<ChunkPacketLog.Entry> resent = new ArrayList<>();
        for (ChunkPacketLog.Entry entry : ChunkPacketLog.disarm()) {
            if (!entry.level().equals(site.two().dimension())) {
                continue;
            }
            boolean inArea = Math.abs(entry.x() - pocket.x()) <= SeamSender.SEND_RADIUS
                    && Math.abs(entry.z() - pocket.z()) <= SeamSender.SEND_RADIUS;
            if (inArea && Math.max(Math.abs(entry.x() - pocket.x()), Math.abs(entry.z() - pocket.z())) > 1) {
                resent.add(entry);
            }
        }
        require(
                resent.isEmpty(),
                "the crossing sent chunks of the area far from the pocket " + pocket + " the way vanilla does: "
                        + resent);
        SeamPodScene.finish(two, pod);
    }

    private static ChunkPos pocketOf(Minecraft client) {
        return Objects.requireNonNull(client.player, "the client has no player").chunkPosition();
    }

    /** Whether {@code level} holds each chunk of the area sent ahead round {@code pocket}. */
    private static boolean holdsWholeArea(ClientLevel level, ChunkPos pocket) {
        for (int dx = -SeamSender.SEND_RADIUS; dx <= SeamSender.SEND_RADIUS; dx++) {
            for (int dz = -SeamSender.SEND_RADIUS; dz <= SeamSender.SEND_RADIUS; dz++) {
                if (level.getChunkSource().getChunk(pocket.x() + dx, pocket.z() + dz, false) == null) {
                    return false;
                }
            }
        }
        return true;
    }

    private static void fallback(ClientGameTestContext context, TwoPlayerServer two, UUID clientId) {
        Site site = two.server().computeOnServer(server -> siteAt(server, FALLBACK_X));
        two.server().runOnServer(server -> {
            site.carveShaft(site.x(), HOLD_HEIGHT + 2);
            player(server, clientId)
                    .teleportTo(site.one(), site.x(), site.floorY() + 3, site.z(), Set.of(), 0, 0, true);
        });
        ClientWait.until(
                context,
                "the client back in layer 1",
                client -> client.player != null && TestLayers.isInDimensionOf(client.player, 1),
                ClientWait::describe);
        ChunkPacketLog.arm();
        CROSSING.arm(site.two().dimension());
        // No lead: the client is under the floor the first tick the server sees it near it, so no area was sent.
        two.server()
                .runOnServer(server -> player(server, clientId)
                        .teleportTo(site.one(), site.x(), site.floorY() - 1, site.z(), Set.of(), 0, 0, true));
        ClientWait.until(
                context,
                "the client to cross into layer 2",
                client -> client.player != null && TestLayers.isInDimensionOf(client.player, 2),
                ClientWait::describe);
        waitCrossed(context);
        waitMeshed(context, "fallback");
        logTiming(context, "fallback");
        ChunkPos pocket = context.computeOnClient(SeamAdoptionClientTest::pocketOf);
        // The server generates the area as it sends it (nothing was loaded at the crossing), and the corners come last:
        // the camera's
        // section being meshed says nothing of them, so wait for the whole area before reading the log.
        ClientWait.until(
                context,
                "vanilla to send each of the " + AREA_SIDE * AREA_SIDE
                        + " chunks of the area after the fallback crossing",
                () -> missing(site, pocket).isEmpty(),
                () -> "missing " + missing(site, pocket));
        require(
                context.computeOnClient(client -> SeamClient.background().isEmpty()),
                "the fallback crossing left a background level, which nothing prepared");
    }

    /** The chunks of the area round {@code pocket} that no layer 2 chunk packet has yet delivered. */
    private static List<ChunkPos> missing(Site site, ChunkPos pocket) {
        Set<ChunkPos> received = new HashSet<>();
        ChunkPacketLog.entries().stream()
                .filter(entry -> entry.level().equals(site.two().dimension()))
                .forEach(entry -> received.add(new ChunkPos(entry.x(), entry.z())));
        List<ChunkPos> missing = new ArrayList<>();
        for (int dx = -SeamSender.SEND_RADIUS; dx <= SeamSender.SEND_RADIUS; dx++) {
            for (int dz = -SeamSender.SEND_RADIUS; dz <= SeamSender.SEND_RADIUS; dz++) {
                ChunkPos pos = new ChunkPos(pocket.x() + dx, pocket.z() + dz);
                if (!received.contains(pos)) {
                    missing.add(pos);
                }
            }
        }
        return missing;
    }

    /** Waits until the section the camera is in is compiled and visible: the renderer, vanilla's or Sodium's, can draw where the player is. */
    private static void waitMeshed(ClientGameTestContext context, String what) {
        ClientWait.until(
                context,
                "the section of the camera to be meshed after the " + what + " crossing",
                client -> client.getCameraEntity() != null
                        && client.levelRenderer.isSectionCompiledAndVisible(
                                client.getCameraEntity().blockPosition(), 0L),
                ClientWait::describe);
    }

    /** Waits until the client has ticked in the level it crossed into, so the timing's start is set. */
    private static void waitCrossed(ClientGameTestContext context) {
        ClientWait.until(
                context,
                "the client to tick in the level it crossed into",
                client -> CROSSING.crossed(),
                ClientWait::describe);
    }

    private static void logTiming(ClientGameTestContext context, String kind) {
        long millis = (System.nanoTime() - CROSSING.crossedNanos) / 1_000_000;
        long loaded = CROSSING.loadedTicks;
        long heapMb = (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / (1024 * 1024);
        long seamBytes = context.computeOnClient(client -> SeamClient.bytesReceived());
        LOGGER.info(
                "SEAM-TIMING {} crossing_to_meshed_ms={} client_ticks={} respawn_to_loaded_ticks={} fade_hold_ticks={} heap_mb={} seam_bytes={}",
                kind,
                millis,
                CLIENT_TICKS.get() - CROSSING.crossedTicks,
                loaded < 0 ? "not_loaded" : String.valueOf(loaded - CROSSING.crossedTicks),
                context.computeOnClient(client -> BreachEffects.heldTicks()),
                heapMb,
                seamBytes);
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
