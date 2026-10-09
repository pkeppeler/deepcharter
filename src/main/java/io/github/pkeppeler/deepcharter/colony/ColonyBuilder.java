package io.github.pkeppeler.deepcharter.colony;

import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.phys.AABB;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.layer.LayerChain;
import io.github.pkeppeler.deepcharter.surface.SurfaceBlocks;
import io.github.pkeppeler.deepcharter.terminal.TerminalType;
import io.github.pkeppeler.deepcharter.terminal.TerminalTypes;

/**
 * Builds the colony once, at world spawn, the first time a server starts with this mod: a flattened pad of
 * {@link ColonyTuning#padSize()} blocks a side with the company town on it, placed from the structure pieces of the
 * {@link ColonyLayout} (ADR 0030, tools/colony/town.py): the square with the Host, the Works and its headframe, the hangar, the
 * Continuity Office, the chapel, the bunkhouse, the offices and the Lamp and Pick. North is -Z.
 *
 * <p>The pad is centred on the world spawn, or on the nearest dry ground if the spawn is in water ({@link #findDryGround}).
 * The pad's ground is the plain's: the surface's regolith, with the packed regolith and rock of its layers scattered on the pad's
 * rim. The land around it is graded to the pad over {@link ColonyTuning#edgeMargin()} blocks ({@link ColonyEdge}), in the same build.
 * Building clears a volume of the pad's size and {@link ColonyTuning#clearHeight()} blocks high: whatever stood there is lost,
 * block displays included, so a build that stopped half way and is built again does not double them.
 *
 * <p>It records the pad's centre and ground before the first block ({@link ColonySite#begin}) and marks the colony finished
 * after the last, so a build that stops half way is built again over the same pad, at the recorded ground. It then sets the
 * world spawn to the Continuity Office and the respawn radius to 0, so a new player stands in the office.
 */
public final class ColonyBuilder {
	/** A block set without neighbour updates. */
	private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;

	/** How many blocks under the surface the packed regolith goes before the rock. */
	private static final int LAYER_PACKED = 2;
	/** The share of columns by the pad's edge, in percent, that show rock; three times that show packed regolith. */
	private static final int SCATTER_PERCENT = 12;

	/** The pad's outer rim, in blocks, where the packed regolith and rock of the layers show through. */
	private static final int RIM = 3;

	private final ServerLevel level;
	private final BlockPos centre;

	private ColonyBuilder(ServerLevel level, BlockPos centre) {
		this.level = level;
		this.centre = centre;
	}

	public static void init() {
		ServerLifecycleEvents.SERVER_STARTED.register(ColonyBuilder::buildIfNeeded);
	}

	/**
	 * Builds the colony if the world has none finished and its data is readable. Returns whether it built one. A second call,
	 * as on a restart, changes nothing.
	 */
	public static boolean buildIfNeeded(MinecraftServer server) {
		Optional<ColonySite> site = Colony.readable(server);
		if (site.isEmpty() || site.get().isBuilt()) {
			return false;
		}
		ServerLevel overworld = server.overworld();
		ColonySite.Placed started = site.get().started().orElseGet(() -> begin(server, overworld, site.get()));
		new ColonyBuilder(overworld, started.center()).build(started);
		site.get().finish();
		ColonySite.Placed placed = site.get().placed().orElseThrow();
		BlockPos office = placed.anchors().get(ColonyAnchor.CONTINUITY_OFFICE);
		server.setRespawnData(LevelData.RespawnData.of(Level.OVERWORLD, office, 0.0F, 0.0F));
		// Players spawn within this many blocks of the world spawn: 0 keeps them in the Continuity Office.
		server.getGameRules().set(GameRules.RESPAWN_RADIUS, 0, server);
		DeepCharter.LOGGER.info("Built the colony at {}", placed.center().toShortString());
		ColonyEvents.BUILT.invoker().onBuilt(server, placed);
		return true;
	}

