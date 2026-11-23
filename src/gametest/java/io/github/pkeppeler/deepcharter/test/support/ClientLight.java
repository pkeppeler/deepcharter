package io.github.pkeppeler.deepcharter.test.support;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Compares the light a client reads with the light the server holds (issue 427). A client that is sent a chunk while the server's light
 * engine still works on it keeps the light it was sent, so a sealed room can read sky light 15 on the client and 0 on the server. The
 * comparison is the one the design tour used for its dark room (PR 422), shared by every carved evidence scene and by the tests that
 * place blocks as the mod does.
 */
public final class ClientLight {
    /** Ticks the client gets, after the server's light engine is idle, to receive the light packets that follow. */
    private static final int PACKET_TICKS = 10;
    /** Comparisons, each after a flush of the client's light queue, before a mismatch stands. */
    private static final int ROUNDS = 3;

    private static final Logger LOGGER = LoggerFactory.getLogger(ClientLight.class);

    private ClientLight() {}

    /** A box of block positions, both corners inclusive. */
    public record Box(BlockPos min, BlockPos max) {
        /** The box {@code radius} blocks round {@code centre} in X and Z, from {@code below} blocks under it to {@code above} over it. */
        public static Box around(BlockPos centre, int radius, int below, int above) {
            return new Box(centre.offset(-radius, -below, -radius), centre.offset(radius, above, radius));
        }

        Stream<BlockPos> cells() {
            return BlockPos.betweenClosedStream(min, max);
        }
    }

    /** One number for the sky and block light of every cell of the box, so that two levels with the same light give the same number. */
    public static int fingerprint(Level level, Box box) {
        return box.cells()
                .mapToInt(pos ->
                        level.getBrightness(LightLayer.SKY, pos) * 16 + level.getBrightness(LightLayer.BLOCK, pos))
                .reduce(1, (hash, light) -> 31 * hash + light);
    }

    /** The highest sky light in the box: 15 in a sealed room is the stale light of issue 427. */
    public static int maxSky(Level level, Box box) {
        return box.cells()
                .mapToInt(pos -> level.getBrightness(LightLayer.SKY, pos))
                .max()
                .orElse(0);
    }

    /** The highest block light in the box. */
    public static int maxBlock(Level level, Box box) {
        return box.cells()
                .mapToInt(pos -> level.getBrightness(LightLayer.BLOCK, pos))
                .max()
                .orElse(0);
    }

    /** True when the client reads, in every cell of the box, the sky and block light that the server holds. The client must be in {@code dimension}. */
    public static boolean matches(
            ClientGameTestContext context, TestServerContext server, ResourceKey<Level> dimension, Box box) {
        return describeMismatch(context, server, dimension, box).isEmpty();
    }

    /**
     * Empty when the client reads the server's light in the box; otherwise what each side reads, for a failure message. A client that is in
     * another dimension, or lacks a chunk of the box, does not read the server's light.
     */
    public static Optional<String> describeMismatch(
            ClientGameTestContext context, TestServerContext server, ResourceKey<Level> dimension, Box box) {
        Optional<String> absent = context.computeOnClient(client -> {
            ClientLevel level = clientLevel(client);
            if (!level.dimension().equals(dimension)) {
                return Optional.of(
                        "the client is in " + level.dimension().identifier() + ", not " + dimension.identifier());
            }
            if (!chunksPresent(level, box)) {
                return Optional.of("the client lacks a chunk of " + box);
            }
            return Optional.<String>empty();
        });
        if (absent.isPresent()) {
            return absent;
        }
        int serverPrint =
                server.computeOnServer(minecraftServer -> fingerprint(serverLevel(minecraftServer, dimension), box));
        int clientPrint = context.computeOnClient(client -> fingerprint(clientLevel(client), box));
        if (serverPrint == clientPrint) {
            return Optional.empty();
        }
        int serverSky = server.computeOnServer(minecraftServer -> maxSky(serverLevel(minecraftServer, dimension), box));
        int serverBlock =
                server.computeOnServer(minecraftServer -> maxBlock(serverLevel(minecraftServer, dimension), box));
        int[] clientLights = context.computeOnClient(client -> clientLights(client, box));
        String cells = server.computeOnServer(
                minecraftServer -> differingCells(serverLevel(minecraftServer, dimension), box, clientLights));
        List<BlockPos> first = server.computeOnServer(minecraftServer -> {
            ServerLevel level = serverLevel(minecraftServer, dimension);
            List<BlockPos> all = box.cells().map(BlockPos::immutable).toList();
            int[] serverLights = serverLights(level, all);
            return LightMismatchEvidence.firstDiffering(all, serverLights, clientLights);
        });
        // The failure path only: what makes the server or the client wrong (LightMismatchEvidence).
        Map<BlockPos, BlockState> emitters = new LinkedHashMap<>();
        BlockState[][] serverBlocks = new BlockState[1][];
        String serverSide = server.computeOnServer(minecraftServer -> {
            ServerLevel level = serverLevel(minecraftServer, dimension);
            emitters.putAll(LightMismatchEvidence.emitters(level, first));
            serverBlocks[0] = LightMismatchEvidence.surroundings(level, box);
            return LightMismatchEvidence.server(level, box, first, clientLights)
                    + LightMismatchEvidence.serverChunks(level, box);
        });
        return Optional.of(context.computeOnClient(client -> {
            ClientLevel level = clientLevel(client);
            return cells + " client maxSky=" + maxSky(level, box) + " maxBlock=" + maxBlock(level, box) + " in "
                    + level.dimension().identifier() + ", server maxSky=" + serverSky + " maxBlock=" + serverBlock
                    + serverSide + LightMismatchEvidence.client(level, emitters)
                    + LightMismatchEvidence.blockDifferences(
                            box, serverBlocks[0], LightMismatchEvidence.surroundings(level, box))
                    + LightMismatchEvidence.clientChunks(level, box);
        }));
    }

