package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.ClientChecks.require;

import io.github.pkeppeler.deepcharter.client.pod.TowChainRenderer;
import io.github.pkeppeler.deepcharter.client.pod.TowPickLineRenderer;
import io.github.pkeppeler.deepcharter.pod.Chassis;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.pod.PodTowing;
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
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Client GameTest for #1605: a real client on foot hooks two pods with real right-clicks on the crosshair, the way the
 * seat could not. The crosshair is aimed at the first pod and right-clicked: the pod is picked, and the client sees the
 * pick and draws the line to it. It is aimed at the second and right-clicked: the first pod tows the second, which a
 * crewmate sits in, and the client sees the cable and the pick gone. A right-click on the towed pod takes the cable
 * off. The tower of the cable is the pod the person clicked first.
 */
public class TowCableOnFootClientTest implements FabricClientGameTest {
    private static final int X = 700;
    private static final int Z = 700;
    private static final int FLOOR_Y = TestLayers.worldY(TestLayers.SURFACE, 200);
    private static final double WIDTH = Chassis.MOLE.width();
    /**
     * Blocks the person stands off the first pod's east face, level with it, so the box is the nearest thing beside the
     * crosshair.
     */
    private static final double OFF_FIRST = 0.3;
    /**
     * Blocks between the two pods' middles along z: the second lies straight ahead of the person, past the first pod's
     * face.
     */
    private static final double APART = 3;

    private static final Vec3 FIRST_AT = new Vec3(X + 0.5, FLOOR_Y, Z + 0.5);
    private static final Vec3 SECOND_AT = FIRST_AT.add(WIDTH / 2 + OFF_FIRST, 0, APART);
    private static final Vec3 STAND_AT = FIRST_AT.add(WIDTH / 2 + OFF_FIRST, 0, -0.5);

    private record Rig(UUID first, UUID second) {}

    @Override
    public void runTest(ClientGameTestContext context) {
        ClientTestLog.start(this);
        try (TwoPlayerServer two = TwoPlayerServer.start(context)) {
            Rig rig = two.server().computeOnServer(server -> setUp(server.overworld(), two));
            ClientWait.until(
                    context,
                    "the client sees both pods",
                    client -> podIfPresent(client, rig.first()) != null && podIfPresent(client, rig.second()) != null);

            aimAt(context, two, rig.first());
            ClientWait.until(
                    context,
                    "the crosshair on the first pod",
                    client -> crosshairPod(client).filter(rig.first()::equals).isPresent(),
                    client -> "the crosshair is on " + crosshairPod(client));
            context.getInput().pressKey(options -> options.keyUse);
            // Split the wait: the server's pick first, so a click the server undid is told apart from a client
            // sync that did not arrive.
            ClientWait.until(
                    context,
                    "the server holding the first pod as the person's pick",
                    () -> two.server()
                            .computeOnServer(server -> PodTowing.pickedPodId(real(server.overworld(), two))
                                    .filter(rig.first()::equals)
                                    .isPresent()),
                    () -> two.server().computeOnServer(server -> {
                        ServerPlayer person = real(server.overworld(), two);
                        return "the server's pick is " + PodTowing.pickedPodId(person) + ", the person holds "
                                + person.getMainHandItem() + " and rides " + person.getVehicle() + ", "
                                + person.distanceTo(TestEntities.require(server.overworld(), rig.first()))
                                + " blocks from the first pod";
                    }));
            ClientWait.until(
                    context,
                    "the client sees the first pod picked",
                    client -> PodTowing.pickedPodId(ClientSession.player(client))
                            .filter(rig.first()::equals)
                            .isPresent());
            context.runOnClient(client -> {
                var camera = ClientSession.player(client).position();
                require(
                        TowPickLineRenderer.chainFor(
                                        ClientLight.clientLevel(client),
                                        ClientSession.player(client),
                                        List.of(pod(client, rig.first()), pod(client, rig.second())),
                                        1f,
                                        camera.x,
                                        camera.y,
                                        camera.z)
                                != null,
                        "The client should draw a line from the picked pod to the person");
            });
            two.server()
                    .runOnServer(server -> require(
                            !PodTowing.isTowed(TestEntities.require(server.overworld(), rig.second(), PodEntity.class)),
                            "the first click should hook nothing"));

            aimAt(context, two, rig.second());
            ClientWait.until(
                    context,
                    "the crosshair on the second pod",
                    client -> crosshairPod(client).filter(rig.second()::equals).isPresent(),
                    client -> "the crosshair is on " + crosshairPod(client));
            context.getInput().pressKey(options -> options.keyUse);
            ClientWait.until(
                    context,
                    "the client sees the cable on the second pod and the pick gone",
                    client -> Optional.of(rig.first()).equals(PodTowing.towerId(pod(client, rig.second())))
                            && PodTowing.pickedPodId(ClientSession.player(client))
                                    .isEmpty());
            ClientWait.until(
                    context,
                    "the crewmate in the towed pod to be told they are towed",
                    () -> two.mock()
                            .actionBarMessages()
                            .contains(Component.translatable("message.deepcharter.towing.being_towed")),
                    () -> "the crewmate was told " + two.mock().actionBarMessages());
            context.runOnClient(client -> {
                var camera = ClientSession.player(client).position();
                TowChainRenderer.Chain chain = TowChainRenderer.chainFor(
                        ClientLight.clientLevel(client),
                        pod(client, rig.second()),
                        List.of(pod(client, rig.first()), pod(client, rig.second())),
                        1f,
                        camera.x,
                        camera.y,
                        camera.z);
                require(chain != null, "The client should draw the cable between the two pods");
            });

            context.getInput().pressKey(options -> options.keyUse);
            ClientWait.until(
                    context,
                    "the client sees the cable come off the towed pod",
                    client -> !PodTowing.isTowed(pod(client, rig.second())));
        }
    }

