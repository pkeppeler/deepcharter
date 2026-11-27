package io.github.pkeppeler.deepcharter.client.layer;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.client.layer.mixin.ClientLevelMapAccess;
import io.github.pkeppeler.deepcharter.client.layer.mixin.ClientPacketListenerAccess;
import io.github.pkeppeler.deepcharter.client.layer.mixin.MinecraftSeamAccess;
import io.github.pkeppeler.deepcharter.layer.LayerTuning;
import io.github.pkeppeler.deepcharter.layer.SeamAcceptPayload;
import io.github.pkeppeler.deepcharter.layer.SeamChunkPayload;
import io.github.pkeppeler.deepcharter.layer.SeamDiscardPayload;
import io.github.pkeppeler.deepcharter.layer.SeamMissedPayload;
import io.github.pkeppeler.deepcharter.layer.SeamPreparePayload;
import io.github.pkeppeler.deepcharter.layer.SeamRefusePayload;
import io.github.pkeppeler.deepcharter.layer.SeamRefuseReason;
import io.github.pkeppeler.deepcharter.layer.SeamSender;
import io.github.pkeppeler.deepcharter.layer.SeamSwapPayload;
import io.github.pkeppeler.deepcharter.layer.mixin.EntitySeamAccess;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLevelEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacketData;
import net.minecraft.network.protocol.game.CommonPlayerSpawnInfo;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.entity.EntityInLevelCallback;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * The client's half of a seam (#1185, ADR 0089): the level of the dimension the vehicle is about to cross into, held in the
 * background and filled from the server's {@link SeamChunkPayload}s, and adopted by the respawn into that dimension in place of the
 * level vanilla would build empty. The held level is never the one the client shows until the respawn makes it so; nothing marks its
 * sections dirty in the renderer ({@code ClientLevelSeamMixin}); its light is applied and drained here, as the client does for the
 * level it shows. A client that cannot adopt (no level held, a different level described by the respawn, a dying player) takes the
 * vanilla path, and tells the server so it sends what it skipped.
 *
 * <p>All of it runs on the client thread.
 */
public final class SeamClient {
    /** What a {@link SeamPreparePayload} described, to compare with the respawn that is to adopt the level it made. */
    private record Prepared(
            ResourceKey<Level> dimension,
            ResourceKey<DimensionType> dimensionType,
            long seedHash,
            int seaLevel,
            boolean flat,
            boolean debug) {
        static Prepared of(SeamPreparePayload payload) {
            return new Prepared(
                    payload.dimension(),
                    payload.dimensionType(),
                    payload.seedHash(),
                    payload.seaLevel(),
                    payload.flat(),
                    payload.debug());
        }
    }

    private static @Nullable ClientLevel held;
    private static @Nullable Prepared heldPrepared;
    /** The generation of the prepare the held level was built or kept for, which the swap must name. */
    private static int heldGeneration;
    /** The chunks put into the held level, to drop when it is let go. */
    private static final LongSet LOADED = new LongOpenHashSet();
    /** The dimension of the latest prepare, even when its level could not be built: the server skips chunks on the strength of it. */
    private static @Nullable ResourceKey<Level> preparedDimension;
    /** An entity the server will add again to the new level after a swap, which the client holds already. */
    private record Carried(UUID uuid, EntityType<?> type) {}

    /**
     * The entities a swap carried that the server has not yet added again, by id: their removal is dropped, and their
     * add pairs.
     */
    private static final Map<Integer, Carried> CARRIED = new HashMap<>();
    /** The client tick at which the carried window ends. */
    private static long carriedUntil;
    /** Client ticks counted here, for the carried window. */
    private static long ticks;
    /** Whether the latest swap failed its checks, so the breach payload that follows it starts the fade. */
    private static boolean swapFailed;
    /** The held level a respawn has claimed, until that respawn's handler ends. */
    private static @Nullable ClientLevel adopting;

    /** Whether the swap's checks fail on purpose, so a test can send the client down the recovery path. */
    private static volatile boolean failSwaps;

    private static boolean adoptedThisRespawn;
    private static long bytesReceived;

