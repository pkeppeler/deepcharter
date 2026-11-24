package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.ClientChecks.require;
import static io.github.pkeppeler.deepcharter.test.support.SeamSite.HOLD_HEIGHT;

import io.github.pkeppeler.deepcharter.client.layer.SeamClient;
import io.github.pkeppeler.deepcharter.layer.SeamSender;
import io.github.pkeppeler.deepcharter.test.support.ChunkPacketLog;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.SeamSite.Site;
import io.github.pkeppeler.deepcharter.test.support.TestLayers;
import io.github.pkeppeler.deepcharter.test.support.TwoPlayerServer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Client GameTest of the seam's arrival data (#1185, ADR 0089), on the shipped layer_1 to layer_2 crossing, with a real client on a
 * dedicated server. Two crossings. In the first the client hovers inside the floor margin until its background level holds the arrival
 * area, then falls through: the client's level after the crossing is that level, and the section the camera is in is meshed, and the
 * chunk packets vanilla sent at the crossing are only those near the pocket. In the second there is no lead, so the crossing finds no
 * background level and falls back to the vanilla path: a fresh level, the camera's section meshed, and the whole area sent by vanilla.
 * Each logs a {@code SEAM-TIMING} line; no frame budget is asserted, since CI renders in software.
 *
 * <p>Not run in the cloud container, which has no display: CI is the first run of it.
 */
public class SeamAdoptionClientTest implements FabricClientGameTest {
    private static final Logger LOGGER = LoggerFactory.getLogger("SeamAdoptionClientTest");
    private static final double ADOPTED_X = 3000.5;
    private static final double FALLBACK_X = 5000.5;
    private static final double Z = 3000.5;
    /** Chunks the area sent ahead covers on a side. */
    private static final int AREA_SIDE = 2 * SeamSender.SEND_RADIUS + 1;
    /** Client ticks the held level's light gets to drain after its last chunk arrives. */
    private static final int LIGHT_DRAIN_TICKS = 40;

    @Override
    public void runTest(ClientGameTestContext context) {
        ClientTestLog.start(this);
        try (TwoPlayerServer two = TwoPlayerServer.start(context)) {
            UUID clientId = two.server()
                    .computeOnServer(server -> realPlayer(server, two).getUUID());
            adopted(context, two, clientId);
            fallback(context, two, clientId);
        } finally {
            ChunkPacketLog.disarm();
        }
    }

    private static void adopted(ClientGameTestContext context, TwoPlayerServer two, UUID clientId) {
        Site site = two.server().computeOnServer(server -> siteAt(server, ADOPTED_X));
        two.server().runOnServer(server -> {
            site.carveShaft(site.x(), HOLD_HEIGHT + 2);
            ServerPlayer player = player(server, clientId);
            player.teleportTo(site.one(), site.x(), site.floorY() + HOLD_HEIGHT, site.z(), Set.of(), 0, 0, true);
            player.setNoGravity(true);
        });
        int area = AREA_SIDE * AREA_SIDE;
        ClientWait.until(
                context,
                "the background level to hold the " + area + " chunks of the arrival area",
                client -> SeamClient.background()
                        .filter(level -> level.getChunkSource().getLoadedChunksCount() >= area)
                        .isPresent(),
                client -> "background chunks "
                        + SeamClient.background()
                                .map(level -> level.getChunkSource().getLoadedChunksCount())
                                .map(String::valueOf)
                                .orElse("none (no level prepared)")
                        + ", " + ClientWait.describe(client));
        context.waitTicks(LIGHT_DRAIN_TICKS);
        ClientLevel held =
                context.computeOnClient(client -> SeamClient.background().orElseThrow());
        ChunkPacketLog.arm();
        long started = System.nanoTime();
        two.server().runOnServer(server -> player(server, clientId).setNoGravity(false));
        ClientWait.until(
                context,
                "the client to fall into layer 2",
                client -> client.player != null && TestLayers.isInDimensionOf(client.player, 2),
                ClientWait::describe);
        require(
                context.computeOnClient(client -> client.level == held),
                "the client's level after the crossing is not the background level it was sent");
        waitMeshed(context, "adopted");
        logTiming(context, "adopted", started);
        ChunkPos pocket =
                context.computeOnClient(client -> Objects.requireNonNull(client.player, "the client has no player")
                        .chunkPosition());
        List<ChunkPacketLog.Entry> resent = new ArrayList<>();
        for (ChunkPacketLog.Entry entry : ChunkPacketLog.disarm()) {
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
        require(SeamClient.background().isEmpty(), "the client still holds a background level after adopting it");
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
        long started = System.nanoTime();
        // No lead: the client is under the floor the first tick the server sees it near it, so no area was sent.
        two.server()
                .runOnServer(server -> player(server, clientId)
                        .teleportTo(site.one(), site.x(), site.floorY() - 1, site.z(), Set.of(), 0, 0, true));
        ClientWait.until(
                context,
                "the client to cross into layer 2",
                client -> client.player != null && TestLayers.isInDimensionOf(client.player, 2),
                ClientWait::describe);
        waitMeshed(context, "fallback");
        logTiming(context, "fallback", started);
        ChunkPos pocket =
                context.computeOnClient(client -> Objects.requireNonNull(client.player, "the client has no player")
                        .chunkPosition());
        Set<ChunkPos> received = new HashSet<>();
        ChunkPacketLog.disarm().stream()
                .filter(entry -> entry.level().equals(site.two().dimension()))
                .forEach(entry -> received.add(new ChunkPos(entry.x(), entry.z())));
        for (int dx = -SeamSender.SEND_RADIUS; dx <= SeamSender.SEND_RADIUS; dx++) {
            for (int dz = -SeamSender.SEND_RADIUS; dz <= SeamSender.SEND_RADIUS; dz++) {
                ChunkPos pos = new ChunkPos(pocket.x() + dx, pocket.z() + dz);
                require(received.contains(pos), "the fallback crossing never sent the chunk " + pos + " of the area");
            }
        }
        require(
                SeamClient.background().isEmpty(),
                "the fallback crossing left a background level, which nothing prepared");
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

    private static void logTiming(ClientGameTestContext context, String kind, long startedNanos) {
        long millis = (System.nanoTime() - startedNanos) / 1_000_000;
        long heapMb = (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / (1024 * 1024);
        long seamBytes = context.computeOnClient(client -> SeamClient.bytesReceived());
        LOGGER.info(
                "SEAM-TIMING {} crossing_to_meshed_ms={} ticks={} heap_mb={} seam_bytes={}",
                kind,
                millis,
                context.computeOnClient(client -> client.level == null ? 0L : client.level.getGameTime()),
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