	/** Chooses the pad and records it, before any block is set. */
	private static ColonySite.Placed begin(MinecraftServer server, ServerLevel overworld, ColonySite site) {
		// The server's own answer is the default until the first tick; the saved world data is the spawn the world chose.
		LevelData.RespawnData spawn = server.getWorldData().overworldData().getRespawnData();
		BlockPos wanted = new BlockPos(spawn.pos().getX(), 0, spawn.pos().getZ());
		BlockPos centre = findDryGround(overworld, wanted).orElseGet(() -> {
			DeepCharter.LOGGER.error("No dry ground within {} blocks of the world spawn {} {}: building the colony at the spawn anyway, on water",
					ColonyTuning.DEFAULT.searchRings() * ColonyTuning.DEFAULT.searchStepChunks() * 16, wanted.getX(), wanted.getZ());
			return wanted;
		});
		long played = overworld.getGameTime();
		if (played > ColonyTuning.DEFAULT.freshWorldTicks()) {
			DeepCharter.LOGGER.warn("Building the colony in a world that has run for {} ticks: it replaces everything in the {} x {} x {} blocks at {} {}",
					played, ColonyTuning.DEFAULT.padSize(), ColonyTuning.DEFAULT.padSize(), ColonyTuning.DEFAULT.clearHeight(), centre.getX(), centre.getZ());
		}
		loadPadChunks(overworld, centre);
		BlockPos ground = centre.atY(new ColonyBuilder(overworld, centre).groundAt(centre.getX(), centre.getZ()));
		ColonySite.Placed started = new ColonySite.Placed(ground, ColonyLayout.read(server).anchorsAt(ground), false);
		site.begin(started);
		return started;
	}

	/**
	 * The centre of dry ground nearest to {@code from}, searched in a square spiral in steps of
	 * {@link ColonyTuning#searchStepChunks()} chunks up to {@link ColonyTuning#searchRings()} steps out. A centre is dry when
	 * its middle, its corners and the middles of its sides have no fluid at the surface. Empty when none is found.
	 */
	public static Optional<BlockPos> findDryGround(ServerLevel level, BlockPos from) {
		int step = ColonyTuning.DEFAULT.searchStepChunks() * 16;
		for (int ring = 0; ring <= ColonyTuning.DEFAULT.searchRings(); ring++) {
			for (int dx = -ring; dx <= ring; dx++) {
				for (int dz = -ring; dz <= ring; dz++) {
					if (Math.max(Math.abs(dx), Math.abs(dz)) == ring && isDry(level, from.getX() + dx * step, from.getZ() + dz * step)) {
						return Optional.of(new BlockPos(from.getX() + dx * step, 0, from.getZ() + dz * step));
					}
				}
			}
		}
		return Optional.empty();
	}

	private static boolean isDry(ServerLevel level, int x, int z) {
		int half = ColonyTuning.DEFAULT.padSize() / 2;
		for (int dx = -half; dx <= half; dx += half) {
			for (int dz = -half; dz <= half; dz += half) {
				// The height of a chunk that is not loaded reads as the floor, so load it first.
				level.getChunk((x + dx) >> 4, (z + dz) >> 4);
				int top = level.getHeight(Heightmap.Types.WORLD_SURFACE, x + dx, z + dz) - 1;
				if (!level.getFluidState(new BlockPos(x + dx, top, z + dz)).isEmpty()) {
					return false;
				}
			}
		}
		return true;
	}

	private static void loadPadChunks(ServerLevel level, BlockPos centre) {
		forEachPadChunk(level, centre, chunk -> {
		});
	}

	private static void forEachPadChunk(ServerLevel level, BlockPos centre, Consumer<LevelChunk> action) {
		int half = ColonyTuning.DEFAULT.padSize() / 2;
		for (int chunkX = (centre.getX() - half) >> 4; chunkX <= (centre.getX() + half - 1) >> 4; chunkX++) {
			for (int chunkZ = (centre.getZ() - half) >> 4; chunkZ <= (centre.getZ() + half - 1) >> 4; chunkZ++) {
				action.accept(level.getChunk(chunkX, chunkZ));
			}
		}
	}

