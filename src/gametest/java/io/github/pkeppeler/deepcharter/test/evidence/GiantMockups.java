package io.github.pkeppeler.deepcharter.test.evidence;

import io.github.pkeppeler.deepcharter.surface.GiantFungus;
import io.github.pkeppeler.deepcharter.surface.GiantLayout;
import io.github.pkeppeler.deepcharter.surface.GiantPiece;
import io.github.pkeppeler.deepcharter.test.evidence.GiantFungiSpots.Spot;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The builds of the {@code giant-fungi} scenario (#1202), each made round a {@link Spot} of a generated Garden cavern:
 * the #1149 mock-ups of the parasol and the spires (copied from {@code HollowsMockups} of the #1147 concept round, on
 * its branch, so the before still is built the way the user saw it), and a giant of the game's own grown from its
 * layout. Runs on the server thread.
 */
final class GiantMockups {
    private static final int SET = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
    /** The most cells {@link Snapshot#clearGiants} clears: a few giants touching, well past one. */
    private static final int MOST_CLEARED = 8_000;

    private GiantMockups() {}

    /**
     * The block states of a box, to put back after a build: the next build is made on the cavern as it was generated.
     */
    static final class Snapshot {
        private final Map<BlockPos, BlockState> states = new LinkedHashMap<>();

        /**
         * The box of {@code radius} blocks round {@code cell}, from {@code below} under it to {@code above} over it.
         */
        static Snapshot around(ServerLevel level, BlockPos cell, int radius, int below, int above) {
            Snapshot snapshot = new Snapshot();
            for (BlockPos pos :
                    BlockPos.betweenClosed(cell.offset(-radius, -below, -radius), cell.offset(radius, above, radius))) {
                snapshot.states.put(pos.immutable(), level.getBlockState(pos));
            }
            return snapshot;
        }

        /**
         * Clears every giant the world grew that has a block in the box from {@code from} to {@code to}, whole, each
         * followed face to face through its blocks (up to {@link #MOST_CLEARED}), and returns what it cleared, to be
         * put back.
         */
        static Snapshot clearGiants(ServerLevel level, BlockPos from, BlockPos to) {
            Snapshot cleared = new Snapshot();
            Deque<BlockPos> queue = new ArrayDeque<>();
            for (BlockPos pos : BlockPos.betweenClosed(from, to)) {
                if (GiantFungiSpots.giant(level.getBlockState(pos))) {
                    queue.add(pos.immutable());
                }
            }
            while (!queue.isEmpty() && cleared.states.size() < MOST_CLEARED) {
                BlockPos pos = queue.poll();
                BlockState state = level.getBlockState(pos);
                if (cleared.states.containsKey(pos) || !GiantFungiSpots.giant(state)) {
                    continue;
                }
                cleared.states.put(pos, state);
                for (Direction side : Direction.values()) {
                    queue.add(pos.relative(side));
                }
            }
            cleared.states.keySet().forEach(pos -> level.setBlock(pos, Blocks.AIR.defaultBlockState(), SET));
            return cleared;
        }

        /** How many cells it holds. */
        int size() {
            return states.size();
        }

        void restore(ServerLevel level) {
            states.forEach((pos, state) -> {
                if (!level.getBlockState(pos).equals(state)) {
                    level.setBlock(pos, state, SET);
                }
            });
        }
    }

    private static BlockState block(String id) {
        return BuiltInRegistries.BLOCK.getValue(Identifier.parse(id)).defaultBlockState();
    }

    /**
     * Sets {@code state} at {@code pos} if the cell is open (air or a glowcap): a build never cuts the cavern's rock.
     */
    private static void put(ServerLevel level, BlockPos pos, BlockState state) {
        if (GiantFungiSpots.open(level.getBlockState(pos))) {
            level.setBlock(pos, state, SET);
        }
    }

    /**
     * Grows {@code layout} with its base at {@code base}: every cell that is open, as the game's placer sets it,
     * wherever its chunk sides fall (a still, not worldgen). Returns how many cells it set.
     */
    static int grow(ServerLevel level, GiantLayout layout, BlockPos base) {
        int set = 0;
        for (var entry : layout.pieces().entrySet()) {
            BlockPos pos = base.offset(
                    entry.getKey().x(), entry.getKey().y(), entry.getKey().z());
            if (GiantFungus.open(level.getBlockState(pos))) {
                level.setBlock(pos, GiantFungus.state(entry.getValue()), SET);
                set++;
            }
        }
        return set;
    }

