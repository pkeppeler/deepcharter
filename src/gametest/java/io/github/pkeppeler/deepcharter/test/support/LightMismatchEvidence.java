package io.github.pkeppeler.deepcharter.test.support;

import io.github.pkeppeler.deepcharter.layer.LightSync;
import io.github.pkeppeler.deepcharter.test.mixin.ChunkMapAccess;
import io.github.pkeppeler.deepcharter.test.support.ClientLight.Box;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/**
 * Says which side is wrong when {@link ClientLight} finds a client reading other light than the server, and why. It runs only when the two
 * differ, so the passing path pays nothing, and it adds nothing to the comparison. For the first few differing cells it reports what the
 * blocks alone give ({@link BlockLightOracle}: is the server right?), every light emitter within 15 blocks with its block state on each
 * side (a block change the client never got shows up there), and for every chunk of the box and its ring whether the server ticks it,
 * whether its holder has changes to broadcast, whether a {@link LightSync} hold is on it and whether the client holds it.
 */
final class LightMismatchEvidence {
    private static final int CELLS = 3;
    private static final int REACH = 15;
    private static final int EMITTERS = 12;

    private LightMismatchEvidence() {}

    /** The cells to explain: the first few cells of the box where the two sides' light differs. */
    static List<BlockPos> firstDiffering(List<BlockPos> cells, int[] server, int[] client) {
        List<BlockPos> found = new ArrayList<>();
        for (int i = 0; i < client.length && found.size() < CELLS; i++) {
            if (server[i] != client[i]) {
                found.add(cells.get(i));
            }
        }
        return found;
    }

    /**
     * What the server knows about {@code cells}, the emitters round them and the chunks of the box. The oracle's verdict covers the whole
     * box: how many cells the server's stored block light, and how many the client's, differ from the light the blocks give.
     */
    static String server(ServerLevel level, Box box, List<BlockPos> cells, int[] clientLights) {
        StringBuilder out = new StringBuilder("\n    oracle (server blocks alone), block light over the whole box:");
        try {
            BlockLightOracle.Field field = BlockLightOracle.expected(
                    level,
                    new BoundingBox(
                            box.min().getX(),
                            box.min().getY(),
                            box.min().getZ(),
                            box.max().getX(),
                            box.max().getY(),
                            box.max().getZ()));
            List<BlockPos> all = box.cells().map(BlockPos::immutable).toList();
            StringBuilder serverWrong = new StringBuilder();
            StringBuilder clientWrong = new StringBuilder();
            int serverOff = 0;
            int clientOff = 0;
            for (int i = 0; i < all.size(); i++) {
                int expected = field.at(all.get(i));
                int stored = level.getBrightness(LightLayer.BLOCK, all.get(i));
                int client = clientLights[i] % 16;
                if (stored != expected && serverOff++ < CELLS) {
                    serverWrong
                            .append(" ")
                            .append(all.get(i).toShortString())
                            .append(" stored ")
                            .append(stored)
                            .append(" expected ")
                            .append(expected)
                            .append(";");
                }
                if (client != expected && clientOff++ < CELLS) {
                    clientWrong
                            .append(" ")
                            .append(all.get(i).toShortString())
                            .append(" client ")
                            .append(client)
                            .append(" expected ")
                            .append(expected)
                            .append(";");
                }
            }
            out.append("\n      server stores other than the oracle in ")
                    .append(serverOff)
                    .append(" of ")
                    .append(all.size())
                    .append(serverWrong)
                    .append("\n      client reads other than the oracle in ")
                    .append(clientOff)
                    .append(" of ")
                    .append(all.size())
                    .append(clientWrong);
        } catch (RuntimeException unavailable) {
            out.append(" unavailable: ").append(unavailable.getMessage());
        }
        out.append("\n    emitters within ").append(REACH).append(" of those cells, server state:");
        emitters(level, cells)
                .forEach((pos, state) -> out.append(" ")
                        .append(pos.toShortString())
                        .append(" ")
                        .append(state)
                        .append(";"));
        return out.toString();
    }

    /** The same emitters, as the client's blocks read them, flagged where they differ from {@code serverStates}. */
    static String client(ClientLevel level, Map<BlockPos, BlockState> serverStates) {
        StringBuilder out = new StringBuilder("\n    emitters, client state:");
        int different = 0;
        for (Map.Entry<BlockPos, BlockState> entry : serverStates.entrySet()) {
            BlockState onClient = level.getBlockState(entry.getKey());
            boolean same = onClient.equals(entry.getValue());
            different += same ? 0 : 1;
            out.append(" ")
                    .append(entry.getKey().toShortString())
                    .append(" ")
                    .append(onClient)
                    .append(same ? ";" : " DIFFERS FROM SERVER;");
        }
        return out.append(" (").append(different).append(" differ)").toString();
    }

