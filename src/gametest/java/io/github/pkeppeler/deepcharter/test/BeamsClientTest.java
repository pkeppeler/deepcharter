package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.ClientChecks.require;

import io.github.pkeppeler.deepcharter.charter.CharterId;
import io.github.pkeppeler.deepcharter.charter.Charters;
import io.github.pkeppeler.deepcharter.client.beam.BeamAim;
import io.github.pkeppeler.deepcharter.client.beam.BeamDraw;
import io.github.pkeppeler.deepcharter.client.beam.PodBeams;
import io.github.pkeppeler.deepcharter.client.pod.PodGeoRenderState;
import io.github.pkeppeler.deepcharter.client.theme.BeamLook;
import io.github.pkeppeler.deepcharter.pod.PodComponents;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.test.support.ClientLight;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.TestEntities;
import io.github.pkeppeler.deepcharter.upgrade.ComponentItems;
import io.github.pkeppeler.deepcharter.upgrade.ComponentTrack;
import java.util.Objects;
import java.util.Set;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Client GameTest for #248 against real blocks and pods: a pod with a lights part shows a headlamp beam and a pod without one shows
 * none; the beam is full length through open air and cut short by a wall of real blocks, and has no length at all when the lamp is
 * inside rock; a stranded pod, which loses its light block, loses its beam with it.
 */
public class BeamsClientTest implements FabricClientGameTest {
    private static final double TOLERANCE = 0.05;
    /** A box of air this many blocks across, floor under it, in the sky above the spawn. */
    private static final int ROOM = 30;

    private record Scene(int lit, int dark, BlockPos floor) {}

