package io.github.pkeppeler.deepcharter.scanner;

import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.jspecify.annotations.Nullable;

/**
 * Blocks as a scanner reads them, with {@code echoes} (cells where something moving gives an echo) read as {@code echo}: a thing the scanner
 * cannot tell from ore. A scan over it draws those cells as ore. Everything else reads through to {@code inner}.
 */
public record EchoedBlocks(BlockGetter inner, Set<BlockPos> echoes, BlockState echo) implements BlockGetter {
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
