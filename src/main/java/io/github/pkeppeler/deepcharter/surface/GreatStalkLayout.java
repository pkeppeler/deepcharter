package io.github.pkeppeler.deepcharter.surface;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * The blocks of one great stalk, relative to its origin (the lowest corner of its base), as a shape generator decided them: pure data, so
 * a shape can be inspected without a world.
 *
 * @param parts    what each cell is
 * @param owners   which trunk a cell belongs to, 1 and up, for the braid's trunks and their shelves. Two cells of different trunks never
 *                 touch, so breaking one trunk never brings another down
 * @param optional the cells a placer may leave out where the ground or the air forbids them (a root flare)
 */
public record GreatStalkLayout(Map<Cell, Part> parts, Map<Cell, Integer> owners, Set<Cell> optional) {
    /** One block position, relative to the stalk's origin. */
    public record Cell(int x, int y, int z) {
        Cell offset(int dx, int dy, int dz) {
            return new Cell(x + dx, y + dy, z + dz);
        }
    }

    /** What a cell is made of. */
    public enum Part {
        /** A {@code great_stalk} block. */
        TRUNK,
        /** A {@code great_stalk_cap} block. */
        CAP
    }

    /** The six faces of a cell. */
    private static final int[][] FACES = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};

    /** The cells of this layout face to face with {@code cell}. */
    private Set<Cell> neighbours(Cell cell) {
        Set<Cell> found = new HashSet<>();
        for (int[] face : FACES) {
            Cell next = cell.offset(face[0], face[1], face[2]);
            if (parts.containsKey(next)) {
                found.add(next);
            }
        }
        return found;
    }

    /** The cells of the lowest level, which stand on the ground. */
    private Set<Cell> base() {
        Set<Cell> base = new HashSet<>();
        parts.keySet().forEach(cell -> {
            if (cell.y() == 0) {
                base.add(cell);
            }
        });
        return base;
    }

    private Set<Cell> reach(Set<Cell> from) {
        Set<Cell> seen = new HashSet<>(from);
        Deque<Cell> queue = new ArrayDeque<>(from);
        while (!queue.isEmpty()) {
            for (Cell next : neighbours(queue.poll())) {
                if (seen.add(next)) {
                    queue.add(next);
                }
            }
        }
        return seen;
    }

    /** This layout with {@code gone} taken out, and every cell above the ground that was joined to it only through them. */
    public GreatStalkLayout without(Set<Cell> gone) {
        Map<Cell, Part> kept = new LinkedHashMap<>(parts);
        kept.keySet().removeAll(gone);
        GreatStalkLayout rest = new GreatStalkLayout(kept, owners, optional);
        kept.keySet().retainAll(rest.reach(rest.base()));
        return rest;
    }

    /** Collects a shape's cells; the first cell put at a position stays. */
    static final class Builder {
        private final Map<Cell, Part> parts = new LinkedHashMap<>();
        private final Map<Cell, Integer> owners = new HashMap<>();
        private final Set<Cell> optional = new HashSet<>();

        void put(int x, int y, int z, Part part, int owner) {
            Cell cell = new Cell(x, y, z);
            if (parts.putIfAbsent(cell, part) == null) {
                owners.put(cell, owner);
            }
        }

        void putOptional(int x, int y, int z, int owner) {
            Cell cell = new Cell(x, y, z);
            if (!parts.containsKey(cell)) {
                optional.add(cell);
            }
            put(x, y, z, Part.TRUNK, owner);
        }

        /**
         * The layout, with the cells that would join two independent trunks taken out (the one of the higher owner goes), and whatever that
         * leaves hanging.
         */
        GreatStalkLayout build() {
            GreatStalkLayout layout = new GreatStalkLayout(parts, owners, optional);
            Set<Cell> touching = new HashSet<>();
            owners.forEach((cell, owner) -> {
                for (Cell next : layout.neighbours(cell)) {
                    if (owner > owners.getOrDefault(next, owner)) {
                        touching.add(cell);
                    }
                }
            });
            return layout.without(touching);
        }
    }
}
