package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.Failures.expect;
import static io.github.pkeppeler.deepcharter.test.support.Failures.failure;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.charter.Charter;
import io.github.pkeppeler.deepcharter.charter.Charters;
import io.github.pkeppeler.deepcharter.colony.ColonySite;
import io.github.pkeppeler.deepcharter.pod.PodComponents;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.repair.Consumable;
import io.github.pkeppeler.deepcharter.repair.RepairRegistry;
import io.github.pkeppeler.deepcharter.test.support.MockPlayer;
import io.github.pkeppeler.deepcharter.test.support.MockPlayers;
import io.github.pkeppeler.deepcharter.test.support.TestLayers;
import io.github.pkeppeler.deepcharter.test.support.TestTicks;
import io.github.pkeppeler.deepcharter.test.support.WorldData;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.phys.Vec3;

/**
 * A teleporter's arrival (#1353): a pod sent with its pilot to a cold chunk stays loaded and tracked for the pilot, as a
 * seam swap's or a tow's arrival does.
 */
public class TeleportArrivalTest {
    /** How long the arrival is watched, in ticks. */
    private static final int WATCH_TICKS = 200;

    @GameTest(maxTicks = WATCH_TICKS + 100)
    public void aTeleportedPodStaysLoadedAndInThePilotsSight(GameTestHelper helper) {
        ServerLevel overworld = helper.getLevel().getServer().overworld();
        int x = 14_000;
        ChunkPos arrival = ChunkPos.containing(TestLayers.pos(TestLayers.SURFACE, x, 64, 0));
        expect(
                helper,
                !overworld.hasChunk(arrival.x(), arrival.z()),
                "chunk %s should be cold before the test",
                arrival);
        MockPlayer pilot = MockPlayers.join(helper, "Arriver");
        Charters.found(
                        overworld.getServer(),
                        pilot.player().getUUID(),
                        "Arrive " + UUID.randomUUID().toString().substring(0, 8))
                .ifPresent(refusal -> {
                    throw failure(helper, "founding the charter: %s", refusal);
                });
        Charter charter = Charters.charterOfOrThrow(
                        overworld.getServer(), pilot.player().getUUID())
                .orElseThrow();
        PodEntity pod = helper.spawn(PodRegistry.POD, new Vec3(1.5, 1, 1.5));
        PodComponents.register(pod, charter.id());
        expect(helper, pilot.player().startRiding(pod, true, false), "the pilot could not board the pod");
        int podId = pod.getId();
        WorldData.with(overworld.getServer(), ColonySite.TYPE, new ColonySite(), () -> {
            LevelData.RespawnData original = overworld.getRespawnData();
            try {
                overworld.setRespawnData(LevelData.RespawnData.of(
                        Level.OVERWORLD, TestLayers.pos(TestLayers.SURFACE, x, 64, 0), 0f, 0f));
                ServerPlayer player = pilot.player();
                player.getInventory()
                        .setItem(
                                player.getInventory().getSelectedSlot(),
                                new ItemStack(RepairRegistry.item(Consumable.MATTER_TRANSMITTER)));
                expect(
                        helper,
                        player.gameMode
                                .useItem(
                                        player,
                                        player.level(),
                                        player.getItemInHand(InteractionHand.MAIN_HAND),
                                        InteractionHand.MAIN_HAND)
                                .consumesAction(),
                        "the transmitter should work");
            } finally {
                overworld.setRespawnData(original);
            }
        });
        expect(
                helper,
                ChunkPos.containing(pod.blockPosition()).equals(arrival),
                "the pod should be in %s, is at %s",
                arrival,
                pod.position());
        long start = helper.getTick();
        List<String> lapses = new ArrayList<>();
        TestTicks.each(helper, () -> {
            long tick = helper.getTick() - start;
            ServerPlayer player = pilot.player();
            // A real client answers the teleport and then reports its look every tick, which moves its chunk tickets.
            pilot.acknowledgeTeleports();
            if (player.isPassenger()) {
                player.connection.handleMovePlayer(
                        new ServerboundMovePlayerPacket.Rot(player.getYRot(), player.getXRot(), false, false));
            }
            long key = arrival.pack();
            int level = overworld.getChunkSource().chunkMap.getDistanceManager().getChunkLevel(key, false);
            boolean loaded = overworld.areEntitiesLoaded(key);
            boolean ticking = overworld.isPositionEntityTicking(pod.blockPosition());
            boolean seen = clientHolds(pilot, podId);
            DeepCharter.LOGGER.info(
                    "[teleport-arrival] tick={} level={} entitiesLoaded={} entityTicking={} clientHoldsPod={} vehicle={}",
                    tick,
                    level,
                    loaded,
                    ticking,
                    seen,
                    player.getVehicle() == null ? "none" : player.getVehicle().getId());
            if (!loaded) {
                lapses.add("tick " + tick + ": entities unloaded at level " + level);
            }
            if (tick >= WATCH_TICKS) {
                expect(helper, lapses.isEmpty(), "the arrival chunk lapsed: %s", lapses);
                expect(helper, seen, "the pilot's client should hold its pod at the end");
                expect(helper, player.getVehicle() == pod, "the pilot should still ride the pod");
                helper.succeed();
            }
        });
    }

    /** Whether the pilot's client holds the pod, from the add and remove packets it was sent. */
    private static boolean clientHolds(MockPlayer pilot, int podId) {
        boolean holds = true;
        for (Object packet : pilot.sequence()) {
            if (packet instanceof ClientboundAddEntityPacket add && add.getId() == podId) {
                holds = true;
            } else if (packet instanceof ClientboundRemoveEntitiesPacket remove
                    && remove.entityIds().contains(podId)) {
                holds = false;
            }
        }
        return holds;
    }
}
