package io.github.pkeppeler.deepcharter.outpost;

import io.github.pkeppeler.deepcharter.outpost.OutpostData.Outpost;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * Whether a creature can reach a block of an outpost: the exposure rule of ADR 0072, "open space reachable from outside the shell". A
 * visit breaks only blocks it can reach.
 */
final class OutpostShell {
    private OutpostShell() {}

    /**
     * True when a visit can reach the block at {@code pos}. Filled by #926: until its exposure rule lands, every block of the zone counts
     * as reachable, which changes nothing while the Sleeper's rate is 0.
     */
    static boolean reachable(ServerLevel level, Outpost outpost, BlockPos pos) {
        return true;
    }
}
