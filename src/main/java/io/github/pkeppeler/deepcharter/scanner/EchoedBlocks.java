package io.github.pkeppeler.deepcharter.scanner;

import io.github.pkeppeler.deepcharter.creature.CreatureRegistry;
import io.github.pkeppeler.deepcharter.creature.Tally;
import io.github.pkeppeler.deepcharter.ore.OreType;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.upgrade.ComponentTrack;
import java.util.Set;
import java.util.stream.Collectors;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.jspecify.annotations.Nullable;

/**
 * Blocks as a scanner reads them, with {@code echoes} (cells where something moving gives an echo) read as {@code echo}: a thing the scanner
 * cannot tell from ore. A scan over it draws those cells as ore. Everything else reads through to {@code inner}.
 */
public record EchoedBlocks(BlockGetter inner, Set<BlockPos> echoes, BlockState echo) implements BlockGetter {
    /** The ore a Tally echoes as: brass reads as the nearest metal. */
    private static final OreType TALLY_ORE = OreType.BRONZIUM;

    /** How far from the pod a Tally still echoes: past the largest dimension of the widest scanner's area. */
    public static final int TALLY_REACH_BLOCKS = reach();

    private static int reach() {
        ScanArea widest = ScannerTuning.DEFAULT.area(ComponentTrack.SCANNER.maxTier());
        return Math.max(widest.halfWidth(), Math.max(widest.up(), widest.down())) + 1;
    }

    /**
     * What a pod's scanner reads of {@code level}: the level, with each Tally within {@link #TALLY_REACH_BLOCKS} of the pod read as an ore (a
     * Company machine echoes as ore, CREATURES.md section 2), so that the map shows a block of ore that moves. The client's level holds the
     * Tallies it tracks, which is all a scanner could hear.
     */
    public static BlockGetter ofTallies(Level level, PodEntity pod) {
        // entity-query: the client's level holds only what it tracks, which is what a scanner's echo shows; a Tally out
        // of view has no echo
        Set<BlockPos> cells = level
                .getEntities(
                        CreatureRegistry.TALLY,
                        pod.getBoundingBox().inflate(TALLY_REACH_BLOCKS),
                        tally -> tally.isAlive() && !tally.isRemoved())
                .stream()
                .map(Tally::blockPosition)
                .collect(Collectors.toSet());
        return cells.isEmpty()
                ? level
                : new EchoedBlocks(
                        level,
                        cells,
                        BuiltInRegistries.BLOCK.getValue(TALLY_ORE.blockId()).defaultBlockState());
    }

    @Override
    public @Nullable BlockEntity getBlockEntity(BlockPos pos) {
        return inner.getBlockEntity(pos);
    }

    @Override
    public BlockState getBlockState(BlockPos pos) {
        return echoes.contains(pos) ? echo : inner.getBlockState(pos);
    }

    @Override
    public FluidState getFluidState(BlockPos pos) {
        return echoes.contains(pos) ? echo.getFluidState() : inner.getFluidState(pos);
    }

    @Override
    public int getHeight() {
        return inner.getHeight();
    }

    @Override
    public int getMinY() {
        return inner.getMinY();
    }
}