	private void build(ColonySite.Placed started) {
		int half = ColonyTuning.DEFAULT.padSize() / 2;
		loadPadChunks(level, centre);
		blendEdges();
		ColonyLayout layout = ColonyLayout.read(level.getServer());
		Map<ColonyAnchor, BlockPos> anchors = layout.anchorsAt(centre);
		if (!anchors.equals(started.anchors())) {
			throw new IllegalStateException("the colony's layout changed since its build began: " + started.anchors() + " became " + anchors
					+ ". A world whose colony build stopped under an older layout cannot finish it: start a new world, or delete the world's data/deepcharter/colony.dat so the colony is placed again");
		}
		flatten(centre.getX() - half, centre.getZ() - half, centre.getX() + half - 1, centre.getZ() + half - 1, centre.getY());
		layout.pieces().forEach(piece -> piece.place(level, centre));
		terminals(anchors);
		// The pad's chunks are loaded already, so the Conduit's overworld part is set now and not when they load.
		forEachPadChunk(level, centre, chunk -> Conduit.place(level, chunk, started, LayerChain.SURFACE));
	}

	/** Every registered terminal type that has a plinth anchor stands on its plinth, facing the square. The contract terminal's plinth is bare until #72 registers it. */
	private void terminals(Map<ColonyAnchor, BlockPos> anchors) {
		for (TerminalType type : TerminalTypes.all()) {
			ColonyAnchor.forTerminal(type).ifPresent(anchor -> set(anchors.get(anchor),
					type.block().defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.SOUTH)));
		}
	}

	/** The Y of the ground at X and Z: the highest block that is not air, water, foliage or a tree. */
	private int groundAt(int x, int z) {
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(x, level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z), z);
		while (pos.getY() > level.getMinY() && !isGround(level.getBlockState(pos))) {
			pos.move(Direction.DOWN);
		}
		return pos.getY();
	}

	private static boolean isGround(BlockState state) {
		return !state.isAir() && state.getFluidState().isEmpty() && !state.canBeReplaced()
				&& !state.is(BlockTags.LEAVES) && !state.is(BlockTags.LOGS);
	}

	/**
	 * Grades the land around the pad to the pad's ground: each column of the margin is cut or filled to the height {@link ColonyEdge}
	 * gives it, topped with the surface block it had (the plain's regolith, the mesa's ochre, the basalt) and scattered with the
	 * packed regolith and rock of the layers under it, thickest by the pad. Whatever stood above the new ground, trees included, goes.
	 */
	private void blendEdges() {
		int half = ColonyTuning.DEFAULT.padSize() / 2;
		int reach = half + ColonyTuning.DEFAULT.edgeMargin();
		int originX = centre.getX() - reach;
		int originZ = centre.getZ() - reach;
		int size = 2 * reach;
		for (int chunkX = originX >> 4; chunkX <= (originX + size - 1) >> 4; chunkX++) {
			for (int chunkZ = originZ >> 4; chunkZ <= (originZ + size - 1) >> 4; chunkZ++) {
				level.getChunk(chunkX, chunkZ);
			}
		}
		int[][] natural = new int[size][size];
		for (int i = 0; i < size; i++) {
			for (int j = 0; j < size; j++) {
				natural[i][j] = groundAt(originX + i, originZ + j);
			}
		}
		int[][] ground = ColonyEdge.heights(natural, originX, originZ, centre.getY());
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int i = 0; i < size; i++) {
			for (int j = 0; j < size; j++) {
				int x = originX + i;
				int z = originZ + j;
				int outside = Math.max(Math.max(centre.getX() - half - x, x - (centre.getX() + half - 1)), Math.max(centre.getZ() - half - z, z - (centre.getZ() + half - 1)));
				if (outside > 0) {
					blendColumn(pos, x, z, natural[i][j], ground[i][j], outside);
				}
			}
		}
	}

	private void blendColumn(BlockPos.MutableBlockPos pos, int x, int z, int natural, int ground, int outside) {
		int surface = Math.min(level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z), level.getMaxY() + 1);
		BlockState top = scatter(x, z, outside, naturalSurface(level.getBlockState(pos.set(x, natural, z))));
		for (int y = ground + 1; y < surface; y++) {
			set(pos.set(x, y, z), Blocks.AIR.defaultBlockState());
		}
		for (int y = natural + 1; y < ground; y++) {
			set(pos.set(x, y, z), underSurface(ground - y));
		}
		if (ground != natural) {
			for (int y = ground - 1; y >= ground - LAYER_PACKED; y--) {
				set(pos.set(x, y, z), underSurface(ground - y));
			}
		}
		set(pos.set(x, ground, z), top);
	}

	/** The block that tops a column the build grades: its own, when it is one of the surface's, and the plain's regolith when it is not. */
	private static BlockState naturalSurface(BlockState natural) {
		return natural.is(SurfaceBlocks.OCHRE_REGOLITH) || natural.is(SurfaceBlocks.BASALT_OUTCROP)
				? natural : SurfaceBlocks.REGOLITH.defaultBlockState();
	}

	/** The packed regolith just under the surface and the rock under that, as the surface's material rule lays them. */
	private static BlockState underSurface(int depth) {
		return (depth <= LAYER_PACKED ? SurfaceBlocks.REGOLITH_PACKED : SurfaceBlocks.REGOLITH_ROCK).defaultBlockState();
	}

	/**
	 * The surface block of a column {@code outside} blocks from the pad's edge (0 or less on the pad's own rim): the given one, or
	 * on a share of columns, falling off with the distance, the packed regolith or the rock of the layers under it.
	 */
	private static BlockState scatter(int x, int z, int outside, BlockState surface) {
		int margin = ColonyTuning.DEFAULT.edgeMargin();
		int rock = SCATTER_PERCENT * (margin - Math.max(outside, 0)) / margin;
		int roll = noise(x, z) % 100;
		if (roll < rock) {
			return SurfaceBlocks.REGOLITH_ROCK.defaultBlockState();
		}
		return roll < rock * 3 ? SurfaceBlocks.REGOLITH_PACKED.defaultBlockState() : surface;
	}

	/** Cuts the terrain above {@code ground} away, fills hollows below it, and lays the pad's surface, the plain's regolith. */
	private void flatten(int minX, int minZ, int maxX, int maxZ, int ground) {
		int top = Math.min(ground + ColonyTuning.DEFAULT.clearHeight(), level.getMaxY());
		int bottom = Math.max(ground - ColonyTuning.DEFAULT.fillDepth(), level.getMinY());
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		level.getEntitiesOfClass(Display.BlockDisplay.class, new AABB(minX, ground, minZ, maxX + 1, top + 1, maxZ + 1)).forEach(Entity::discard);
		for (int x = minX; x <= maxX; x++) {
			for (int z = minZ; z <= maxZ; z++) {
				for (int y = ground + 1; y <= top; y++) {
					set(pos.set(x, y, z), Blocks.AIR.defaultBlockState());
				}
				int rim = Math.min(Math.min(x - minX, maxX - x), Math.min(z - minZ, maxZ - z));
				set(pos.set(x, ground, z), rim < RIM ? scatter(x, z, 0, SurfaceBlocks.REGOLITH.defaultBlockState()) : SurfaceBlocks.REGOLITH.defaultBlockState());
				for (int y = ground - 1; y >= bottom && !isGround(level.getBlockState(pos.set(x, y, z))); y--) {
					set(pos, Blocks.DIRT.defaultBlockState());
				}
			}
		}
	}

	/** The same small number for the same X and Z in every world, so ruins look worn at random but build the same every time. */
	private static int noise(int x, int z) {
		return ((x * 73856093) ^ (z * 19349663)) >>> 8 & 0xFFFF;
	}

	private void set(BlockPos pos, BlockState state) {
		if (!level.getBlockState(pos).equals(state)) {
			level.setBlock(pos, state, FLAGS);
		}
	}
}
