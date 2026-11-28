package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.Inputs.FORWARD;

import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.pod.PodStats;
import io.github.pkeppeler.deepcharter.test.support.FarChunks;
import io.github.pkeppeler.deepcharter.test.support.MockPlayer;
import io.github.pkeppeler.deepcharter.test.support.MockPlayers;
import io.github.pkeppeler.deepcharter.test.support.TestLayers;
import io.github.pkeppeler.deepcharter.test.support.TestTicks;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.PositionAndRotation;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ServerboundMoveVehiclePacket;
import net.minecraft.server.level.ServerEntity;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.UpdateInterval;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * Server GameTests for what the server tells a pod's pilot while the pod drives (#961): one steady position update every tick and no
 * correction, so the server is not what makes a pod stutter, and a pilot's own report of where the pod is does not move it, so
 * the pilot's client has no authority to exploit.
 */
public class PodSyncTest {
    private static final int FLOOR_Y = TestLayers.worldY(TestLayers.SURFACE, 1);
    private static final int FLOOR_RADIUS = 12;
    private static final int DRIVE_TICKS = 40;
    /** Ticks the pod gets to reach cruising speed before the steady stretch is judged. */
    private static final int SETTLE_TICKS = 6;
    /** Ticks a vehicle has ticked before a pilot's claim is sent, so the connection has seen the pilot ride it. */
    private static final int CLAIM_AFTER_TICKS = 5;

    private static final double EPSILON = 1e-6;
    /**
     * How far a hacked client claims the vehicle went in one tick: five blocks, where the pod's own top speed is a
     * fifth of one.
     */
    private static final double CLAIMED_STEP = 5;

    /** Lays the floor and awaits entity ticking in every chunk under it; the result is true once they all tick (#1083). */
    private static boolean[] floor(GameTestHelper helper) {
        BlockPos low = helper.absolutePos(BlockPos.ZERO);
        BlockPos high = helper.absolutePos(new BlockPos(2 * FLOOR_RADIUS, 0, 2 * FLOOR_RADIUS));
        List<BlockPos> chunks = new ArrayList<>();
        for (int chunkX = low.getX() >> 4; chunkX <= high.getX() >> 4; chunkX++) {
            for (int chunkZ = low.getZ() >> 4; chunkZ <= high.getZ() >> 4; chunkZ++) {
                chunks.add(new BlockPos(chunkX << 4, FLOOR_Y, chunkZ << 4));
            }
        }
        boolean[] ticking = {false};
        FarChunks.awaitAllEntityTicking(helper, helper.getLevel(), chunks, () -> ticking[0] = true);
        for (int x = -FLOOR_RADIUS; x <= FLOOR_RADIUS; x++) {
            for (int z = -FLOOR_RADIUS; z <= FLOOR_RADIUS; z++) {
                helper.setBlock(
                        new BlockPos(x + FLOOR_RADIUS, FLOOR_Y, z + FLOOR_RADIUS), Blocks.STONE.defaultBlockState());
            }
        }
        return ticking;
    }

    private static Vec3 spawnAt() {
        return new Vec3(FLOOR_RADIUS + 0.5, FLOOR_Y + 1, FLOOR_RADIUS + 0.5);
    }

    private static MockPlayer pilotOf(GameTestHelper helper, Entity vehicle) {
        MockPlayer pilot = MockPlayers.join(helper, "sync-pilot");
        pilot.teleportTo(helper.getLevel(), vehicle.position(), 0f, 0f);
        if (!pilot.player().startRiding(vehicle)) {
            throw helper.assertionException("the pilot could not mount %s", vehicle);
        }
        return pilot;
    }