    private static int[] serverLights(Level level, List<BlockPos> cells) {
        return cells.stream()
                .mapToInt(pos ->
                        level.getBrightness(LightLayer.SKY, pos) * 16 + level.getBrightness(LightLayer.BLOCK, pos))
                .toArray();
    }

    /** The client's sky and block light of every cell of the box, as {@code sky * 16 + block}, in {@link Box#cells} order. */
    private static int[] clientLights(Minecraft client, Box box) {
        Level level = clientLevel(client);
        return box.cells()
                .mapToInt(pos ->
                        level.getBrightness(LightLayer.SKY, pos) * 16 + level.getBrightness(LightLayer.BLOCK, pos))
                .toArray();
    }

    /** How many cells differ and the first few, for a failure message: which cells shows whether the light is late or wrong. */
    private static String differingCells(Level server, Box box, int[] client) {
        List<BlockPos> cells = box.cells().map(BlockPos::immutable).toList();
        StringBuilder first = new StringBuilder();
        int differing = 0;
        for (int i = 0; i < client.length; i++) {
            int serverLight = server.getBrightness(LightLayer.SKY, cells.get(i)) * 16
                    + server.getBrightness(LightLayer.BLOCK, cells.get(i));
            if (serverLight != client[i]) {
                if (differing++ < 4) {
                    first.append(" ")
                            .append(cells.get(i).toShortString())
                            .append(" client sky/block ")
                            .append(client[i] / 16)
                            .append("/")
                            .append(client[i] % 16)
                            .append(" server ")
                            .append(serverLight / 16)
                            .append("/")
                            .append(serverLight % 16)
                            .append(";");
                }
            }
        }
        return differing + " of " + client.length + " cells differ:" + first;
    }

    /** True when the client is in {@code dimension} and holds every chunk the box touches. */
    public static boolean clientHolds(ClientGameTestContext context, ResourceKey<Level> dimension, Box box) {
        return context.computeOnClient(client -> {
            ClientLevel level = clientLevel(client);
            return level.dimension().equals(dimension) && chunksPresent(level, box);
        });
    }

    public static ClientLevel clientLevel(Minecraft client) {
        return Objects.requireNonNull(client.level, "the client is not in a level");
    }

    public static ServerLevel serverLevel(MinecraftServer server, ResourceKey<Level> dimension) {
        return Objects.requireNonNull(server.getLevel(dimension), () -> "the server has no level " + dimension);
    }

    /** True when the level holds every chunk the box touches. */
    public static boolean chunksPresent(Level level, Box box) {
        return ChunkPos.rangeClosed(ChunkPos.containing(box.min()), ChunkPos.containing(box.max()))
                .allMatch(chunk -> level.getChunkSource().hasChunk(chunk.x(), chunk.z()));
    }

    /**
     * True when the server holds every chunk the box touches, and the ring of chunks round them, as one it can send: the chunk is full and
     * its light is done, the state vanilla's {@code ChunkMap.waitForLightBeforeSending} and {@code LightSync} hold a chunk back until.
     * Light spreads at most 15 blocks, so a neighbour still generating is the only other chunk whose light can still change the box.
     * {@link #serverLightIdle} alone is true for a chunk still below the light stage, or whose light job is not yet queued. A ring chunk
     * outside the player's view is never generated, so only a ring chunk a player is sent counts.
     */
    public static boolean serverChunksSendable(TestServerContext server, ResourceKey<Level> dimension, Box box) {
        return server.computeOnServer(minecraftServer -> {
            ServerLevel level = serverLevel(minecraftServer, dimension);
            ChunkMap chunkMap = level.getChunkSource().chunkMap;
            ChunkPos low = ChunkPos.containing(box.min());
            ChunkPos high = ChunkPos.containing(box.max());
            for (ChunkPos chunk : ChunkPos.rangeClosed(
                            new ChunkPos(low.x() - 1, low.z() - 1), new ChunkPos(high.x() + 1, high.z() + 1))
                    .toList()) {
                boolean inBox =
                        chunk.x() >= low.x() && chunk.x() <= high.x() && chunk.z() >= low.z() && chunk.z() <= high.z();
                boolean sent = level.players().stream()
                        .anyMatch(player -> player.getChunkTrackingView().contains(chunk));
                if ((inBox || sent) && chunkMap.getChunkToSend(chunk.pack()) == null) {
                    return false;
                }
            }
            return true;
        });
    }

