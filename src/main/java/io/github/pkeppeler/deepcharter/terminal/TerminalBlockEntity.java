package io.github.pkeppeler.deepcharter.terminal;

import java.util.HashSet;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

import io.github.pkeppeler.deepcharter.DeepCharter;

/**
 * The block entity of every terminal block. It holds no data of its own: repair state is world-wide, in {@link RepairState}.
 * It marks a block as a working terminal and names its {@link TerminalType}, and it is where a later feature (an outpost
 * terminal, say) would keep state for one block.
 */
public final class TerminalBlockEntity extends BlockEntity {
	/** Starts with no valid blocks: {@link TerminalTypes#register} adds each terminal block. */
	public static final BlockEntityType<TerminalBlockEntity> TYPE = Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE,
			Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "terminal"), new BlockEntityType<>(TerminalBlockEntity::new, new HashSet<>()));

	public TerminalBlockEntity(BlockPos pos, BlockState state) {
		super(TYPE, pos, state);
	}

	public TerminalType type() {
		return TerminalTypes.of(getBlockState().getBlock())
				.orElseThrow(() -> new IllegalStateException("no terminal type owns " + getBlockState().getBlock() + " at " + getBlockPos()));
	}
}