    @Override
    public void runTest(ClientGameTestContext context) {
        ClientTestLog.start(this);
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            Scene scene = singleplayer.getServer().computeOnServer(BeamsClientTest::setUp);
            ClientWait.until(
                    context,
                    "both pods and the room's floor on the client",
                    client -> client.level != null
                            && podIfPresent(client, scene.lit()) != null
                            && podIfPresent(client, scene.dark()) != null
                            && client.level.getBlockState(scene.floor()).is(Blocks.STONE));
            context.runOnClient(client -> {
                PodEntity lit = pod(client, scene.lit());
                PodEntity dark = pod(client, scene.dark());
                double reach = BeamLook.current().headlamp().length() * 9 / 15;
                require(PodBeams.reach(lit) > 0, "a pod with a lights part should have a beam reach");
                require(PodBeams.reach(dark) == 0, "a pod with no lights part must have no beam reach");
                require(
                        Math.abs(PodBeams.reach(lit) - reach) < 1e-9,
                        "a tier 2 lights part (light level 9) reaches 9/15 of the headlamp length "
                                + reach
                                + ", got "
                                + PodBeams.reach(lit));

                BeamDraw open = Objects.requireNonNull(
                        PodBeams.beamFor(lit, stateOf(lit)), "a lit pod in open air should draw a beam");
                // Its headlamp tilts down, so over a bare floor the beam ends where it meets the floor.
                double height = BeamLook.current().headlamp().height();
                double floorHit = height
                        / Math.sin(Math.toRadians(BeamLook.current().headlamp().pitch()));
                require(floorHit < reach, "the room is set so that the floor cuts the beam short of its reach");
                require(
                        Math.abs(open.length() - floorHit) < TOLERANCE,
                        "the beam over a bare floor should be drawn " + floorHit + " blocks long, drawn "
                                + open.length());
                require(PodBeams.beamFor(dark, stateOf(dark)) == null, "a dark pod must draw no beam");
                require(PodBeams.cullingBox(dark, 0f) == null, "a dark pod needs no beam in its culling box");

                // The beam's culling box holds the far end of the beam at every heading, and nothing lies behind the
                // pod.
                for (int heading = 0; heading < 360; heading += 15) {
                    AABB box = Objects.requireNonNull(PodBeams.cullingBox(lit, heading), "a lit pod has a beam box")
                            .inflate(TOLERANCE);
                    double pitch = BeamLook.current().headlamp().pitch();
                    Vec3 end = lamp(lit, heading)
                            .add(
                                    BeamAim.dirX(heading, pitch) * reach,
                                    BeamAim.dirY(pitch) * reach,
                                    BeamAim.dirZ(heading, pitch) * reach);
                    require(
                            box.contains(end),
                            "the beam box at heading " + heading + " should hold the beam's end " + end + ", got "
                                    + box);
                    Vec3 behind =
                            lit.position().add(-BeamAim.dirX(heading, 0) * reach, 0, -BeamAim.dirZ(heading, 0) * reach);
                    require(
                            !box.contains(behind),
                            "the beam box at heading " + heading + " should not reach " + behind
                                    + " behind the pod, got " + box);
                }

                // The cast the renderer makes (BeamAim, one walk of the client's blocks), here level down the room from
                // the lamp, so
                // the floor does not cut it.
                double length = BeamAim.cast(
                        ClientLight.clientLevel(client),
                        lit.getX(),
                        lit.getY() + BeamLook.current().headlamp().height(),
                        lit.getZ() + BeamLook.current().headlamp().forward(),
                        0,
                        0,
                        1,
                        reach);
                require(
                        Math.abs(length - reach) < TOLERANCE,
                        "open air should give the full reach " + reach + ", cast " + length);
            });
            // A wall four blocks in front of the lit pod's lamp cuts the cast to the wall's face.
            singleplayer.getServer().runOnServer(server -> {
                PodEntity lit = TestEntities.require(server.overworld(), scene.lit(), PodEntity.class);
                for (int dy = 0; dy < 6; dy++) {
                    for (int dx = -3; dx <= 3; dx++) {
                        server.overworld()
                                .setBlock(
                                        BlockPos.containing(lit.getX() + dx, lit.getY() + dy, lit.getZ() + 5),
                                        Blocks.STONE.defaultBlockState(),
                                        3);
                    }
                }
            });
            ClientWait.until(
                    context,
                    "the wall on the client",
                    client -> ClientLight.clientLevel(client)
                            .getBlockState(BlockPos.containing(
                                    pod(client, scene.lit()).getX(),
                                    pod(client, scene.lit()).getY() + 1,
                                    pod(client, scene.lit()).getZ() + 5))
                            .is(Blocks.STONE));
            context.runOnClient(client -> {
                PodEntity lit = pod(client, scene.lit());
                double forward = BeamLook.current().headlamp().forward();
                double wallFace = Math.floor(lit.getZ() + 5);
                double expected = wallFace - (lit.getZ() + forward);
                double length = BeamAim.cast(
                        ClientLight.clientLevel(client),
                        lit.getX(),
                        lit.getY() + BeamLook.current().headlamp().height(),
                        lit.getZ() + forward,
                        0,
                        0,
                        1,
                        PodBeams.reach(lit));
                require(
                        Math.abs(length - expected) < TOLERANCE,
                        "a wall at z + 5 should cut the level cast to " + expected + " blocks, cast " + length);
                // The beam that is drawn tilts down by the style's pitch, so it meets the wall's face at the distance
                // along its slant.
                double pitch = BeamLook.current().headlamp().pitch();
                double slant = (wallFace - (lit.getZ() + forward)) / Math.cos(Math.toRadians(pitch));
                BeamDraw cut = Objects.requireNonNull(
                        PodBeams.beamFor(lit, stateOf(lit)), "a beam cut short by a wall is still drawn, shorter");
                require(
                        Math.abs(cut.length() - slant) < TOLERANCE,
                        "the drawn beam should end at the wall, " + slant + " blocks, drawn " + cut.length());
            });
            // Stranded, the pod loses its light block (ADR 0024) and its beam.
            singleplayer
                    .getServer()
                    .runOnServer(server -> TestEntities.require(server.overworld(), scene.lit(), PodEntity.class)
                            .setStranded(true));
            ClientWait.until(
                    context,
                    "the stranded pod to be dark on the client",
                    client -> pod(client, scene.lit()).stranded());
            context.runOnClient(client -> {
                PodEntity lit = pod(client, scene.lit());
                require(PodBeams.reach(lit) == 0, "a stranded pod has no beam reach");
                require(PodBeams.beamFor(lit, stateOf(lit)) == null, "a stranded pod must draw no beam");
            });
        }
    }

    private static Vec3 lamp(PodEntity pod, float heading) {
        var style = BeamLook.current().headlamp();
        return new Vec3(
                pod.getX() - Math.sin(Math.toRadians(heading)) * style.forward(),
                pod.getY() + style.height(),
                pod.getZ() + Math.cos(Math.toRadians(heading)) * style.forward());
    }

    private static PodGeoRenderState stateOf(PodEntity pod) {
        PodGeoRenderState state = new PodGeoRenderState();
        state.x = pod.getX();
        state.y = pod.getY();
        state.z = pod.getZ();
        // Facing +z, the way a pod set down with no yaw does.
        state.heading = 0f;
        state.distanceToCameraSq = 0;
        return state;
    }

    private static Scene setUp(net.minecraft.server.MinecraftServer server) {
        ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
        ServerLevel level = player.level();
        BlockPos floor = player.blockPosition().offset(0, 80, 0);
        for (int dx = -ROOM; dx <= ROOM; dx++) {
            for (int dz = -ROOM; dz <= ROOM; dz++) {
                level.setBlock(floor.offset(dx, 0, dz), Blocks.STONE.defaultBlockState(), 3);
                for (int dy = 1; dy <= 12; dy++) {
                    level.setBlock(floor.offset(dx, dy, dz), Blocks.AIR.defaultBlockState(), 3);
                }
            }
        }
        player.teleportTo(level, floor.getX() + 0.5, floor.getY() + 1, floor.getZ() - 12.5, Set.of(), 0f, 0f, true);
        require(Charters.found(server, player.getUUID(), "Beam Test Charter").isEmpty(), "founding should succeed");
        CharterId charter = Charters.charterOfOrThrow(server, player.getUUID())
                .orElseThrow()
                .id();
        PodEntity lit = spawn(level, floor.getX() + 0.5, floor.getY() + 1, floor.getZ() - 8.5);
        PodEntity dark = spawn(level, floor.getX() + 8.5, floor.getY() + 1, floor.getZ() - 8.5);
        PodComponents.register(lit, charter);
        PodComponents.register(dark, charter);
        PodComponents.install(lit, ComponentItems.mint(server, ComponentTrack.LIGHTS, 2, charter));
        return new Scene(lit.getId(), dark.getId(), floor);
    }

    private static PodEntity spawn(ServerLevel level, double x, double y, double z) {
        PodEntity pod = TestEntities.create(PodRegistry.POD, level);
        pod.setPos(x, y, z);
        level.addFreshEntity(pod);
        return pod;
    }

    private static PodEntity pod(Minecraft client, int id) {
        return TestEntities.require(ClientLight.clientLevel(client), id, PodEntity.class);
    }

    private static @Nullable PodEntity podIfPresent(Minecraft client, int id) {
        return ClientLight.clientLevel(client).getEntity(id) instanceof PodEntity pod ? pod : null;
    }
}
