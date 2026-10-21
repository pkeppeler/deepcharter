package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.ClientChecks.require;

import io.github.pkeppeler.deepcharter.client.pod.TowChainRenderer;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.pod.PodTowing;
import io.github.pkeppeler.deepcharter.pod.TowTuning;
import io.github.pkeppeler.deepcharter.test.support.ClientLight;
import io.github.pkeppeler.deepcharter.test.support.ClientSession;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.TestEntities;
import io.github.pkeppeler.deepcharter.test.support.TestLayers;
import io.github.pkeppeler.deepcharter.test.support.TwoPlayerServer;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Client GameTest: a remote client sees a tow. The mock pilot drives a tower, and the real client, which rides nothing, sees the
 * synced cable on the pod behind it, sees that pod follow, and sees the cable come off.
 */
public class TowingClientTest implements FabricClientGameTest {
    private static final int X = 700;
    private static final int Z = 700;
    private static final int FLOOR_Y = TestLayers.worldY(TestLayers.SURFACE, 200);
    /** Long enough for the tower to drive out the 4.9 blocks of slack the pod starts with and then pull it 5 blocks. */
    private static final int DRIVE_TICKS = 100;

    private static final double MIN_FOLLOWED_BLOCKS = 5;
    /** What the client's pods may disagree by: each one is interpolated from what the server last sent. */
    private static final double CLIENT_LAG_BLOCKS = 1.5;

    private static final Input FORWARD = new Input(true, false, false, false, false, false, false);

    /** The two pods by UUID: a pod whose chunk is unloaded and loaded again comes back as a new entity with a new id. */
    private record Rig(UUID tower, UUID towed) {}

    @Override
    public void runTest(ClientGameTestContext context) {
        ClientTestLog.start(this);
        try (TwoPlayerServer two = TwoPlayerServer.start(context)) {
            Rig rig = two.server().computeOnServer(server -> setUp(server.overworld(), two));
            ClientWait.until(
                    context,
                    "the client sees both pods with the cable between them",
                    client -> podIfPresent(client, rig.tower()) != null
                            && podIfPresent(client, rig.towed()) != null
                            && Optional.of(rig.tower()).equals(PodTowing.towerId(pod(client, rig.towed()))));
            Vec3 towedStart =
                    context.computeOnClient(client -> pod(client, rig.towed()).position());

            two.server().runOnServer(server -> two.mock().setInput(FORWARD));
            context.waitTicks(DRIVE_TICKS);
            two.server().runOnServer(server -> two.mock().releaseInput());
            context.waitTicks(10);

            Vec3 towedEnd =
                    context.computeOnClient(client -> pod(client, rig.towed()).position());
            Vec3 towerEnd =
                    context.computeOnClient(client -> pod(client, rig.tower()).position());
            double followed = towedEnd.subtract(towedStart).horizontalDistance();
            require(
                    !(followed < MIN_FOLLOWED_BLOCKS),
                    "The client should see the towed pod follow its tower, it moved " + followed + " blocks");
            double gap = towedEnd.distanceTo(towerEnd);
            double trail = context.computeOnClient(
                    client -> TowTuning.DEFAULT.trailDistance(pod(client, rig.tower()), pod(client, rig.towed())));
            require(
                    !(gap > trail + CLIENT_LAG_BLOCKS),
                    "The client should see the towed pod within the cable's trail of its tower, it is " + gap
                            + " blocks away");

            context.runOnClient(client -> {
                PodEntity tower = pod(client, rig.tower());
                PodEntity towed = pod(client, rig.towed());
                List<PodEntity> pods = List.of(tower, towed);
                Vec3 camera = ClientSession.player(client).position();
                TowChainRenderer.Chain chain = Objects.requireNonNull(
                        TowChainRenderer.chainFor(
                                ClientLight.clientLevel(client), towed, pods, 1f, camera.x, camera.y, camera.z),
                        "The renderer should draw a line for the towed pod");
                require(chain.curve.links > 0, "The renderer should draw a line for the towed pod");
                double hitchGap = Math.sqrt(Math.pow(chain.curve.x[0] - chain.curve.x[chain.curve.links], 2)
                        + Math.pow(chain.curve.y[0] - chain.curve.y[chain.curve.links], 2)
                        + Math.pow(chain.curve.z[0] - chain.curve.z[chain.curve.links], 2));
                double trailLine = TowTuning.DEFAULT.lineLength();
                require(
                        hitchGap < trailLine + CLIENT_LAG_BLOCKS,
                        "The line between the hitches is at most the rope and a little lag, it is " + hitchGap);
                require(
                        TowChainRenderer.chainFor(
                                        ClientLight.clientLevel(client), tower, pods, 1f, camera.x, camera.y, camera.z)
                                == null,
                        "The renderer should draw no line for the tower");
                require(
                        TowChainRenderer.chainFor(
                                        ClientLight.clientLevel(client),
                                        towed,
                                        List.of(towed),
                                        1f,
                                        camera.x,
                                        camera.y,
                                        camera.z)
                                == null,
                        "The renderer should draw no line while the tower is not loaded on the client");
            });

            two.server().runOnServer(server -> {
                require(
                        PodTowing.detach(TestEntities.require(server.overworld(), rig.towed(), PodEntity.class)),
                        "the towed pod should have a cable to take off");
            });
            ClientWait.until(
                    context,
                    "the client sees the cable come off the towed pod",
                    client ->
                            podIfPresent(client, rig.towed()) != null && !PodTowing.isTowed(pod(client, rig.towed())));
            context.runOnClient(client -> {
                PodEntity towed = pod(client, rig.towed());
                Vec3 camera = ClientSession.player(client).position();
                require(
                        TowChainRenderer.chainFor(
                                        ClientLight.clientLevel(client),
                                        towed,
                                        List.of(pod(client, rig.tower()), towed),
                                        1f,
                                        camera.x,
                                        camera.y,
                                        camera.z)
                                == null,
                        "The renderer should draw no line once the cable is off");
            });
        }
    }