    private SeamClient() {}

    public static void init() {
        ClientPlayNetworking.registerGlobalReceiver(
                SeamPreparePayload.TYPE, (payload, context) -> prepare(context.client(), payload));
        ClientPlayNetworking.registerGlobalReceiver(
                SeamChunkPayload.TYPE, (payload, context) -> receive(context.client(), payload));
        ClientPlayNetworking.registerGlobalReceiver(
                SeamDiscardPayload.TYPE, (payload, context) -> discard(context.client(), payload.dimension()));
        ClientPlayNetworking.registerGlobalReceiver(
                SeamSwapPayload.TYPE, (payload, context) -> swap(context.client(), payload));
        ClientTickEvents.END_CLIENT_TICK.register(SeamClient::drain);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            release();
            preparedDimension = null;
            adopting = null;
            bytesReceived = 0;
            CARRIED.clear();
            swapFailed = false;
        });
    }

    /** The level holding the arrival area that is not the one the client shows, if the server prepared one. */
    // test-seam: SeamAdoptionClientTest reads the held level, to show that the crossing adopts it and not a fresh one
    public static Optional<ClientLevel> background() {
        return Optional.ofNullable(held);
    }

    /** Bytes of arrival chunks the client has been sent ahead in this session, for the timing log of the client test. */
    // test-seam: SeamAdoptionClientTest logs it in its SEAM-TIMING line
    public static long bytesReceived() {
        return bytesReceived;
    }

    /** Whether {@code level} is the held level and not the one shown: the renderer is not told about its blocks and sections. */
    public static boolean isBackground(ClientLevel level) {
        return level == held && level != Minecraft.getInstance().level;
    }

    /** Makes every swap's checks fail while {@code fail} is true, as a client that could not take it would. */
    // test-seam: SeamSwapClientTest drives the recovery path, which a client cannot be made to need on demand
    public static void failSwapsForTest(boolean fail) {
        failSwaps = fail;
    }

    /** The held level a respawn is adopting right now, if it is. */
    public static @Nullable ClientLevel adopting() {
        return adopting;
    }

    private static void prepare(Minecraft client, SeamPreparePayload payload) {
        ClientPacketListener listener = client.getConnection();
        ClientLevel shown = client.level;
        if (listener == null || shown == null || shown.dimension().equals(payload.dimension())) {
            return;
        }
        preparedDimension = payload.dimension();
        Prepared prepared = Prepared.of(payload);
        ClientLevel current = held;
        if (current != null && prepared.equals(heldPrepared)) {
            current.getChunkSource()
                    .updateViewCenter(payload.centre().x(), payload.centre().z());
            accept(payload);
            return;
        }
        release();
        try {
            ClientPacketListenerAccess access = (ClientPacketListenerAccess) listener;
            ClientLevel.ClientLevelData data = shown.getLevelData();
            ClientLevel level = new ClientLevel(
                    listener,
                    new ClientLevel.ClientLevelData(data.getDifficulty(), data.isHardcore(), payload.flat()),
                    payload.dimension(),
                    listener.registryAccess()
                            .lookupOrThrow(Registries.DIMENSION_TYPE)
                            .getOrThrow(payload.dimensionType()),
                    access.deepcharter$serverChunkRadius(),
                    access.deepcharter$serverSimulationDistance(),
                    client.levelExtractor,
                    payload.debug(),
                    payload.seedHash(),
                    payload.seaLevel());
            level.getChunkSource()
                    .updateViewCenter(payload.centre().x(), payload.centre().z());
            held = level;
            heldPrepared = prepared;
            accept(payload);
        } catch (RuntimeException e) {
            DeepCharter.LOGGER.warn(
                    "The background level of {} could not be built: the crossing will take the vanilla path",
                    payload.dimension().identifier(),
                    e);
            ClientPlayNetworking.send(
                    new SeamRefusePayload(payload.dimension(), payload.generation(), SeamRefuseReason.BUILD_FAILED));
        }
    }

    /** Tells the server the held level is ready for the prepare of this generation: it may swap this client into it. */
    private static void accept(SeamPreparePayload payload) {
        heldGeneration = payload.generation();
        ClientPlayNetworking.send(new SeamAcceptPayload(payload.dimension(), payload.generation()));
    }

    private static void receive(Minecraft client, SeamChunkPayload payload) {
        ClientLevel level = held;
        ClientPacketListener listener = client.getConnection();
        if (level == null
                || listener == null
                || level == client.level
                || !level.dimension().equals(payload.dimension())) {
            return;
        }
        ClientboundLevelChunkWithLightPacket packet = payload.chunk();
        int x = packet.x();
        int z = packet.z();
        level.getChunkSource().replaceWithPacketData(x, z, packet.chunkData());
        LOADED.add(ChunkPos.pack(x, z));
        bytesReceived += SeamSender.estimatedBytes(packet);
        ClientboundLightUpdatePacketData light = packet.lightData();
        level.queueLightUpdate(() -> applyLight(listener, level, x, z, light));
    }

    /**
     * Applies a chunk's light the way {@code handleLevelChunkWithLight} does, to the held level. The listener's level is the held one
     * while it runs: Sodium reads it when light data is applied, to tell its chunk tracker which level got light.
     */
    private static void applyLight(
            ClientPacketListener listener, ClientLevel level, int x, int z, ClientboundLightUpdatePacketData light) {
        ClientPacketListenerAccess access = (ClientPacketListenerAccess) listener;
        ClientLevel shown = access.deepcharter$level();
        access.deepcharter$setLevel(level);
        try {
            access.deepcharter$applyLightData(x, z, light, false);
            LevelChunk chunk = level.getChunkSource().getChunk(x, z, false);
            if (chunk != null) {
                access.deepcharter$enableChunkLight(chunk, x, z);
            }
        } finally {
            access.deepcharter$setLevel(shown);
        }
    }

    private static void discard(Minecraft client, ResourceKey<Level> dimension) {
        ClientLevel level = held;
        if (level != null && level.dimension().equals(dimension) && level != client.level) {
            release();
        }
        if (dimension.equals(preparedDimension)) {
            preparedDimension = null;
        }
    }

    /** Each client tick the held level's light is polled and run, as the shown level's is by the client's own tick. */
    private static void drain(Minecraft client) {
        ticks++;
        if (!CARRIED.isEmpty() && ticks >= carriedUntil) {
            endCarriedWindow(client);
        }
        ClientLevel level = held;
        if (level == null) {
            return;
        }
        if (client.level == null) {
            release();
        } else if (level != client.level) {
            level.pollLightUpdates();
            level.getChunkSource().getLightEngine().runLightUpdates();
        }
    }

    private static void release() {
        ClientLevel level = held;
        held = null;
        heldPrepared = null;
        if (level != null) {
            LOADED.forEach(packed -> level.getChunkSource().drop(ChunkPos.unpack(packed)));
        }
        LOADED.clear();
    }

    /**
     * The swap (#1229, ADR 0089): the vehicle the player rides, and everything it carries, moves into the held level
     * with no respawn. Checks first; a client that cannot swap tells the server, which respawns it alone, and fades
     * while it waits.
     */
    private static void swap(Minecraft client, SeamSwapPayload payload) {
        String problem = swapProblem(client, payload);
        if (problem != null) {
            DeepCharter.LOGGER.warn(
                    "The swap into {} cannot be taken: {}", payload.dimension().identifier(), problem);
            refuseSwap(payload);
            return;
        }
        try {
            applySwap(client, payload);
        } catch (RuntimeException failure) {
            // Best effort: what has moved stays where it is, the server is told, and its recovery respawns this
            // player, which builds the level again from nothing.
            DeepCharter.LOGGER.error(
                    "The swap into {} failed half way", payload.dimension().identifier(), failure);
            refuseSwap(payload);
        }
    }

    /** Tells the server this client could not take the swap, and fades the breach that follows it. */
    private static void refuseSwap(SeamSwapPayload payload) {
        swapFailed = true;
        ClientPlayNetworking.send(
                new SeamRefusePayload(payload.dimension(), payload.generation(), SeamRefuseReason.SWAP_FAILED));
    }

    /** Moves the player and what it rides into the held level and shows that level: the part of a swap that mutates. */
    private static void applySwap(Minecraft client, SeamSwapPayload payload) {
        ClientLevel from = Objects.requireNonNull(client.level);
        ClientLevel to = Objects.requireNonNull(held);
        LocalPlayer player = Objects.requireNonNull(client.player);
        ClientPacketListener listener = Objects.requireNonNull(client.getConnection());
        // The light the held level has been sent is applied before it is shown, so the first frame is lit.
        to.pollLightUpdates();
        to.getChunkSource().getLightEngine().runLightUpdates();

        Entity root = player.getRootVehicle();
        List<Entity> tree = root.getSelfAndPassengers().toList();
        Vec3 shift = payload.arrival().subtract(root.position());
        for (Entity entity : tree) {
            // Never removed from the old level: removal runs unRide. It is left to be forgotten with that level.
            ((EntitySeamAccess) entity).deepcharter$setLevel(to);
            entity.setLevelCallback(EntityInLevelCallback.NULL);
            entity.setPos(entity.position().add(shift));
            entity.setOldPosAndRot(entity.oldPosition().add(shift), entity.yRotO, entity.xRotO);
            entity.getInterpolation().cancel();
        }
        ClientLevelMapAccess maps = (ClientLevelMapAccess) from;
        ((ClientLevelMapAccess) to).deepcharter$addMapData(maps.deepcharter$allMapData());
        for (Entity entity : tree) {
            to.addEntity(entity);
        }

        ClientPacketListenerAccess access = (ClientPacketListenerAccess) listener;
        access.deepcharter$setLevel(to);
        access.deepcharter$setLevelData(to.getLevelData());
        to.getChunkSource().updateViewRadius(access.deepcharter$serverChunkRadius());
        to.setServerSimulationDistance(access.deepcharter$serverSimulationDistance());
        client.level = to;
        ((MinecraftSeamAccess) client).deepcharter$updateLevelInEngines(to, false);
        client.setCameraEntity(player);

        held = null;
        heldPrepared = null;
        LOADED.clear();
        preparedDimension = null;
        swapFailed = false;
        CARRIED.clear();
        for (Entity entity : tree) {
            if (entity != player) {
                CARRIED.put(entity.getId(), new Carried(entity.getUUID(), entity.getType()));
            }
        }
        carriedUntil = ticks + LayerTuning.DEFAULT.seamMissedWindowTicks();
        ClientLevelEvents.AFTER_CLIENT_LEVEL_CHANGE.invoker().afterLevelChange(client, to);
        SeamSwapEvents.SWAPPED.invoker().onSwapped(from, to);
    }

    /** Why the client cannot take {@code payload}'s swap, or null when it can. */
    private static @Nullable String swapProblem(Minecraft client, SeamSwapPayload payload) {
        ClientLevel to = held;
        Prepared prepared = heldPrepared;
        LocalPlayer player = client.player;
        if (failSwaps) {
            return "made to fail by a test";
        }
        if (client.getConnection() == null || client.level == null || player == null) {
            return "the client has no connection, level or player";
        }
        if (to == null || prepared == null || !prepared.dimension().equals(payload.dimension())) {
            return "no level is held for " + payload.dimension().identifier();
        }
        if (heldGeneration != payload.generation()) {
            return "the held level is of generation " + heldGeneration + ", not " + payload.generation();
        }
        if (player.isDeadOrDying()) {
            return "the player is dying";
        }
        Entity root = player.getRootVehicle();
        if (root.getId() != payload.rootId() || !root.getUUID().equals(payload.rootUuid())) {
            return "the vehicle is " + root.getId() + ", not " + payload.rootId();
        }
        Set<Integer> carried = new HashSet<>();
        root.getSelfAndPassengers().forEach(entity -> carried.add(entity.getId()));
        if (!carried.equals(new HashSet<>(payload.carried()))) {
            return "the client carries " + carried + ", the server " + payload.carried();
        }
        BlockPos arrival = BlockPos.containing(payload.arrival());
        if (to.getChunkSource().getChunk(arrival.getX() >> 4, arrival.getZ() >> 4, false) == null) {
            return "the held level lacks the arrival chunk";
        }
        return null;
    }

    /**
     * Whether the breach payload that follows a failed swap fades: it does, once, since the client is about to respawn.
     */
    public static boolean takeSwapFailure() {
        boolean failed = swapFailed;
        swapFailed = false;
        return failed;
    }

    /**
     * An add of {@code packet}'s entity into the level {@code level} the client swapped into, which it holds already:
     * only its position codec is synced. Returns true when it was one of those and the add is done.
     */
    public static boolean pairCarried(ClientLevel level, ClientboundAddEntityPacket packet) {
        Carried carried = CARRIED.get(packet.getId());
        if (carried == null) {
            return false;
        }
        // entity-query: an id the swap carried is in the level the client moved it to; a miss leaves the vanilla add
        // to run
        Entity entity = level.getEntity(packet.getId());
        if (entity == null || !carried.uuid().equals(packet.getUUID()) || carried.type() != packet.getType()) {
            return false;
        }
        entity.syncPacketPositionCodec(packet.getX(), packet.getY(), packet.getZ());
        CARRIED.remove(packet.getId());
        return true;
    }

    /**
     * {@code ids} without the carried entities that await their add: the server removed them from the level the vehicle
     * left.
     */
    public static IntList withoutCarried(IntList ids) {
        if (CARRIED.isEmpty()) {
            return ids;
        }
        IntList kept = new IntArrayList(ids.size());
        for (int id : ids) {
            if (!CARRIED.containsKey(id)) {
                kept.add(id);
            }
        }
        return kept;
    }

    /** Drops the carried entities the server never added again: they were not in the level it moved the vehicle to. */
    private static void endCarriedWindow(Minecraft client) {
        ClientLevel level = client.level;
        if (level != null) {
            CARRIED.keySet().forEach(id -> {
                // entity-query: an id the swap carried that was never added again; a miss means it is gone already
                Entity entity = level.getEntity(id);
                if (entity != null && entity != client.player) {
                    DeepCharter.LOGGER.warn(
                            "A carried entity {} was never added again after the swap: dropped", entity);
                    level.removeEntity(id, Entity.RemovalReason.DISCARDED);
                }
            });
        }
        CARRIED.clear();
    }

    /**
     * Called at the start of a respawn. Claims the held level when the respawn is into the dimension it was prepared for and describes
     * it as the prepare did, and the player is not dying; a respawn into that dimension that cannot adopt lets the level go.
     */
    public static void claim(CommonPlayerSpawnInfo spawn, @Nullable LocalPlayer oldPlayer) {
        adoptedThisRespawn = false;
        adopting = null;
        ClientLevel level = held;
        Prepared prepared = heldPrepared;
        if (level == null
                || prepared == null
                || oldPlayer == null
                || spawn.dimension().equals(oldPlayer.level().dimension())
                || !spawn.dimension().equals(prepared.dimension())) {
            return;
        }
        boolean describes = spawn.dimensionType().is(prepared.dimensionType())
                && spawn.seed() == prepared.seedHash()
                && spawn.seaLevel() == prepared.seaLevel()
                && spawn.isFlat() == prepared.flat()
                && spawn.isDebug() == prepared.debug();
        if (!describes || oldPlayer.isDeadOrDying()) {
            release();
            return;
        }
        held = null;
        heldPrepared = null;
        LOADED.clear();
        adopting = level;
        adoptedThisRespawn = true;
    }

    /**
     * Called at the end of a respawn into {@code dimension}. Lets go of the claim; when nothing was adopted into a dimension the server
     * prepared, tells it, since it skipped the chunks it sent ahead.
     */
    public static void endClaim(ResourceKey<Level> dimension) {
        adopting = null;
        if (!adoptedThisRespawn && dimension.equals(preparedDimension)) {
            ClientPlayNetworking.send(new SeamMissedPayload(dimension));
        }
        adoptedThisRespawn = false;
        if (dimension.equals(preparedDimension)) {
            preparedDimension = null;
        }
    }
}
