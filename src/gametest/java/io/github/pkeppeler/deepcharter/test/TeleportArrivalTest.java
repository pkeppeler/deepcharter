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
import io.github.pkeppeler.deepcharter.repair.RepairTuning;
import io.github.pkeppeler.deepcharter.test.mixin.ServerChunkCacheAccess;
import io.github.pkeppeler.deepcharter.test.mixin.TicketAccess;
import io.github.pkeppeler.deepcharter.test.support.CampaignEnvironments;
import io.github.pkeppeler.deepcharter.test.support.FarChunks;
import io.github.pkeppeler.deepcharter.test.support.MockPlayer;
import io.github.pkeppeler.deepcharter.test.support.MockPlayers;
import io.github.pkeppeler.deepcharter.test.support.TestLayers;
import io.github.pkeppeler.deepcharter.test.support.TestTicks;
import io.github.pkeppeler.deepcharter.test.support.WorldData;
import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ChunkLevel;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.Ticket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.phys.Vec3;

/**
 * A teleporter's arrival (#1353): a pod sent with its pilot to a cold chunk stays loaded and tracked for the pilot,
 * as a seam swap's or a tow's arrival does.
 */
public class TeleportArrivalTest {
    // The countdown counts down only while its chunk is ready to save (vanilla's timed tickets), and that readiness
    // comes and goes with the machine's load: a run saw 10 to 92 of 100 ticks left 124 ticks after the first tick of
    // countdown. So neither its start nor its end is a tick count; each is bounded by a FarChunks.Deadline.
    /** Ticks the chunk is watched after the arrival ticket is released, for a lapse at the handover. */
    private static final int AFTER_RELEASE_TICKS = 40;

    /** The arrival ticket's type, by its registry name. */
    private static final Identifier ARRIVAL_TICKET =
            Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "teleport_arrival");

    /**
     * Ticks from the teleport to the client's answer, which moves the pilot's own chunk tickets to the arrival: a
     * 250 ms round trip. Without the arrival ticket, any answer more than 1 tick late let the chunk drop (#1353).
     */
    private static final int ANSWER_DELAY = 5;

    // The landing look-up loads the cold chunk synchronously on the server thread, inside a same-tick WorldData.with.
    // The waits for the countdown to start and to end, then the boarding and watch, with room.
    @GameTest(environment = CampaignEnvironments.SYNC_CHUNK_LOAD, maxTicks = 2 * FarChunks.AWAIT_BUDGET_TICKS + 600)
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
        int countdownTicks = RepairTuning.DEFAULT.arrivalTicketTicks();
        FarChunks.Deadline countdownWait = FarChunks.deadline();
        FarChunks.Deadline releaseWait = FarChunks.deadline();
        long[] sent = {-1};
        boolean[] everLoaded = {false};
        boolean[] everHeld = {false};
        long[] countdown = {-1};
        long[] released = {-1};
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
            // A second answer to the same teleport would disconnect it.
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
            List<Ticket> tickets = ((ServerChunkCacheAccess) overworld.getChunkSource())
                    .deepcharter$ticketStorage()
                    .getTickets(key);
            List<Ticket> arrivalTickets = tickets.stream()
                    .filter(ticket -> ARRIVAL_TICKET.equals(BuiltInRegistries.TICKET_TYPE.getKey(ticket.getType())))
                    .toList();
            boolean held = !arrivalTickets.isEmpty();
            everHeld[0] |= held;
            if (countdown[0] < 0
                    && arrivalTickets.stream()
                            .anyMatch(ticket -> ((TicketAccess) ticket).deepcharter$ticksLeft() < countdownTicks)) {
                countdown[0] = tick;
            }
            if (everHeld[0] && !held && released[0] < 0) {
                released[0] = tick;
            }
            everLoaded[0] |= loaded;
            expect(
                    helper,
                    level <= ChunkLevel.byStatus(FullChunkStatus.FULL) && (loaded || !everLoaded[0]),
                    "the arrival chunk lapsed at tick %d: level %d, entities loaded %s (arrival ticket held: %s)",
                    tick,
                    level,
                    loaded,
                    held);
            expect(
                    helper,
                    released[0] < 0 || countdown[0] >= 0,
                    "the arrival ticket was released at tick %d before its countdown started",
                    released[0]);
            boolean counting = countdown[0] >= 0;
            countdownWait.await(
                    helper,
                    overworld,
                    counting,
                    () -> String.format(
                            "the arrival ticket's countdown never started (ever held: %s, tickets now: %s)",
                            everHeld[0], tickets));
            if (!counting) {
                return;
            }
            boolean gone = released[0] >= 0;
            releaseWait.await(
                    helper,
                    overworld,
                    gone,
                    () -> String.format(
                            "the arrival ticket was not released after its countdown started at tick %d"
                                    + " (tickets now: %s)",
                            countdown[0], tickets));
            if (!gone) {
                return;
            }
            if (tick >= released[0] + AFTER_RELEASE_TICKS) {
                DeepCharter.LOGGER.info(
                        "[teleport-arrival] countdown started at tick={}, ticket released at tick={}",
                        countdown[0],
                        released[0]);
                expect(helper, !pod.isRemoved(), "the pod was removed: %s", pod.getRemovalReason());
                // Only at the end: the client still drops the pod for a tick at tick 1 and after the answer (#1364).
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