    /**
     * The tracker's own {@link ServerEntity} for the pod (built as {@code ChunkMap} builds it, from the entity type's update interval) sends
     * exactly one relative move on every tick of a drive and no other movement: no teleport, position sync, motion push or vehicle correction. The pod
     * covers the same ground every tick of the steady stretch. A pod that stuttered on the wire would show a tick with none or two, or a step
     * that differs. A second {@code ServerEntity} is read here rather than the pilot's own packets, because a mock pilot's tracking starts some
     * ticks into a far-away test and would leave a gap that is the harness's.
     */
    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + 400)
    public void driveSendsOneSteadyMoveEveryTick(GameTestHelper helper) {
        boolean[] floorTicks = floor(helper);
        PodEntity pod = helper.spawn(PodRegistry.POD, spawnAt());
        MockPlayer pilot = pilotOf(helper, pod);
        List<Packet<?>> sent = new ArrayList<>();
        ServerEntity tracker = new ServerEntity(
                helper.getLevel(),
                pod,
                UpdateInterval.periodic(pod.getType().updateInterval()),
                pod.getType().trackDeltas(),
                new ServerEntity.Synchronizer() {
                    @Override
                    public void sendToTrackingPlayers(Packet<? super ClientGamePacketListener> packet) {
                        sent.add(packet);
                    }

                    @Override
                    public void sendToTrackingPlayersAndSelf(Packet<? super ClientGamePacketListener> packet) {
                        sent.add(packet);
                    }

                    @Override
                    public void sendToTrackingPlayersFiltered(
                            Packet<? super ClientGamePacketListener> packet, Predicate<ServerPlayer> filter) {
                        sent.add(packet);
                    }
                });
        List<Vec3> positions = new ArrayList<>();
        List<List<String>> perTick = new ArrayList<>();
        boolean[] driving = {false};
        TestTicks.each(helper, () -> {
            // The structure is far from the world's players: the pod starts ticking some ticks into the test.
            if (!driving[0]) {
                if (!floorTicks[0] || pod.tickCount < 1) {
                    return;
                }
                driving[0] = true;
                pilot.setInput(FORWARD);
                tracker.sendChanges();
                sent.clear();
                return;
            }
            tracker.sendChanges();
            // The pod's synced data (the fuel it burns) changes every tick and rides along; it moves nothing.
            perTick.add(sent.stream()
                    .filter(packet -> !(packet instanceof ClientboundSetEntityDataPacket))
                    .map(packet -> packet.getClass().getSimpleName())
                    .toList());
            sent.clear();
            positions.add(pod.position());
            if (positions.size() < DRIVE_TICKS) {
                return;
            }
            pilot.releaseInput();
            double speed = PodStats.of(pod).horizontalSpeed();
            for (int i = SETTLE_TICKS; i < perTick.size() - 1; i++) {
                List<String> kinds = perTick.get(i);
                if (kinds.size() != 1 || !kinds.getFirst().startsWith("Pos")) {
                    throw helper.assertionException(
                            "drive tick %s: the server should send one relative move for the pod, sent %s (all: %s)",
                            i, kinds, perTick);
                }
                double step = positions.get(i + 1).subtract(positions.get(i)).horizontalDistance();
                if (Math.abs(step - speed) > EPSILON) {
                    throw helper.assertionException(
                            "drive tick %s: the pod should cover %s blocks a tick at cruise, covered %s (positions %s)",
                            i, speed, step, positions);
                }
            }
            pilot.leave();
            helper.succeed();
        });
    }

    /**
     * The control for {@link #pilotsClaimedMoveDoesNotMoveThePod}: the same claim from the pilot of a vanilla boat does move the boat, so
     * the claim reaches the handler and a pod that stays put is the pod's doing, not the harness's.
     */
    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + 400)
    public void controlBoatTakesTheClaimedMove(GameTestHelper helper) {
        boolean[] floorTicks = floor(helper);
        Entity boat = helper.spawn(EntityTypes.OAK_BOAT, spawnAt());
        claimAfterRiding(helper, boat, floorTicks, moved -> {
            if (moved.x < CLAIMED_STEP - 1) {
                throw helper.assertionException(
                        "the control: a vanilla boat should take the claimed %s blocks, moved %s", CLAIMED_STEP, moved);
            }
        });
    }

    /**
     * A pilot's own report of where the pod is ({@link ServerboundMoveVehiclePacket}, which vanilla's client sends for a vehicle it
     * simulates) must not move a pod: the pod is simulated by the server from the pilot's input, so a modified client that claims a
     * faster move than the pod's top speed would otherwise be a speed exploit.
     */
    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + 400)
    public void pilotsClaimedMoveDoesNotMoveThePod(GameTestHelper helper) {
        boolean[] floorTicks = floor(helper);
        PodEntity pod = helper.spawn(PodRegistry.POD, spawnAt());
        claimAfterRiding(helper, pod, floorTicks, moved -> {
            if (moved.lengthSqr() > EPSILON) {
                throw helper.assertionException(
                        "the pilot claimed the vehicle moved %s blocks and the server took it: it moved %s",
                        CLAIMED_STEP, moved);
            }
        });
    }

    /**
     * Seats a mock pilot in {@code vehicle}, waits until it has ticked, sends the packet a vanilla client sends for a vehicle it
     * simulates with the vehicle {@link #CLAIMED_STEP} blocks east, and hands {@code check} how far the vehicle moved on the spot.
     */
    private static void claimAfterRiding(
            GameTestHelper helper, Entity vehicle, boolean[] floorTicks, Consumer<Vec3> check) {
        MockPlayer pilot = pilotOf(helper, vehicle);
        boolean[] claimed = {false};
        TestTicks.each(helper, () -> {
            if (claimed[0] || !floorTicks[0] || vehicle.tickCount < CLAIM_AFTER_TICKS) {
                return;
            }
            claimed[0] = true;
            pilot.acknowledgeTeleports();
            Vec3 before = vehicle.position();
            pilot.player()
                    .connection
                    .handleMoveVehicle(new ServerboundMoveVehiclePacket(
                            PositionAndRotation.of(before.add(CLAIMED_STEP, 0, 0), vehicle.getYRot(), 0f), true));
            check.accept(vehicle.position().subtract(before));
            pilot.leave();
            helper.succeed();
        });
    }
}
