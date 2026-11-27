package io.github.pkeppeler.deepcharter.handbook;

import io.github.pkeppeler.deepcharter.layer.LayerStructures;
import io.github.pkeppeler.deepcharter.layer.StructureKind;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;

/**
 * The place a pencil path points to, and the test for having reached it. A place is named by what stands in the world, never by a
 * coordinate: a structure kind, the Prospector's wreck, or another Note. A later payoff (a cache, a wax cylinder) is one more
 * implementation here.
 */
public sealed interface PencilPlace {
    /**
     * Whether a player standing at {@code pos} in {@code level} has reached this place. {@code found} is the Notes their charter has
     * found. A level that holds no layer the place is in answers false, so the Nether and the End never count.
     */
    boolean reached(ServerLevel level, BlockPos pos, Set<Identifier> found);

    /** The inside of any structure of {@code kind}, or the ground within the reach of it. */
    record Structure(StructureKind kind) implements PencilPlace {
        @Override
        public boolean reached(ServerLevel level, BlockPos pos, Set<Identifier> found) {
            return LayerStructures.siteNear(level, kind, pos)
                    .filter(site -> PencilPaths.near(site, pos))
                    .isPresent();
        }
    }

    /** PROSPECTOR-0002's wreck: the site nearest the Conduit in its layer ({@link LayerStructures#prospector}). */
    record Wreck() implements PencilPlace {
        @Override
        public boolean reached(ServerLevel level, BlockPos pos, Set<Identifier> found) {
            return LayerStructures.prospector(level)
                    .filter(site -> PencilPaths.near(site, pos))
                    .isPresent();
        }
    }

    /** Another Note: reached when the charter has found it. */
    record Note(int number) implements PencilPlace {
        @Override
        public boolean reached(ServerLevel level, BlockPos pos, Set<Identifier> found) {
            return found.contains(Notes.id(number));
        }
    }
}
