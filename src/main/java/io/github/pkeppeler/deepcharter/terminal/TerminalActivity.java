package io.github.pkeppeler.deepcharter.terminal;

import java.util.List;
import java.util.Optional;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

import io.github.pkeppeler.deepcharter.texture.TextureProperties;

/**
 * Keeps each terminal block's {@link TextureProperties#ACTIVE} equal to whether its type is online, so the lit screen shows on a
 * working terminal and the dark one on a broken one (lore: by morning every terminal was dark except the contract terminal).
 *
 * <p>A terminal is set when it is placed ({@link TerminalBlock}), at the terminal its last part went into ({@link Terminals}), and
 * when its chunk loads, which mends a world saved before the state existed and a terminal of a type repaired at another block. It
 * is purely a look: nothing reads it to decide what a terminal does. These are gameplay and load paths, so unreadable repair
 * data leaves the block as it is and never throws.
 */
public final class TerminalActivity {
	private TerminalActivity() {
	}

	static void init() {
		ServerChunkEvents.CHUNK_LOAD.register((level, chunk, newlyGenerated) -> sync(level, chunk));
	}

	/** Sets the terminal at {@code pos} to show whether its type is online. A block that is no terminal is left alone. */
	public static void sync(ServerLevel level, BlockPos pos) {
		placed(level, pos, level.getBlockState(pos));
	}

	/**
	 * {@link #sync(ServerLevel, BlockPos)} for a block just placed in {@code state}. It reads the level only to change the block,
	 * because a block set while its chunk loads (below) is placed too, and reading that chunk then would wait on its own load.
	 */
	static void placed(ServerLevel level, BlockPos pos, BlockState state) {
		shown(level.getServer(), state).ifPresent(online -> level.setBlock(pos, state.setValue(TextureProperties.ACTIVE, online), Block.UPDATE_ALL));
	}

	/**
	 * {@link #sync(ServerLevel, BlockPos)} for every terminal in a chunk that is loading. It sets the chunk's blocks directly, as
	 * {@code colony/Conduit} does, because the chunk is not in the level's view yet. The block it sets is already right, so its
	 * {@link #placed} does nothing.
	 */
	public static void sync(ServerLevel level, LevelChunk chunk) {
		for (BlockPos pos : List.copyOf(chunk.getBlockEntities().keySet())) {
			BlockState state = chunk.getBlockState(pos);
			shown(level.getServer(), state).ifPresent(online -> chunk.setBlockState(pos, state.setValue(TextureProperties.ACTIVE, online), 0));
		}
	}

	/** The ACTIVE value a terminal in {@code state} should take, or empty when it is no terminal, already right, or unreadable. */
	private static Optional<Boolean> shown(MinecraftServer server, BlockState state) {
		Optional<TerminalType> type = TerminalTypes.of(state.getBlock());
		if (type.isEmpty()) {
			return Optional.empty();
		}
		RepairState repairs = RepairState.get(server);
		if (type.get().needsRepair() && !repairs.isReadable()) {
			return Optional.empty();
		}
		boolean online = repairs.online(type.get());
		return state.getValue(TextureProperties.ACTIVE) == online ? Optional.empty() : Optional.of(online);
	}
}
