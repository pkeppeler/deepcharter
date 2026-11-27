package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.ClientChecks.require;

import io.github.pkeppeler.deepcharter.client.pod.TetherLineRenderer;
import io.github.pkeppeler.deepcharter.client.pod.TowChainRenderer;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.pod.PodTether;
import io.github.pkeppeler.deepcharter.test.support.ClientLight;
import io.github.pkeppeler.deepcharter.test.support.ClientSession;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.TestEntities;
import io.github.pkeppeler.deepcharter.test.support.TestLayers;
import io.github.pkeppeler.deepcharter.test.support.TwoPlayerServer;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Client GameTest for #986: the client sees the tether. The server clips the real player to a pod, the synced clip reaches the
 * client, the renderer builds a line from the pod's hitch to the person, and the line is gone once the clip is.
 */
public class TetherClientTest implements FabricClientGameTest {
    private static final int X = 800;
    private static final int Z = 800;
    private static final int FLOOR_Y = TestLayers.worldY(TestLayers.SURFACE, 200);
    /** What the client's two places may disagree by: each one is interpolated from what the server last sent. */
    private static final double CLIENT_LAG_BLOCKS = 1.5;

    @Override
    public void runTest(ClientGameTestContext context) {
        ClientTestLog.start(this);
        try (TwoPlayerServer two = TwoPlayerServer.start(context)) {
            UUID podId = two.server().computeOnServer(server -> setUp(server.overworld(), two));
            // The pod first, and the person standing. A line to a pod in a chunk that is not ticking yet ends at
            // once, like
            // one to a pod that unloaded, and a person still falling through chunks the client has not got snaps it.
            ClientWait.until(
                    context,
                    "the client sees the pod and stands on the slab",
                    client -> podIfPresent(client, podId) != null
                            && ClientSession.player(client).onGround(),
                    client -> "pod seen " + (podIfPresent(client, podId) != null) + ", on ground "
                            + ClientSession.player(client).onGround() + ", " + ClientWait.describe(client));
            two.server().runOnServer(server -> clip(server.overworld(), two, podId));
            ClientWait.until(
                    context,
                    "the client sees its own clip to the pod",
                    client -> PodTether.podId(ClientSession.player(client))
                            .filter(podId::equals)
                            .isPresent(),
                    client -> "clipped to " + PodTether.podId(ClientSession.player(client)) + ", "
                            + ClientWait.describe(client));
            context.runOnClient(client -> {
                PodEntity pod = Objects.requireNonNull(podIfPresent(client, podId));
                Vec3 camera = ClientSession.player(client).position();
                TowChainRenderer.Chain chain = Objects.requireNonNull(
                        TetherLineRenderer.chainFor(
                                ClientLight.clientLevel(client),
                                ClientSession.player(client),
                                List.of(pod),
                                1f,
                                camera.x,
                                camera.y,
                                camera.z),
                        "The renderer should draw a line from the pod to the person clipped to it");
                require(chain.curve.links > 0, "The line should have links");
                Vec3 start = new Vec3(chain.curve.x[0], chain.curve.y[0], chain.curve.z[0]);
                Vec3 end = new Vec3(
                        chain.curve.x[chain.curve.links],
                        chain.curve.y[chain.curve.links],
                        chain.curve.z[chain.curve.links]);
                double hitch = pod.chassis().width() / 2.0;
                require(
                        Math.abs(start.distanceTo(pod.getBoundingBox().getCenter()) - hitch) < CLIENT_LAG_BLOCKS,
                        "The line should start at the pod's hitch, it starts " + start);
                require(
                        end.distanceTo(ClientSession.player(client)
                                        .getBoundingBox()
                                        .getCenter())
                                < CLIENT_LAG_BLOCKS,
                        "The line should end at the person, it ends " + end);
                require(
                        TetherLineRenderer.chainFor(
                                        ClientLight.clientLevel(client),
                                        ClientSession.player(client),
                                        List.of(),
                                        1f,
                                        camera.x,
                                        camera.y,
                                        camera.z)
                                == null,
                        "The renderer should draw no line while the pod is not loaded on the client");
            });
            two.server().runOnServer(server -> {
                ServerPlayer real = real(server.overworld(), two);
                require(
                        PodTether.unclip(real, PodTether.Unclipped.UNHOOKED),
                        "the real player should have been clipped, they are at " + real.position() + ", alive "
                                + real.isAlive() + ", pod "
                                + PodTether.pod(real).map(PodEntity::position) + ", entity "
                                + server.overworld().getEntity(podId));
            });
            ClientWait.until(
                    context,
                    "the client sees the clip come off",
                    client -> !PodTether.isClipped(ClientSession.player(client)));
            context.runOnClient(client -> {
                PodEntity pod = Objects.requireNonNull(podIfPresent(client, podId));
                Vec3 camera = ClientSession.player(client).position();
                require(
                        TetherLineRenderer.chainFor(
                                        ClientLight.clientLevel(client),
                                        ClientSession.player(client),
                                        List.of(pod),
                                        1f,
                                        camera.x,
                                        camera.y,
                                        camera.z)
                                == null,
                        "The renderer should draw no line once the clip is off");
            });
        }
    }

    private static @Nullable PodEntity podIfPresent(Minecraft client, UUID id) {
        return ClientLight.clientLevel(client).getEntity(id) instanceof PodEntity pod ? pod : null;
    }

    private static ServerPlayer real(ServerLevel level, TwoPlayerServer two) {
        return level.getServer().getPlayerList().getPlayers().stream()
                .filter(player -> player != two.mock().player())
                .findFirst()
                .orElseThrow();
    }

    /** A stone slab high above the terrain, the real player on it beside a pod. */
    private static UUID setUp(ServerLevel level, TwoPlayerServer two) {
        // The chunks the slab and the pod stand in stay loaded while the real player is teleported in: a pod whose
        // chunk unloads in
        // between ends its line, as it should, and the test is about the drawn line.
        for (int chunkX = (X - 6) >> 4; chunkX <= (X + 8) >> 4; chunkX++) {
            for (int chunkZ = (Z - 6) >> 4; chunkZ <= (Z + 8) >> 4; chunkZ++) {
                // force-ok: a client test has no GameTestHelper, and the dedicated server is closed with the test
                level.setChunkForced(chunkX, chunkZ, true);
            }
        }
        for (int x = X - 6; x <= X + 8; x++) {
            for (int z = Z - 6; z <= Z + 8; z++) {
                for (int y = FLOOR_Y - 8; y <= FLOOR_Y + 10; y++) {
                    level.setBlock(
                            new BlockPos(x, y, z), (y < FLOOR_Y ? Blocks.STONE : Blocks.AIR).defaultBlockState(), 3);
                }
            }
        }
        ServerPlayer real = real(level, two);
        real.teleportTo(level, X + 3.5, FLOOR_Y, Z + 3.5, Set.of(), 0, 0, true);
        two.mock().teleportTo(level, new Vec3(X - 3.5, FLOOR_Y, Z - 3.5), 0, 0);
        PodEntity pod = TestEntities.create(PodRegistry.POD, level);
        pod.setPos(X + 0.5, FLOOR_Y, Z + 0.5);
        level.addFreshEntity(pod);
        return pod.getUUID();
    }

    private static void clip(ServerLevel level, TwoPlayerServer two, UUID podId) {
        PodTether.clip(real(level, two), TestEntities.require(level, podId, PodEntity.class));
    }
}
