package io.github.pkeppeler.deepcharter.test.support;

import static io.github.pkeppeler.deepcharter.test.support.ClientChecks.require;
import static io.github.pkeppeler.deepcharter.test.support.SeamSite.HOLD_HEIGHT;

import io.github.pkeppeler.deepcharter.client.layer.SeamClient;
import io.github.pkeppeler.deepcharter.layer.BreachPayload;
import io.github.pkeppeler.deepcharter.layer.LightSync;
import io.github.pkeppeler.deepcharter.layer.SeamAcceptPayload;
import io.github.pkeppeler.deepcharter.layer.SeamChunkPayload;
import io.github.pkeppeler.deepcharter.layer.SeamDiscardPayload;
import io.github.pkeppeler.deepcharter.layer.SeamPreparePayload;
import io.github.pkeppeler.deepcharter.layer.SeamRefusePayload;
import io.github.pkeppeler.deepcharter.layer.SeamRefuseReason;
import io.github.pkeppeler.deepcharter.layer.SeamSender;
import io.github.pkeppeler.deepcharter.layer.SeamSwapPayload;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodEvents;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.test.support.SeamSite.Site;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * A pod over the floor of layer 1 with the mock in its first seat and the real client in its second, for a client
 * GameTest of a seam crossing (#1229, ADR 0089): pinned at the hold height while the arrival area is sent, with the
 * mock answering each prepare it is sent (an accept, or a refusal that sends the whole pod through the fallback), and
 * then dropped across the floor. The mock sits in the first seat so the pod is moved by the server and the real client
 * is a passenger. Shared by {@code SeamSwapClientTest} and {@code SeamAdoptionClientTest}.
 */
public final class SeamPodScene {
    private static final int AREA_SIDE = 2 * SeamSender.SEND_RADIUS + 1;
    /** Client ticks the held level's light gets to drain after its last chunk arrives. */
    private static final int LIGHT_DRAIN_TICKS = 40;

    private SeamPodScene() {}

    /**
     * Where each pod is pinned until its crossing, by UUID: pod movement ignores no-gravity (#554), so the hold is an
     * after-tick pin.
     */
    private static final Map<UUID, Vec3> PINS = new ConcurrentHashMap<>();
    /** The mock that answers each prepare it is sent, and how. Read and written on the server thread. */
    private static @Nullable MockPlayer answering;

    private static boolean answersAccept;
    private static int answered;

    static {
        PodEvents.AFTER_TICK.register(pod -> {
            Vec3 pin = PINS.get(pod.getUUID());
            if (pin != null) {
                pod.setPos(pin);
                pod.setDeltaMovement(Vec3.ZERO);
            }
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> answer());
    }

    /** Answers each prepare the mock was sent once, as a client does: an accept of its generation, or a refusal. */
    private static void answer() {
        MockPlayer mock = answering;
        if (mock == null) {
            return;
        }
        List<SeamPreparePayload> prepares = mock.payloadsOf(SeamPreparePayload.class);
        while (answered < prepares.size()) {
            SeamPreparePayload prepare = prepares.get(answered++);
            mock.send(
                    answersAccept
                            ? new SeamAcceptPayload(prepare.dimension(), prepare.generation())
                            : new SeamRefusePayload(
                                    prepare.dimension(), prepare.generation(), SeamRefuseReason.RELEASED));
        }
    }

    /** What the client held before a crossing, to compare with what it holds after. */
    public record Before(
            LocalPlayer player,
            Entity vehicle,
            int vehicleId,
            UUID vehicleUuid,
            Entity mockPlayer,
            ClientLevel shown,
            ClientLevel held) {}

    /**
     * A fresh pod over {@code site} with the mock in its first seat and the real client in its second, pinned at the
     * hold height.
     */
    public static PodEntity seat(
            ClientGameTestContext context, TwoPlayerServer two, UUID clientId, Site site, boolean mockAccepts) {
        MockPlayer mock = two.mock();
        PodEntity[] made = new PodEntity[1];
        Vec3 hold = two.server().computeOnServer(server -> site.at(HOLD_HEIGHT));
        // The riders go first, and the pod joins once its chunk ticks entities: an entity added to a chunk that does
        // not yet is not in the level's lookup, so no rider could board it and no client would be told of it.
        two.server().runOnServer(server -> {
            site.carveShaft(site.x(), HOLD_HEIGHT + 4);
            ServerPlayer real = player(server, clientId);
            real.stopRiding();
            mock.player().stopRiding();
            mock.teleportTo(site.one(), hold, 0, 0);
            real.teleportTo(site.one(), hold.x, hold.y, hold.z, Set.of(), 0, 0, true);
            // Held in the air until the pod is there: nothing else would stop a fall through the floor, which is a
            // crossing.
            mock.player().setNoGravity(true);
            real.setNoGravity(true);
            mock.announceChannel(SeamPreparePayload.TYPE);
            mock.announceChannel(SeamChunkPayload.TYPE);
            mock.announceChannel(SeamDiscardPayload.TYPE);
            mock.announceChannel(SeamSwapPayload.TYPE);
            mock.announceChannel(BreachPayload.TYPE);
            answersAccept = mockAccepts;
            answered = 0;
            answering = mock;
        });
        ClientWait.until(
                context,
                "the chunk of the hold to tick entities in layer 1 and reach the client",
                () -> two.server()
                                .computeOnServer(
                                        server -> site.one().isPositionEntityTicking(BlockPos.containing(hold)))
                        && context.computeOnClient(client -> client.level != null
                                && inLayer(client, 1)
                                && client.level
                                        .getChunkSource()
                                        .hasChunk(
                                                site.centre().x(), site.centre().z())),
                () -> "hold " + hold + ", "
                        + two.server().computeOnServer(server -> diagnose(server, clientId, site, hold))
                        + ", client: "
                        + context.computeOnClient(client -> ClientWait.describe(client) + ", chunks "
                                + (client.level == null
                                        ? -1
                                        : client.level.getChunkSource().getLoadedChunksCount())
                                + ", client loaded "
                                + (client.getConnection() != null
                                        && client.getConnection().hasClientLoaded())));
        two.server().runOnServer(server -> {
            PodEntity pod = TestEntities.create(PodRegistry.POD, site.one());
            TwoSeatPods.add(pod);
            pod.setPos(hold);
            site.one().addFreshEntity(pod);
            PINS.put(pod.getUUID(), hold);
            made[0] = pod;
            mock.player().setNoGravity(false);
            player(server, clientId).setNoGravity(false);
            require(mock.player().startRiding(pod, true, false), "the mock could not board the pod");
            require(player(server, clientId).startRiding(pod, true, false), "the real client could not board the pod");
        });
        ClientWait.until(
                context,
                "the client to ride the pod over layer 1's floor",
                () -> context.computeOnClient(client -> client.player != null
                        && inLayer(client, 1)
                        && vehicleOf(client) instanceof PodEntity pod
                        && pod.getPassengers().size() == 2),
                () -> context.computeOnClient(client -> ClientWait.describe(client) + ", client knows the pod "
                                + (client.level == null ? null : client.level.getEntity(made[0].getId()))
                                + ", entities "
                                + (client.level == null ? -1 : client.level.getEntityCount())
                                + ", client pod passengers "
                                + (client.level == null ? null : vehicleCarry(client, made[0].getId()))
                                + ", player passenger " + (client.player != null && client.player.isPassenger()))
                        + "; server pod: "
                        + two.server()
                                .computeOnServer(server -> made[0] + " removed " + made[0].isRemoved() + " passengers "
                                        + made[0].getPassengers() + " real vehicle "
                                        + player(server, clientId).getVehicle()));
        return made[0];
    }

    /**
     * Waits until the held level has the area and both riders are ready, and reads what the client holds before the
     * crossing.
     */
    public static Before holdArea(
            ClientGameTestContext context, TwoPlayerServer two, UUID clientId, Site site, PodEntity pod) {
        int area = AREA_SIDE * AREA_SIDE;
        ClientWait.until(
                context,
                "the held level to hold the " + area + " chunks of the arrival area",
                client -> SeamClient.background()
                        .filter(level -> level.getChunkSource().getLoadedChunksCount() >= area)
                        .isPresent(),
                client -> "held chunks "
                        + SeamClient.background()
                                .map(level -> level.getChunkSource().getLoadedChunksCount())
                                .map(String::valueOf)
                                .orElse("none (no level prepared)")
                        + ", " + ClientWait.describe(client));
        ClientWait.until(
                context,
                "both riders to be ready, or the mock to refuse",
                () -> two.server()
                        .computeOnServer(server -> SeamSender.readyToSwap(
                                        player(server, clientId), site.two(), site.centre())
                                && (!answersAccept
                                        || SeamSender.readyToSwap(two.mock().player(), site.two(), site.centre()))),
                () -> "server pod at " + pod.position());
        // The server resends a chunk whose light it is settling, a seam's or a structure's: let those settle, so what
        // the crossing sends again is the pocket's and nothing else.
        ClientWait.until(
                context,
                "the light of the arrival area to settle on the server",
                () -> two.server().computeOnServer(server -> watched(site).isEmpty()),
                () -> "watched chunks " + two.server().computeOnServer(server -> watched(site)));
        context.waitTicks(LIGHT_DRAIN_TICKS);
        UUID mockId = two.mock().player().getUUID();
        return context.computeOnClient(client -> new Before(
                Objects.requireNonNull(client.player),
                Objects.requireNonNull(vehicleOf(client)),
                Objects.requireNonNull(vehicleOf(client)).getId(),
                Objects.requireNonNull(vehicleOf(client)).getUUID(),
                Objects.requireNonNull(levelOf(client).getPlayerByUUID(mockId)),
                levelOf(client),
                SeamClient.background().orElseThrow()));
    }

    private static List<ChunkPos> watched(Site site) {
        List<ChunkPos> watched = new ArrayList<>();
        for (int dx = -SeamSender.SEND_RADIUS; dx <= SeamSender.SEND_RADIUS; dx++) {
            for (int dz = -SeamSender.SEND_RADIUS; dz <= SeamSender.SEND_RADIUS; dz++) {
                ChunkPos pos =
                        new ChunkPos(site.centre().x() + dx, site.centre().z() + dz);
                if (LightSync.watching(site.two(), pos)) {
                    watched.add(pos);
                }
            }
        }
        return watched;
    }

    /** Lets the pod go and puts it under the floor, as one tick of a fall at speed would. */
    public static void drop(TwoPlayerServer two, Site site, PodEntity pod) {
        two.server().runOnServer(server -> {
            PINS.remove(pod.getUUID());
            pod.setPos(site.x(), site.floorY() - 1, site.z());
        });
    }

    /** Empties the pod and removes it, and clears the mock's answers, so the next leg starts clean. */
    public static void finish(TwoPlayerServer two, PodEntity pod) {
        two.server().runOnServer(server -> {
            answering = null;
            PINS.remove(pod.getUUID());
            TwoSeatPods.forget(pod);
            Entity current = null;
            for (ServerPlayer rider : server.getPlayerList().getPlayers()) {
                if (rider.getVehicle() != null) {
                    current = rider.getVehicle();
                    rider.stopRiding();
                }
            }
            if (current != null) {
                current.discard();
            }
            pod.discard();
        });
    }

    private static String diagnose(MinecraftServer server, UUID clientId, Site site, Vec3 hold) {
        ServerPlayer real = player(server, clientId);
        return "server: entity ticking " + site.one().isPositionEntityTicking(BlockPos.containing(hold))
                + ", real player at " + real.position() + " in "
                + real.level().dimension().identifier()
                + ", changing dimension " + real.isChangingDimension() + ", client loaded "
                + real.connection.hasClientLoaded() + ", chunk pending "
                + real.connection.chunkSender.isPending(site.centre().pack());
    }

    /** Whether the client's player is in the layer dimension of {@code layer}. */
    public static boolean inLayer(Minecraft client, int layer) {
        return client.player != null && TestLayers.isInDimensionOf(client.player, layer);
    }

    private static String vehicleCarry(Minecraft client, int id) {
        Entity entity = levelOf(client).getEntity(id);
        return entity == null ? "none" : String.valueOf(entity.getPassengers());
    }

    public static @Nullable Entity vehicleOf(Minecraft client) {
        return client.player == null ? null : client.player.getVehicle();
    }

    public static ClientLevel levelOf(Minecraft client) {
        return Objects.requireNonNull(client.level, "the client has no level");
    }

    /**
     * Whether the client's player rides the vehicle {@code uuid}, which carries two entities: it, the mock and nothing
     * else.
     */
    public static boolean ridesWith(Minecraft client, UUID uuid) {
        Entity vehicle = vehicleOf(client);
        return vehicle != null
                && vehicle.getUUID().equals(uuid)
                && vehicle.getPassengers().size() == 2
                && vehicle.getPassengers().contains(client.player);
    }

    public static ServerPlayer player(MinecraftServer server, UUID id) {
        return Objects.requireNonNull(server.getPlayerList().getPlayer(id), () -> "player " + id + " is not online");
    }
}