    /** The client's copy of the pod, or null while it does not see it. */
    private static PodEntity pod(Minecraft client, UUID id) {
        return Objects.requireNonNull(podIfPresent(client, id), () -> "the client sees no pod " + id);
    }

    private static @Nullable PodEntity podIfPresent(Minecraft client, UUID id) {
        return ClientLight.clientLevel(client).getEntity(id) instanceof PodEntity pod ? pod : null;
    }

    /** A stone slab high above the terrain, the real player on it, the mock in a tower with a pod on a cable behind it. */
    private static Rig setUp(ServerLevel level, TwoPlayerServer two) {
        fill(level, FLOOR_Y - 8, FLOOR_Y - 1, Blocks.STONE);
        fill(level, FLOOR_Y, FLOOR_Y + 10, Blocks.AIR);
        ServerPlayer real = level.getServer().getPlayerList().getPlayers().stream()
                .filter(player -> player != two.mock().player())
                .findFirst()
                .orElseThrow();
        real.teleportTo(level, X + 4.5, FLOOR_Y, Z + 8.5, Set.of(), 0, 0, true);
        Vec3 at = new Vec3(X + 0.5, FLOOR_Y, Z + 0.5);
        two.mock().teleportTo(level, at, 0, 0);
        PodEntity tower = spawn(level, at);
        PodEntity towed = spawn(level, at.add(0, 0, -2));
        require(two.mock().player().startRiding(tower), "the mock pilot could not mount the tower");
        PodTowing.attach(tower, towed);
        return new Rig(tower.getUUID(), towed.getUUID());
    }

    private static PodEntity spawn(ServerLevel level, Vec3 at) {
        PodEntity pod = TestEntities.create(PodRegistry.POD, level);
        pod.setPos(at);
        level.addFreshEntity(pod);
        return pod;
    }

    private static void fill(ServerLevel level, int yFrom, int yTo, Block block) {
        for (int x = X - 4; x <= X + 5; x++) {
            for (int y = yFrom; y <= yTo; y++) {
                for (int z = Z - 6; z <= Z + 16; z++) {
                    level.setBlock(new BlockPos(x, y, z), block.defaultBlockState(), 3);
                }
            }
        }
    }
}