    /** Every emitter within {@value #REACH} blocks of a cell (by taxicab distance, the least light travels), on the server. */
    static Map<BlockPos, BlockState> emitters(ServerLevel level, List<BlockPos> cells) {
        Map<BlockPos, BlockState> found = new LinkedHashMap<>();
        for (BlockPos cell : cells) {
            for (BlockPos pos :
                    BlockPos.betweenClosed(cell.offset(-REACH, -REACH, -REACH), cell.offset(REACH, REACH, REACH))) {
                if (found.size() >= EMITTERS
                        || pos.distManhattan(cell) > REACH
                        || level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4) == null) {
                    continue;
                }
                BlockState state = level.getBlockState(pos);
                if (state.getLightEmission() > 0) {
                    found.put(pos.immutable(), state);
                }
            }
        }
        return found;
    }

    /** The block state of every cell of {@code box} and the {@value #REACH} blocks round it, in x, y, z order, as {@code level} reads them. */
    static BlockState[] surroundings(Level level, Box box) {
        List<BlockState> states = new ArrayList<>();
        for (BlockPos pos : BlockPos.betweenClosed(
                box.min().offset(-REACH, -REACH, -REACH), box.max().offset(REACH, REACH, REACH))) {
            states.add(level.getBlockState(pos));
        }
        return states.toArray(BlockState[]::new);
    }

    /**
     * How many blocks within light's reach of the box the client reads as another state than the server, and the first few: a block
     * the client never got, or got late, that light passes or stops at.
     */
    static String blockDifferences(Box box, BlockState[] server, BlockState[] client) {
        List<BlockPos> cells = new ArrayList<>();
        BlockPos.betweenClosed(
                        box.min().offset(-REACH, -REACH, -REACH), box.max().offset(REACH, REACH, REACH))
                .forEach(pos -> cells.add(pos.immutable()));
        StringBuilder first = new StringBuilder();
        int different = 0;
        for (int i = 0; i < server.length; i++) {
            if (!server[i].equals(client[i]) && different++ < 6) {
                first.append(" ")
                        .append(cells.get(i).toShortString())
                        .append(" server ")
                        .append(server[i])
                        .append(" client ")
                        .append(client[i])
                        .append(";");
            }
        }
        return "\n    blocks within " + REACH + " of the box that differ, server vs client: " + different + " of "
                + server.length + first;
    }

    /** Per chunk of the box and its ring, the server's side: sendable, ticking, changes to broadcast, held. */
    static String serverChunks(ServerLevel level, Box box) {
        List<String> held = LightSync.heldChunks();
        String dimension = " in " + level.dimension().identifier() + " ";
        StringBuilder out = new StringBuilder("\n    server chunks (box and ring):");
        for (ChunkPos chunk : ring(box)) {
            ChunkHolder holder =
                    ((ChunkMapAccess) level.getChunkSource().chunkMap).deepcharter$visibleHolder(chunk.pack());
            out.append(" ")
                    .append(chunk)
                    .append(" sendable=")
                    .append(level.getChunkSource().chunkMap.getChunkToSend(chunk.pack()) != null)
                    .append(" ticking=")
                    .append(level.getChunkSource().isPositionTicking(chunk.pack()))
                    .append(" changes=")
                    .append(holder == null ? "no holder" : String.valueOf(holder.hasChangesToBroadcast()))
                    .append(" held=")
                    .append(held.stream().anyMatch(line -> line.contains("chunk " + chunk + dimension)))
                    .append(";");
        }
        return out.toString();
    }

    /** Per chunk of the box and its ring, whether the client holds it. */
    static String clientChunks(ClientLevel level, Box box) {
        StringBuilder out = new StringBuilder("\n    client chunks (box and ring), present:");
        for (ChunkPos chunk : ring(box)) {
            out.append(" ")
                    .append(chunk)
                    .append("=")
                    .append(level.getChunkSource().hasChunk(chunk.x(), chunk.z()))
                    .append(";");
        }
        return out.toString();
    }

    private static List<ChunkPos> ring(Box box) {
        ChunkPos low = ChunkPos.containing(box.min());
        ChunkPos high = ChunkPos.containing(box.max());
        return ChunkPos.rangeClosed(new ChunkPos(low.x() - 1, low.z() - 1), new ChunkPos(high.x() + 1, high.z() + 1))
                .toList();
    }
}