    /** True when no level of the server has light work pending. */
    public static boolean serverLightIdle(ClientGameTestContext context, TestServerContext server) {
        return server.computeOnServer(minecraftServer -> {
            for (ServerLevel level : minecraftServer.getAllLevels()) {
                if (level.getLightEngine().hasLightWork()) {
                    return false;
                }
            }
            return true;
        });
    }

    /**
     * Waits until the client has applied every light update it was sent. A client queues the light of each chunk packet and light packet
     * and applies ten a frame, in order ({@code ClientLevel.pollLightUpdates}), so a chunk can be present while its light is still
     * queued, and reads full sky light until then (#998): a marker queued behind them runs once they have, and the client's own light
     * engine is idle after it.
     */
    private static void awaitClientLightApplied(ClientGameTestContext context) {
        AtomicBoolean applied = new AtomicBoolean();
        context.runOnClient(client -> clientLevel(client).queueLightUpdate(() -> applied.set(true)));
        ClientWait.until(
                context, "the client to apply the light it was sent", applied::get, () -> "the client's light queue");
        ClientWait.until(
                context,
                "the client's light engine to be idle",
                () -> context.computeOnClient(
                        client -> !clientLevel(client).getLightEngine().hasLightWork()),
                () -> "client light work pending");
    }

    /**
     * Waits until the server's light engine is idle and the client has applied the light it was sent, then returns what a comparison of
     * the box finds. A packet still in flight lands behind the client's marker, so a mismatch is compared again after another
     * {@link #PACKET_TICKS} and a flush, up to {@link #ROUNDS} times: a light that stays different through them is one the client will
     * keep. Sends nothing.
     */
    public static Optional<String> settledMismatch(
            ClientGameTestContext context, TestServerContext server, ResourceKey<Level> dimension, Box box) {
        ClientWait.until(
                context,
                "the server's light engine to be idle",
                () -> serverLightIdle(context, server),
                () -> "light work pending");
        Optional<String> mismatch = Optional.empty();
        for (int round = 1; round <= ROUNDS; round++) {
            // tick-wait: a packet sent after the light job arrives within a few ticks; the comparison is the check
            context.waitTicks(PACKET_TICKS);
            awaitClientLightApplied(context);
            mismatch = describeMismatch(context, server, dimension, box);
            if (mismatch.isEmpty()) {
                return mismatch;
            }
        }
        int serverPrint = server.computeOnServer(s -> fingerprint(serverLevel(s, dimension), box));
        return mismatch.map(m -> m + " after " + ROUNDS + " rounds, server light print " + serverPrint);
    }

    /** A tripwire (issue 832): shows whether client and server light agree at each evidence still. */
    public static void logStillLight(
            ClientGameTestContext context,
            TestServerContext server,
            ResourceKey<Level> dimension,
            String name,
            BlockPos pos) {
        String serverRead = server.computeOnServer(s -> {
            ServerLevel level = serverLevel(s, dimension);
            return "blockLight=" + level.getBrightness(LightLayer.BLOCK, pos) + " skyLight="
                    + level.getBrightness(LightLayer.SKY, pos);
        });
        String clientRead = context.computeOnClient(client -> {
            ClientLevel level = clientLevel(client);
            var probe = client.gameRenderer.mainCamera().attributeProbe();
            return "blockLight=" + level.getBrightness(LightLayer.BLOCK, pos) + " skyLight="
                    + level.getBrightness(LightLayer.SKY, pos) + " dimension="
                    + level.dimension().identifier() + " blockTint="
                    + probe.getValue(EnvironmentAttributes.BLOCK_LIGHT_TINT, 1f) + " skyFactor="
                    + probe.getValue(EnvironmentAttributes.SKY_LIGHT_FACTOR, 1f) + " ambient="
                    + probe.getValue(EnvironmentAttributes.AMBIENT_LIGHT_COLOR, 1f) + " gamma="
                    + client.options.gamma().get() + " darkness="
                    + client.options.darknessEffectScale().get();
        });
        LOGGER.info(
                "[still-light] still={} pos={} client: {} | server: {}",
                name,
                pos.toShortString(),
                clientRead,
                serverRead);
    }
}
