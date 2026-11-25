package io.github.pkeppeler.deepcharter.surface;

import io.github.pkeppeler.deepcharter.surface.GreatStalkLayout.Cell;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.Direction;
import org.jspecify.annotations.Nullable;

/**
 * The cells of one giant fungus (#1202), relative to its base (the cell at the foot of its stem or the middle of its
 * mound, on the floor), as a shape generator decided them: pure data, like {@link GreatStalkLayout}, so a shape can be
 * inspected and fitted to its chunk without a world.
 *
 * @param pieces   what each cell is
 * @param owners   which part a cell belongs to: 0 for the mound and flare every part stands on, 1 and up for each stem
 *     or spire with what it holds. Two cells of different parts above the floor never touch face to face, so breaking
 *     one part never brings another down ({@link StalkCollapse})
 * @param optional the cells a placer may leave out where the ground or the air forbids them (the flare, the glowcaps)
 */
public record GiantLayout(Map<Cell, GiantPiece> pieces, Map<Cell, Integer> owners, Set<Cell> optional) {
    /** The cells of a chunk along one side. */
    public static final int CHUNK = 16;

    /** The most cells a giant spans in x or in z: a parasol's widest cap, radius 7. */
    public static final int MOST_ACROSS = 15;

    /** The most cells a giant has, so that two of them fall in one collapse ({@link StalkCollapse}). */
    public static final int MOST_CELLS = 960;

    /** The lowest x of any cell. */
    public int minX() {
        return pieces.keySet().stream().mapToInt(Cell::x).min().orElse(0);
    }

    /** The highest x of any cell. */
    public int maxX() {
        return pieces.keySet().stream().mapToInt(Cell::x).max().orElse(0);
    }

    /** The lowest z of any cell. */
    public int minZ() {
        return pieces.keySet().stream().mapToInt(Cell::z).min().orElse(0);
    }

    /** The highest z of any cell. */
    public int maxZ() {
        return pieces.keySet().stream().mapToInt(Cell::z).max().orElse(0);
    }

    /** The highest y of any cell. */
    public int maxY() {
        return pieces.keySet().stream().mapToInt(Cell::y).max().orElse(0);
    }

    /** This layout seen in a mirror across {@code axis} through its base: every cell and every piece turned. */
    public GiantLayout mirrored(Direction.Axis axis) {
        Map<Cell, GiantPiece> turned = new LinkedHashMap<>();
        Map<Cell, Integer> turnedOwners = new HashMap<>();
        Set<Cell> turnedOptional = new HashSet<>();
        pieces.forEach((cell, piece) -> {
            Cell to = mirror(cell, axis);
            turned.put(to, piece.mirrored(axis));
            turnedOwners.put(to, owners.getOrDefault(cell, 0));
            if (optional.contains(cell)) {
                turnedOptional.add(to);
            }
        });
        return new GiantLayout(turned, turnedOwners, turnedOptional);
    }

    private static Cell mirror(Cell cell, Direction.Axis axis) {
        return switch (axis) {
            case X -> new Cell(-cell.x(), cell.y(), cell.z());
            case Z -> new Cell(cell.x(), cell.y(), -cell.z());
            case Y -> cell;
        };
    }

    /**
     * A layout fitted into a chunk: {@code layout}, which may be this one or its mirror image, with its base at {@code
     * baseX} and {@code baseZ} in the chunk (0 to 15), so that every cell lies in the chunk.
     */
    public record Fit(GiantLayout layout, int baseX, int baseZ) {
        /** Where {@code cell} of the fitted layout lies in its chunk, along x. */
        public int localX(Cell cell) {
            return baseX + cell.x();
        }

        /** Where {@code cell} of the fitted layout lies in its chunk, along z. */
        public int localZ(Cell cell) {
            return baseZ + cell.z();
        }
    }

    /**
     * This layout fitted whole into a chunk, its base as near {@code wantX}, {@code wantZ} as it can be (ADR 0088: a
     * layer feature never writes outside its chunk, and a giant is moved or mirrored to fit, never cut). Of the four
     * ways it can face (as it is, mirrored across x, across z or both), the one that needs the base moved least wins,
     * so a stem that curves toward the side of the chunk curves back toward its middle instead. Empty if the layout is
     * more than {@link #MOST_ACROSS} cells across or has more than {@link #MOST_CELLS} cells.
     */
    public Optional<Fit> fitInChunk(int wantX, int wantZ) {
        if (maxX() - minX() + 1 > MOST_ACROSS || maxZ() - minZ() + 1 > MOST_ACROSS || pieces.size() > MOST_CELLS) {
            return Optional.empty();
        }
        Fit best = null;
        int bestMove = Integer.MAX_VALUE;
        for (int mirrors = 0; mirrors < 4; mirrors++) {
            GiantLayout turned = this;
            if ((mirrors & 1) != 0) {
                turned = turned.mirrored(Direction.Axis.X);
            }
            if ((mirrors & 2) != 0) {
                turned = turned.mirrored(Direction.Axis.Z);
            }
            int x = Math.clamp(wantX, -turned.minX(), CHUNK - 1 - turned.maxX());
            int z = Math.clamp(wantZ, -turned.minZ(), CHUNK - 1 - turned.maxZ());
            int move = Math.abs(x - wantX) + Math.abs(z - wantZ);
            if (move < bestMove) {
                best = new Fit(turned, x, z);
                bestMove = move;
            }
        }
        return Optional.ofNullable(best);
    }

    /** Collects a shape's cells; the first cell put at a position stays. */
    static final class Builder {
        private final Map<Cell, GiantPiece> pieces = new LinkedHashMap<>();
        private final Map<Cell, Integer> owners = new HashMap<>();
        private final Set<Cell> optional = new HashSet<>();

        boolean has(Cell cell) {
            return pieces.containsKey(cell);
        }

        @Nullable
        GiantPiece piece(Cell cell) {
            return pieces.get(cell);
        }

        void put(Cell cell, GiantPiece piece, int owner) {
            if (pieces.putIfAbsent(cell, piece) == null) {
                owners.put(cell, owner);
            }
        }

        /** Puts {@code piece} in a cell already taken, keeping its owner. */
        void replace(Cell cell, GiantPiece piece) {
            pieces.replace(cell, piece);
        }

        void putOptional(Cell cell, GiantPiece piece) {
            if (!pieces.containsKey(cell)) {
                optional.add(cell);
                put(cell, piece, 0);
            }
        }

        /**
         * Whether {@code cell} is free for part {@code owner}: nothing is in it, and no face of it touches a cell of
         * another part above the floor, so that each part stays its own when one breaks.
         */
        boolean freeFor(Cell cell, int owner) {
            if (pieces.containsKey(cell)) {
                return false;
            }
            for (Direction face : Direction.values()) {
                Cell next = cell.offset(face.getStepX(), face.getStepY(), face.getStepZ());
                Integer other = owners.get(next);
                if (other != null && other != owner && other != 0 && !optional.contains(next)) {
                    return false;
                }
            }
            return true;
        }

        GiantLayout build() {
            // In the order the shape grew them, so a placer writes them in the same order on every run.
            return new GiantLayout(
                    Collections.unmodifiableMap(new LinkedHashMap<>(pieces)), Map.copyOf(owners), Set.copyOf(optional));
        }
    }
}