    /** The lowest row of a parasol's gills over its base. */
    static int gillsRow(GiantLayout layout) {
        return layout.pieces().entrySet().stream()
                .filter(entry -> entry.getValue() instanceof GiantPiece.Gills)
                .mapToInt(entry -> entry.getKey().y())
                .min()
                .orElseThrow(() -> new AssertionError("a parasol with no gills"));
    }

    /**
     * Sets {@code state} in each open border cell of {@code chunk} at row {@code y}, a line round the chunk inside its
     * edge, so a still shows where the chunk ends. Returns how many cells it set.
     */
    static int outline(ServerLevel level, ChunkPos chunk, int y, BlockState state) {
        int set = 0;
        for (int i = 0; i < GiantLayout.CHUNK; i++) {
            for (BlockPos pos : List.of(
                    new BlockPos(chunk.getMinBlockX() + i, y, chunk.getMinBlockZ()),
                    new BlockPos(chunk.getMinBlockX() + i, y, chunk.getMaxBlockZ()),
                    new BlockPos(chunk.getMinBlockX(), y, chunk.getMinBlockZ() + i),
                    new BlockPos(chunk.getMaxBlockX(), y, chunk.getMinBlockZ() + i))) {
                if (GiantFungiSpots.open(level.getBlockState(pos))) {
                    level.setBlock(pos, state, SET);
                    set++;
                }
            }
        }
        return set;
    }

    /**
     * The #1149 parasol mock-up: a 2 x 2 trunk as tall as the cavern allows (8 to 20) under a broad cap of radius 6
     * that droops at the rim, with a dome of radius 4 over it, all of the great stalk's own cubes.
     */
    static String parasol(ServerLevel level, Spot spot) {
        BlockState trunk = block("deepcharter:great_stalk");
        BlockState cap = block("deepcharter:great_stalk_cap");
        int height = Mth.clamp(spot.clear() - 3, 8, 20);
        BlockPos base = spot.tall();
        for (int y = 0; y < height; y++) {
            for (int dx = 0; dx <= 1; dx++) {
                for (int dz = 0; dz <= 1; dz++) {
                    put(level, base.offset(dx, y, dz), trunk);
                }
            }
        }
        for (int dx = -6; dx <= 7; dx++) {
            for (int dz = -6; dz <= 7; dz++) {
                double r = Math.hypot(dx - 0.5, dz - 0.5);
                if (r <= 6.5) {
                    put(level, base.offset(dx, height, dz), cap);
                }
                if (r <= 4.5) {
                    put(level, base.offset(dx, height + 1, dz), cap);
                }
                if (r > 5.5 && r <= 6.5) {
                    put(level, base.offset(dx, height - 1, dz), cap);
                }
            }
        }
        return "parasol mock-up " + height + " tall";
    }

    /**
     * The #1149 spires mock-up: five thin fungal stalks of falling heights, each with a cap knob on top and bracket
     * shelves of cap off its sides every four blocks.
     */
    static String spires(ServerLevel level, Spot spot) {
        BlockState stalk = block("deepcharter:fungal_stalk");
        BlockState cap = block("deepcharter:great_stalk_cap");
        int tallest = Mth.clamp(spot.clear() - 2, 8, 22);
        double[][] spires = {{0, 0, 1.0}, {2, -2, 0.7}, {-2, 1, 0.8}, {1, 3, 0.55}, {-3, -2, 0.45}};
        int built = 0;
        for (double[] s : spires) {
            BlockPos column = spot.tall().offset((int) s[0], 0, (int) s[1]);
            Optional<BlockPos> cell = GiantFungiSpots.floorAt(
                    level, column.getX(), column.getZ(), spot.tall().getY(), 6);
            if (cell.isEmpty()) {
                continue;
            }
            int height = (int) Math.round(tallest * s[2]);
            for (int y = 0; y < height; y++) {
                put(level, cell.get().above(y), stalk);
                if (y >= 3 && y % 4 == 3) {
                    int side = (y / 4) % 4;
                    put(
                            level,
                            cell.get()
                                    .offset(side == 0 ? 1 : side == 2 ? -1 : 0, y, side == 1 ? 1 : side == 3 ? -1 : 0),
                            cap);
                }
            }
            put(level, cell.get().above(height), cap);
            built++;
        }
        return "spires mock-up, " + built + " spires, the tallest " + tallest;
    }
}
