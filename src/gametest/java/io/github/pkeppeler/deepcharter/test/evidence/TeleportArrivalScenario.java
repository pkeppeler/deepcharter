package io.github.pkeppeler.deepcharter.test.evidence;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.charter.Charter;
import io.github.pkeppeler.deepcharter.charter.Charters;
import io.github.pkeppeler.deepcharter.client.transmission.TransmissionOverlay;
import io.github.pkeppeler.deepcharter.colony.ColonySite;
import io.github.pkeppeler.deepcharter.pod.PodComponents;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.repair.Consumable;
import io.github.pkeppeler.deepcharter.repair.RepairRegistry;
import io.github.pkeppeler.deepcharter.test.support.ClientSession;
import io.github.pkeppeler.deepcharter.test.support.TestEntities;
import io.github.pkeppeler.deepcharter.test.support.WorldData;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientEntityEvents;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.CameraType;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelData;

/**
 * Evidence scenario "teleport-arrival" for #1364: a pilot uses a Matter Transmitter and lands in a chunk no one has
 * loaded, far from the spawn. One frame a tick across the arrival, and a log line of, for each tick, whether the
 * pilot's client holds its pod, rides it, and dropped it since the tick before. Before #1364 the client dropped the pod
 * at the arrival and was sent it again a tick later.
 */
public class TeleportArrivalScenario extends EvidenceScenario {
    /** How far east of the pilot the transmitter lands: far enough that no one has loaded the chunk. */
    private static final int AWAY_BLOCKS = 12_000;

    private static final int SETTLE_TICKS = 40;
    private static final int LEAD_FRAMES = 10;
    private static final int ARRIVAL_FRAMES = 40;

    /** The pod's entity id, once the scenario has made it: the client counts its unloads in {@link #UNLOADS}. */
    private static volatile int watched = -1;

    /** How many times the client has removed the watched pod from its level. */
    private static final AtomicInteger UNLOADS = new AtomicInteger();

    @Override
    protected String name() {
        return "teleport-arrival";
    }

    @Override
    protected void run(ClientGameTestContext context) {
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            awaitWorld(context);
            // A remove and the add after it can both land between two readings: count the removes as they happen.
            ClientEntityEvents.ENTITY_UNLOAD.register((entity, level) -> {
                if (entity.getId() == watched) {
                    UNLOADS.incrementAndGet();
                }
            });
            int podId = singleplayer.getServer().computeOnServer(server -> {
                ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
                if (Charters.found(server, player.getUUID(), "Arrival Evidence Charter")
                        .isPresent()) {
                    throw new AssertionError("founding the charter failed");
                }
                Charter charter =
                        Charters.charterOfOrThrow(server, player.getUUID()).orElseThrow();
                PodEntity pod = TestEntities.create(PodRegistry.POD, server.overworld());
                pod.setPos(player.getX(), player.getY(), player.getZ() + 3);
                server.overworld().addFreshEntity(pod);
                PodComponents.register(pod, charter.id());
                player.startRiding(pod, true, false);
                player.getInventory()
                        .setItem(
                                player.getInventory().getSelectedSlot(),
                                new ItemStack(RepairRegistry.item(Consumable.MATTER_TRANSMITTER)));
                return pod.getId();
            });
            watched = podId;
            // From behind, so the pod itself is in every frame.
            context.runOnClient(client -> client.options.setCameraType(CameraType.THIRD_PERSON_BACK));
            context.waitTicks(SETTLE_TICKS); // tick-wait: lets the pod settle and the chase camera swing in
            // The charter's welcome would cover the pod: this scenario is about the pod.
            context.runOnClient(client -> TransmissionOverlay.reset());
            tickFrames(context, LEAD_FRAMES);
            singleplayer.getServer().runOnServer(server -> {
                ServerLevel overworld = server.overworld();
                ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
                BlockPos landing = player.blockPosition().east(AWAY_BLOCKS);
                // With no colony built, the transmitter takes the pod to the world spawn: move that far out.
                WorldData.with(server, ColonySite.TYPE, new ColonySite(), () -> {
                    LevelData.RespawnData original = overworld.getRespawnData();
                    try {
                        overworld.setRespawnData(LevelData.RespawnData.of(Level.OVERWORLD, landing, 0f, 0f));
                        player.gameMode.useItem(
                                player,
                                overworld,
                                player.getItemInHand(InteractionHand.MAIN_HAND),
                                InteractionHand.MAIN_HAND);
                    } finally {
                        overworld.setRespawnData(original);
                    }
                });
            });
            List<String> ticks = new ArrayList<>();
            int dropped = 0;
            int unloadsSeen = 0;
            for (int tick = 1; tick <= ARRIVAL_FRAMES; tick++) {
                context.waitTicks(1); // tick-wait: one frame and one reading a tick, to catch a one-tick drop
                frame(context);
                boolean[] state = context.computeOnClient(client -> {
                    Entity pod = client.level == null ? null : client.level.getEntity(podId);
                    return new boolean[] {
                        pod != null, pod != null && ClientSession.player(client).getVehicle() == pod
                    };
                });
                int unloads = UNLOADS.get();
                boolean unloaded = unloads > unloadsSeen;
                unloadsSeen = unloads;
                if (!state[0] || !state[1] || unloaded) {
                    dropped++;
                }
                ticks.add(String.format(
                        "%d:%s%s%s", tick, state[0] ? "H" : "-", state[1] ? "R" : "-", unloaded ? "!" : ""));
            }
            // H: the client holds the pod; R: the pilot rides it on the client; !: the client removed it since.
            DeepCharter.LOGGER.info(
                    "[teleport-arrival] ticks with the pod dropped or not held and ridden: {}; per tick: {}",
                    dropped,
                    String.join(" ", ticks));
            screenshot(context, "arrived");
        }
    }
}