    /** Turns the person to look at the middle of the pod, from their eyes. */
    private static void aimAt(ClientGameTestContext context, TwoPlayerServer two, UUID pod) {
        two.server().runOnServer(server -> {
            ServerLevel level = server.overworld();
            ServerPlayer real = real(level, two);
            PodEntity target = TestEntities.require(level, pod, PodEntity.class);
            Vec3 toward = target.getBoundingBox().getCenter().subtract(real.getEyePosition());
            float yaw = (float) Math.toDegrees(Math.atan2(-toward.x, toward.z));
            float pitch = (float) -Math.toDegrees(Math.atan2(toward.y, Math.hypot(toward.x, toward.z)));
            real.teleportTo(level, STAND_AT.x, STAND_AT.y, STAND_AT.z, Set.of(), yaw, pitch, true);
        });
        context.waitTicks(5);
    }

    private static Optional<UUID> crosshairPod(Minecraft client) {
        return client.hitResult instanceof EntityHitResult hit && hit.getEntity() instanceof PodEntity pod
                ? Optional.of(pod.getUUID())
                : Optional.empty();
    }

    private static PodEntity pod(Minecraft client, UUID id) {
        return Objects.requireNonNull(podIfPresent(client, id), () -> "the client sees no pod " + id);
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

    /** A stone slab high above the terrain, the real person on it with a cable, the mock in the second pod. */
    private static Rig setUp(ServerLevel level, TwoPlayerServer two) {
        fill(level, FLOOR_Y - 8, FLOOR_Y - 1, Blocks.STONE);
        fill(level, FLOOR_Y, FLOOR_Y + 10, Blocks.AIR);
        ServerPlayer real = real(level, two);
        real.teleportTo(level, STAND_AT.x, STAND_AT.y, STAND_AT.z, Set.of(), 0, 0, true);
        real.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(PodRegistry.TOW_CABLE));
        PodEntity first = spawn(level, FIRST_AT);
        PodEntity second = spawn(level, SECOND_AT);
        two.mock().teleportTo(level, SECOND_AT, 0, 0);
        require(two.mock().player().startRiding(second), "the crewmate could not mount the second pod");
        return new Rig(first.getUUID(), second.getUUID());
    }

    private static PodEntity spawn(ServerLevel level, Vec3 at) {
        PodEntity pod = TestEntities.create(PodRegistry.POD, level);
        pod.setPos(at);
        level.addFreshEntity(pod);
        return pod;
    }

    private static void fill(ServerLevel level, int yFrom, int yTo, Block block) {
        for (int x = X - 4; x <= X + 12; x++) {
            for (int y = yFrom; y <= yTo; y++) {
                for (int z = Z - 6; z <= Z + 10; z++) {
                    level.setBlock(new BlockPos(x, y, z), block.defaultBlockState(), 3);
                }
            }
        }
    }
}
