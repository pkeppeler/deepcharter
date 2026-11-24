package io.github.pkeppeler.deepcharter.client.layer;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.client.layer.mixin.ClientPacketListenerAccess;
import io.github.pkeppeler.deepcharter.layer.SeamChunkPayload;
import io.github.pkeppeler.deepcharter.layer.SeamDiscardPayload;
import io.github.pkeppeler.deepcharter.layer.SeamMissedPayload;
import io.github.pkeppeler.deepcharter.layer.SeamPreparePayload;
import io.github.pkeppeler.deepcharter.layer.SeamSender;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.Optional;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacketData;
import net.minecraft.network.protocol.game.CommonPlayerSpawnInfo;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.dimension.DimensionType;
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
    /** The chunks put into the held level, to drop when it is let go. */
    private static final LongSet LOADED = new LongOpenHashSet();
    /** The dimension of the latest prepare, even when its level could not be built: the server skips chunks on the strength of it. */
    private static @Nullable ResourceKey<Level> preparedDimension;
    /** The held level a respawn has claimed, until that respawn's handler ends. */
    private static @Nullable ClientLevel adopting;

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
        ClientTickEvents.END_CLIENT_TICK.register(SeamClient::drain);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            release();
            preparedDimension = null;
            adopting = null;
            bytesReceived = 0;
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
        } catch (RuntimeException e) {
            DeepCharter.LOGGER.warn(
                    "The background level of {} could not be built: the crossing will take the vanilla path",
                    payload.dimension().identifier(),
                    e);
        }
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
