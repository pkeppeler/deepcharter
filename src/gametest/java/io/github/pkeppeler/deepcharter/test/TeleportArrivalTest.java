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
import net.minecraft.server.level.ChunkLevel;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.TicketStorage;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.phys.Vec3;

/**
 * A teleporter's arrival (#1353): a pod sent with its pilot to a cold chunk stays loaded and tracked for the pilot,
 * as a seam swap's or a tow's arrival does.
 */
public class TeleportArrivalTest {
    /** How long the arrival is watched, in ticks. */
    private static final int WATCH_TICKS = 200;

    /**
     * Ticks from the teleport to the client's answer to it, which is what moves the pilot's own chunk tickets to the
     * arrival: a 250 ms round trip, or a server running behind. Without an arrival ticket, any answer later than the
     * one-tick ticket of the landing look-up let the chunk drop with no ticket at all (#1353: answers 2, 3, 5 and 20
     * ticks late all did, 1 tick late did not).
     */
    private static final int ANSWER_DELAY = 5;

    @GameTest(maxTicks = WATCH_TICKS + 100)
    public void theArrivalChunkHoldsUntilTheLatePilotsTicketsArrive(GameTestHelper helper) {
        ServerLevel overworld = helper.getLevel().getServer().overworld();
        int x = 14_000;
        ChunkPos arrival = ChunkPos.containing(TestLayers.pos(TestLayers.SURFACE, x, 64, 0));
        expect(
                helper,
                !overworld.hasChunk(arrival.x(), arrival.z()),
                "chunk %s should be cold before the test",
                arrival);
        MockPlayer pilot = MockPlayers.join(helper, "Arriver");
        // The join teleports the mock into the structure: answer it, so the server sends it the chunks around it there.
        pilot.acknowledgeTeleports();
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
        long[] sent = {-1};
        String[] last = {""};
        boolean[] everLoaded = {false};
        List<String> lapses = new ArrayList<>();
        TestTicks.each(helper, () -> {
            ServerPlayer player = pilot.player();
            if (sent[0] < 0) {
                // The pilot's client holds its pod before it teleports, as a pilot's has long done.
                if (clientHolds(pilot, podId)) {
                    send(helper, overworld, pilot, x);
                    expect(
                            helper,
                            ChunkPos.containing(pod.blockPosition()).equals(arrival),
                            "the pod should be in %s, is at %s",
                            arrival,
                            pod.position());
                    sent[0] = helper.getTick();
                }
                return;
            }
            long tick = helper.getTick() - sent[0];
            // A real client answers the teleport once, then reports its look every tick: each moves its chunk tickets.
            // A
            // second answer to the same teleport would disconnect it.
            if (tick == ANSWER_DELAY) {
                pilot.acknowledgeTeleports();
            }
            if (tick > ANSWER_DELAY && player.isPassenger()) {
                player.connection.handleMovePlayer(
                        new ServerboundMovePlayerPacket.Rot(player.getYRot(), player.getXRot(), false, false));
            }
            long key = arrival.pack();
            int level = overworld.getChunkSource().chunkMap.getDistanceManager().getChunkLevel(key, false);
            boolean loaded = overworld.areEntitiesLoaded(key);
            String state = String.format(
                    "level=%d tickets=%s entitiesLoaded=%s entityTicking=%s clientHoldsPod=%s podRemoved=%s",
                    level,
                    overworld
                            .getDataStorage()
                            .computeIfAbsent(TicketStorage.TYPE)
                            .getTickets(key),
                    loaded,
                    overworld.isPositionEntityTicking(pod.blockPosition()),
                    clientHolds(pilot, podId),
                    pod.isRemoved());
            if (!state.equals(last[0])) {
                DeepCharter.LOGGER.info("[teleport-arrival] tick={} {}", tick, state);
                last[0] = state;
            }
            everLoaded[0] |= loaded;
            if (level > ChunkLevel.byStatus(FullChunkStatus.FULL) || (everLoaded[0] && !loaded)) {
                lapses.add(String.format("tick %d: level %d, entities loaded %s", tick, level, loaded));
            }
            if (tick >= WATCH_TICKS) {
                expect(helper, lapses.isEmpty(), "the arrival chunk lapsed: %s", lapses);
                expect(helper, !pod.isRemoved(), "the pod was removed: %s", pod.getRemovalReason());
                expect(helper, clientHolds(pilot, podId), "the pilot's client should hold its pod at the end");
                expect(helper, player.getVehicle() == pod, "the pilot should still ride the pod");
                helper.succeed();
            }
        });
    }

    /** The pilot uses a Matter Transmitter with the colony unbuilt and the world spawn at column {@code x}, 0. */
    private static void send(GameTestHelper helper, ServerLevel overworld, MockPlayer pilot, int x) {
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
    }

    /** Whether the pilot's client holds the pod, from the add and remove packets it was sent. */
    private static boolean clientHolds(MockPlayer pilot, int podId) {
        boolean holds = false;
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
