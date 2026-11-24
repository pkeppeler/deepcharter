package io.github.pkeppeler.deepcharter.outpost;

import io.github.pkeppeler.deepcharter.outpost.OutpostShell.Shell;
import io.github.pkeppeler.deepcharter.wreck.SalvageHeaps;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/**
 * What a Sleeper's visit breaks (ADR 0072, 0079, 0092): a few blocks of the outpost's zone that the depth does not rate, that a creature can
 * reach, and that are not in {@code deepcharter:never_settled} ({@link OutpostShell} finds them). A storage block spills what it holds, and its own item, into a salvage heap
 * ({@link SalvageHeaps}); any other block is removed and drops nothing, so a visit never litters the hall with items.
 */
public final class VisitBreaks {
    /** What a run of visits broke: what was lost by block, the heaps made, and the box round every cell changed. */
    public record Broken(Map<Identifier, Integer> lost, int heaps, Optional<BoundingBox> changed) {}

    private VisitBreaks() {}

    /**
     * Meets {@code visits} Sleeper's visits: each breaks up to {@code maxBlocks} of the shell's candidates, picked at random. A storage
     * block spills into a heap; any other becomes air and the shell learns of it ({@link Shell#opened}), so what the break lays bare is a
     * candidate for the next pick. A cell a visit broke is not picked again. Returns what was lost and where.
     */
    public static Broken apply(ServerLevel level, Shell shell, int visits, int maxBlocks, RandomSource random) {
        Map<Identifier, Integer> lost = new LinkedHashMap<>();
        int heaps = 0;
        BoundingBox changed = null;
        for (int visit = 0; visit < visits; visit++) {
            for (int broken = 0; broken < maxBlocks; broken++) {
                List<BlockPos> candidates = shell.candidates();
                if (candidates.isEmpty()) {
                    break;
                }
                BlockPos pos = candidates.get(random.nextInt(candidates.size()));
                BlockState state = level.getBlockState(pos);
                if (level.getBlockEntity(pos) instanceof Container) {
                    heaps += SalvageHeaps.spill(level, pos, List.of(new ItemStack(state.getBlock())));
                } else {
                    lost.merge(BuiltInRegistries.BLOCK.getKey(state.getBlock()), 1, Integer::sum);
                    level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL | Block.UPDATE_SUPPRESS_DROPS);
                }
                shell.opened(pos);
                BoundingBox cell = BoundingBox.fromCorners(pos.offset(-1, -1, -1), pos.offset(1, 1, 1));
                changed = changed == null
                        ? cell
                        : BoundingBox.encapsulatingBoxes(List.of(changed, cell)).orElseThrow();
            }
        }
        return new Broken(lost, heaps, Optional.ofNullable(changed));
    }
}
