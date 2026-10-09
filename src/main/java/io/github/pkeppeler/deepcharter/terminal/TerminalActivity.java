package io.github.pkeppeler.deepcharter.terminal;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerBlockEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

import io.github.pkeppeler.deepcharter.texture.TextureProperties;

/**
 * Keeps each terminal block's {@link TextureProperties#ACTIVE} equal to whether its type is online, so the lit screen shows on a
 * working terminal and the dark one on a broken one (lore: by morning every terminal was dark except the contract terminal).
 *
 * <p>A terminal is set right when it is placed ({@link TerminalBlock}), at every loaded terminal of a type when the type is
 * repaired, and when its chunk loads, which mends a world saved before the state existed and a terminal that was unloaded when
 * its type was repaired. It is purely a look: nothing reads it to decide what a terminal does. These are gameplay and load paths,
 * so unreadable repair data leaves the block as it is and never throws.
 */
public final class TerminalActivity {
	/** The terminal block entities in loaded chunks of every level, so a repair reaches each of them. Server thread only. */
	private static final Set<TerminalBlockEntity> LOADED = Collections.newSetFromMap(new IdentityHashMap<>());

	private TerminalActivity() {
	}

	static void init() {
		ServerChunkEvents.CHUNK_LOAD.register((level, chunk, newlyGenerated) -> sync(level, chunk));
		ServerBlockEntityEvents.BLOCK_ENTITY_LOAD.register((blockEntity, level) -> {
			if (blockEntity instanceof TerminalBlockEntity terminal) {
				LOADED.add(terminal);
			}
		});
		ServerBlockEntityEvents.BLOCK_ENTITY_UNLOAD.register((blockEntity, level) -> {
			if (blockEntity instanceof TerminalBlockEntity terminal) {
				LOADED.remove(terminal);
			}
		});
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> LOADED.clear());
		TerminalEvents.REPAIRED.register((server, type, charter, player) -> syncLoaded(type));
	}

	/** Sets the terminal at {@code pos} to show whether its type is online. A block that is no terminal is left alone. */
	public static void sync(ServerLevel level, BlockPos pos) {
		BlockState state = level.getBlockState(pos);
		BlockState right = corrected(level.getServer(), state);
		if (right != state) {
			level.setBlock(pos, right, Block.UPDATE_ALL);
		}
	}

	/** {@link #sync(ServerLevel, BlockPos)} for every loaded terminal of {@code type}, wherever it stands. */
	static void syncLoaded(TerminalType type) {
		for (TerminalBlockEntity terminal : List.copyOf(LOADED)) {
			if (!terminal.isRemoved() && terminal.getLevel() instanceof ServerLevel level && terminal.type() == type) {
				sync(level, terminal.getBlockPos());
			}
		}
	}

	/**
	 * {@link #sync(ServerLevel, BlockPos)} for every terminal in a chunk that is loading. It finds them by their blocks, not their
	 * block entities, and skips a section whose palette holds no terminal. It sets the chunk's blocks directly, as
	 * {@code colony/Conduit} does: the chunk is not in the level's view yet, and reading it through the level would wait on its own
	 * load.
	 */
	public static void sync(ServerLevel level, LevelChunk chunk) {
		LevelChunkSection[] sections = chunk.getSections();
		for (int index = 0; index < sections.length; index++) {
			LevelChunkSection section = sections[index];
			if (section.hasOnlyAir() || !section.maybeHas(state -> state.getBlock() instanceof TerminalBlock)) {
				continue;
			}
			int baseY = SectionPos.sectionToBlockCoord(chunk.getSectionYFromSectionIndex(index));
			for (int y = 0; y < SectionPos.SECTION_SIZE; y++) {
				for (int z = 0; z < SectionPos.SECTION_SIZE; z++) {
					for (int x = 0; x < SectionPos.SECTION_SIZE; x++) {
						BlockState state = section.getBlockState(x, y, z);
						BlockState right = corrected(level.getServer(), state);
						if (right != state) {
							chunk.setBlockState(chunk.getPos().getBlockAt(x, baseY + y, z), right, 0);
						}
					}
				}
			}
		}
	}

	/**
	 * {@code state} with {@link TextureProperties#ACTIVE} set to whether its type is online; {@code state} itself when it is
	 * already right, is no terminal, or its repair data is unreadable.
	 */
	static BlockState corrected(MinecraftServer server, BlockState state) {
		Optional<TerminalType> type = TerminalTypes.of(state.getBlock());
		if (type.isEmpty()) {
			return state;
		}
		RepairState repairs = RepairState.get(server);
		if (type.get().needsRepair() && !repairs.isReadable()) {
			return state;
		}
		return state.setValue(TextureProperties.ACTIVE, repairs.online(type.get()));
	}
}
