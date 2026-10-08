package io.github.pkeppeler.deepcharter.colony;

import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CandleBlock;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.LevelData;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.handbook.NoteBlock;
import io.github.pkeppeler.deepcharter.layer.LayerChain;
import io.github.pkeppeler.deepcharter.terminal.TerminalType;
import io.github.pkeppeler.deepcharter.terminal.TerminalTypes;

/**
 * Builds the colony once, at world spawn, the first time a server starts with this mod: a flattened pad of
 * {@link ColonyTuning#padSize()} blocks a side with every building of the lore canon (section 11), all in ruins. The layout is
 * code, not a template: it is a table of offsets from the pad's centre, north is -Z.
 *
 * <p>The pad is centred on the world spawn, or on the nearest dry ground if the spawn is in water ({@link #findDryGround}).
 * Building clears a volume of the pad's size and {@link ColonyTuning#clearHeight()} blocks high: whatever stood there is lost.
 *
 * <p>It records the pad's centre and ground before the first block ({@link ColonySite#begin}) and marks the colony finished
 * after the last, so a build that stops half way is built again over the same pad, at the recorded ground. It then sets the
 * world spawn to the Continuity Office and the respawn radius to 0, so a new player stands in the office.
 */
public final class ColonyBuilder {
	/** A block set without neighbour updates, so a bed or a door built in two steps is not broken by the first. */
	private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
	/** The row of plinths on the north edge of the square is this far from the centre in Z. */
	private static final int PLINTH_Z = -8;

	private final ServerLevel level;
	private final BlockPos centre;
	/** False when only the anchors are wanted: nothing is read from or written to the level. */
	private final boolean placing;
	private final Map<ColonyAnchor, BlockPos> anchors = new EnumMap<>(ColonyAnchor.class);

	private ColonyBuilder(ServerLevel level, BlockPos centre, boolean placing) {
		this.level = level;
		this.centre = centre;
		this.placing = placing;
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
		new ColonyBuilder(overworld, started.center(), true).build(started);
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
		BlockPos ground = centre.atY(new ColonyBuilder(overworld, centre, true).groundAt(centre.getX(), centre.getZ()));
		ColonySite.Placed started = new ColonySite.Placed(ground, new ColonyBuilder(overworld, ground, false).layOutAnchors(), false);
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

	private Map<ColonyAnchor, BlockPos> layOutAnchors() {
		layOut(centre);
		return anchors;
	}

	private void build(ColonySite.Placed started) {
		int half = ColonyTuning.DEFAULT.padSize() / 2;
		loadPadChunks(level, centre);
		flatten(centre.getX() - half, centre.getZ() - half, centre.getX() + half - 1, centre.getZ() + half - 1, centre.getY());
		layOut(centre);
		if (!anchors.equals(started.anchors())) {
			throw new IllegalStateException("the colony's layout changed since its build began: " + started.anchors() + " became " + anchors);
		}
		// The pad's chunks are loaded already, so the Conduit's overworld part is set now and not when they load.
		forEachPadChunk(level, centre, chunk -> Conduit.place(level, chunk, started, LayerChain.SURFACE));
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

	/** Cuts the terrain above {@code ground} away, fills hollows below it, and lays the pad's surface. */
	private void flatten(int minX, int minZ, int maxX, int maxZ, int ground) {
		int top = Math.min(ground + ColonyTuning.DEFAULT.clearHeight(), level.getMaxY());
		int bottom = Math.max(ground - ColonyTuning.DEFAULT.fillDepth(), level.getMinY());
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int x = minX; x <= maxX; x++) {
			for (int z = minZ; z <= maxZ; z++) {
				for (int y = ground + 1; y <= top; y++) {
					set(pos.set(x, y, z), Blocks.AIR.defaultBlockState());
				}
				set(pos.set(x, ground, z), roughGround(x, z));
				for (int y = ground - 1; y >= bottom && !isGround(level.getBlockState(pos.set(x, y, z))); y--) {
					set(pos, Blocks.DIRT.defaultBlockState());
				}
			}
		}
	}

	private static BlockState roughGround(int x, int z) {
		return switch (noise(x, z) % 5) {
			case 0 -> Blocks.GRAVEL.defaultBlockState();
			case 1, 2 -> Blocks.COARSE_DIRT.defaultBlockState();
			default -> Blocks.PACKED_MUD.defaultBlockState();
		};
	}

	/** The same small number for the same X and Z in every world, so ruins look worn at random but build the same every time. */
	private static int noise(int x, int z) {
		return ((x * 73856093) ^ (z * 19349663)) >>> 8 & 0xFFFF;
	}

	private void layOut(BlockPos ground) {
		square(ground);
		terminals(ground);
		statue(ground);
		continuityOffice(ground);
		hangar(ground);
		chapel(ground);
		bunkhouse(ground);
		payOffice(ground);
		personnelOffice(ground);
		lampAndPick(ground);
		conduit(ground);
	}

	/** The square: 21 x 21 of old paving around the statue. */
	private void square(BlockPos ground) {
		for (int dx = -10; dx <= 10; dx++) {
			for (int dz = -10; dz <= 10; dz++) {
				set(ground.offset(dx, 0, dz), paving(ground.getX() + dx, ground.getZ() + dz));
			}
		}
	}

	private static BlockState paving(int x, int z) {
		return switch (noise(x, z) % 4) {
			case 0 -> Blocks.CRACKED_STONE_BRICKS.defaultBlockState();
			case 1 -> Blocks.MOSSY_STONE_BRICKS.defaultBlockState();
			default -> Blocks.STONE_BRICKS.defaultBlockState();
		};
	}

	/**
	 * Five plinths in a row on the north edge of the square. Every registered terminal type that has a plinth anchor stands on
	 * its plinth. That is the four colony terminals, in the order of repair, and the contract terminal once #72 registers it:
	 * until then the fifth plinth is bare.
	 */
	private void terminals(BlockPos ground) {
		int[] columns = {-8, -4, 0, 4, 8};
		ColonyAnchor[] row = {ColonyAnchor.FUEL_PUMP, ColonyAnchor.ORE_PROCESSOR, ColonyAnchor.UPGRADE_TERMINAL,
				ColonyAnchor.REPAIR_STATION, ColonyAnchor.CONTRACT_TERMINAL};
		for (int i = 0; i < row.length; i++) {
			fill(ground, columns[i] - 1, 1, PLINTH_Z - 1, columns[i] + 1, 1, PLINTH_Z + 1, Blocks.POLISHED_DEEPSLATE.defaultBlockState());
			anchors.put(row[i], ground.offset(columns[i], 2, PLINTH_Z));
		}
		for (TerminalType type : TerminalTypes.all()) {
			ColonyAnchor.forTerminal(type).ifPresent(anchor -> set(anchors.get(anchor),
					type.block().defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.SOUTH)));
		}
	}

	/** The Founder: bronze, arms out and palms up, on a pedestal in the middle of the square. */
	private void statue(BlockPos ground) {
		BlockState bronze = Blocks.COPPER_BLOCK.waxed().unaffected().defaultBlockState();
		fill(ground, -1, 1, -1, 1, 2, 1, Blocks.STONE_BRICKS.defaultBlockState());
		anchors.put(ColonyAnchor.STATUE, ground.above());
		fill(ground, 0, 3, 0, 0, 5, 0, bronze);
		fill(ground, -2, 6, 0, 2, 6, 0, bronze);
		set(ground.offset(-3, 6, 0), Blocks.CUT_COPPER_SLAB.waxed().unaffected().defaultBlockState());
		set(ground.offset(3, 6, 0), Blocks.CUT_COPPER_SLAB.waxed().unaffected().defaultBlockState());
		set(ground.offset(0, 7, 0), bronze);
	}

	/** An open hall in the east of the square. The world spawn is its middle, on a blue pad. */
	private void continuityOffice(BlockPos ground) {
		fill(ground, 14, 0, -4, 22, 0, 4, Blocks.POLISHED_ANDESITE.defaultBlockState());
		fill(ground, 17, 0, -1, 19, 0, 1, Blocks.CONCRETE.lightBlue().defaultBlockState());
		// N04, the Continuity Plan leaflet, on the shelves.
		set(ground.offset(15, 3, -3), NoteBlock.stateOf(4));
		ruinedWalls(ground, 14, -4, 22, 4, 4, Blocks.SMOOTH_STONE.defaultBlockState());
		fill(ground, 14, 1, 0, 14, 2, 0, Blocks.AIR.defaultBlockState());
		set(ground.offset(21, 1, -3), Blocks.LECTERN.defaultBlockState());
		fill(ground, 15, 1, -3, 15, 2, -3, Blocks.BOOKSHELF.defaultBlockState());
		anchors.put(ColonyAnchor.CONTINUITY_OFFICE, ground.offset(18, 1, 0));
	}

	/** A bay big enough for a pod, open to the square on its east side. */
	private void hangar(BlockPos ground) {
		fill(ground, -30, 0, 0, -16, 0, 14, Blocks.SMOOTH_STONE.defaultBlockState());
		fill(ground, -26, 0, 4, -20, 0, 10, Blocks.IRON_BLOCK.defaultBlockState());
		ruinedWalls(ground, -30, 0, -16, 14, 7, Blocks.STONE_BRICKS.defaultBlockState());
		fill(ground, -16, 1, 4, -16, 5, 10, Blocks.AIR.defaultBlockState());
		anchors.put(ColonyAnchor.HANGAR, ground.offset(-23, 1, 7));
	}

	/** A small chapel with one candle on its altar. It burns, and nobody says why. */
	private void chapel(BlockPos ground) {
		fill(ground, -22, 0, -19, -18, 0, -13, Blocks.STONE_BRICKS.defaultBlockState());
		ruinedWalls(ground, -22, -19, -18, -13, 5, Blocks.STONE_BRICKS.defaultBlockState());
		fill(ground, -20, 1, -13, -20, 2, -13, Blocks.AIR.defaultBlockState());
		set(ground.offset(-20, 1, -18), Blocks.STONE_BRICKS.defaultBlockState());
		// N03, the visitors' book, on a second block of the altar.
		set(ground.offset(-21, 1, -18), Blocks.STONE_BRICKS.defaultBlockState());
		set(ground.offset(-21, 2, -18), NoteBlock.stateOf(3));
		BlockPos candle = ground.offset(-20, 2, -18);
		set(candle, Blocks.CANDLE.defaultBlockState().setValue(CandleBlock.LIT, true));
		anchors.put(ColonyAnchor.CHAPEL_CANDLE, candle);
		for (int dz = -16; dz <= -14; dz += 2) {
			set(ground.offset(-21, 1, dz), Blocks.SPRUCE_SLAB.defaultBlockState());
			set(ground.offset(-19, 1, dz), Blocks.SPRUCE_SLAB.defaultBlockState());
		}
	}

	/** Rows of bunks, all made up. */
	private void bunkhouse(BlockPos ground) {
		fill(ground, 8, 0, 12, 28, 0, 18, Blocks.SPRUCE_PLANKS.defaultBlockState());
		ruinedWalls(ground, 8, 12, 28, 18, 4, Blocks.SPRUCE_PLANKS.defaultBlockState());
		fill(ground, 18, 1, 12, 18, 2, 12, Blocks.AIR.defaultBlockState());
		for (int dz = 13; dz <= 17; dz += 4) {
			for (int dx = 9; dx <= 26; dx += 3) {
				set(ground.offset(dx, 1, dz), bed(BedPart.FOOT));
				set(ground.offset(dx + 1, 1, dz), bed(BedPart.HEAD));
			}
		}
		anchors.put(ColonyAnchor.BUNKHOUSE, ground.offset(18, 1, 15));
	}

	private static BlockState bed(BedPart part) {
		return Blocks.BED.white().defaultBlockState().setValue(BedBlock.FACING, Direction.EAST).setValue(BedBlock.PART, part);
	}

	/** A counter with a grille, and barrels of pay stubs that nobody came for. */
	private void payOffice(BlockPos ground) {
		fill(ground, -10, 0, 14, -2, 0, 22, Blocks.SPRUCE_PLANKS.defaultBlockState());
		ruinedWalls(ground, -10, 14, -2, 22, 4, Blocks.BRICKS.defaultBlockState());
		fill(ground, -6, 1, 14, -6, 2, 14, Blocks.AIR.defaultBlockState());
		fill(ground, -9, 1, 17, -3, 1, 17, Blocks.SPRUCE_PLANKS.defaultBlockState());
		fill(ground, -9, 2, 17, -3, 2, 17, Blocks.IRON_BARS.defaultBlockState());
		for (int dx = -9; dx <= -3; dx += 2) {
			set(ground.offset(dx, 1, 20), Blocks.BARREL.defaultBlockState());
		}
		// N02, a pay stub, on a barrel.
		set(ground.offset(-5, 2, 20), NoteBlock.stateOf(2));
		anchors.put(ColonyAnchor.PAY_OFFICE, ground.offset(-6, 1, 15));
	}

	/** Joy's desk, with her calendar (N01) on it: she recorded the templates. */
	private void personnelOffice(BlockPos ground) {
		fill(ground, 2, 0, 14, 10, 0, 22, Blocks.DYED_TERRACOTTA.white().defaultBlockState());
		ruinedWalls(ground, 2, 14, 10, 22, 4, Blocks.DYED_TERRACOTTA.white().defaultBlockState());
		fill(ground, 6, 1, 14, 6, 2, 14, Blocks.AIR.defaultBlockState());
		fill(ground, 5, 1, 20, 7, 1, 20, Blocks.SPRUCE_PLANKS.defaultBlockState());
		set(ground.offset(6, 2, 20), NoteBlock.stateOf(1));
		anchors.put(ColonyAnchor.PERSONNEL_OFFICE, ground.offset(6, 1, 16));
	}

	/** The miners' bar, burned out; beside it only the footings of the bandstand. */
	private void lampAndPick(BlockPos ground) {
		fill(ground, -27, 0, 16, -17, 0, 26, Blocks.BLACKSTONE.defaultBlockState());
		fill(ground, -24, 0, 19, -20, 0, 23, Blocks.COAL_BLOCK.defaultBlockState());
		ruinedWalls(ground, -27, 16, -17, 26, 3, Blocks.BLACKSTONE.defaultBlockState());
		fill(ground, -22, 1, 16, -22, 2, 16, Blocks.AIR.defaultBlockState());
		fill(ground, -26, 1, 24, -18, 1, 24, Blocks.DEEPSLATE_TILES.defaultBlockState());
		for (int dx = -15; dx <= -13; dx += 2) {
			for (int dz = 17; dz <= 19; dz += 2) {
				set(ground.offset(dx, 1, dz), Blocks.STONE.defaultBlockState());
			}
		}
		anchors.put(ColonyAnchor.LAMP_AND_PICK, ground.offset(-22, 1, 18));
	}

	/** The Conduit rises behind the ore processor and a pipe runs from it to the processor. The casing is set by {@link Conduit}. */
	private void conduit(BlockPos ground) {
		fill(ground, -4, 2, -12, -4, 2, -10, Blocks.COPPER_BLOCK.waxed().unaffected().defaultBlockState());
		anchors.put(ColonyAnchor.CONDUIT, ground.offset(-4, 1, -14));
	}

	/** Walls around the box, {@code height} high, with the worn tops of a ruin: some of the top rows are gone. */
	private void ruinedWalls(BlockPos ground, int dx1, int dz1, int dx2, int dz2, int height, BlockState wall) {
		for (int dx = dx1; dx <= dx2; dx++) {
			for (int dz = dz1; dz <= dz2; dz++) {
				if (dx != dx1 && dx != dx2 && dz != dz1 && dz != dz2) {
					continue;
				}
				int worn = noise(ground.getX() + dx, ground.getZ() + dz) % 7;
				int top = worn == 0 ? height - 2 : worn <= 2 ? height - 1 : height;
				for (int dy = 1; dy <= top; dy++) {
					set(ground.offset(dx, dy, dz), wall);
				}
			}
		}
	}

	private void fill(BlockPos ground, int dx1, int dy1, int dz1, int dx2, int dy2, int dz2, BlockState state) {
		for (int dx = dx1; dx <= dx2; dx++) {
			for (int dy = dy1; dy <= dy2; dy++) {
				for (int dz = dz1; dz <= dz2; dz++) {
					set(ground.offset(dx, dy, dz), state);
				}
			}
		}
	}

	private void set(BlockPos pos, BlockState state) {
		if (placing && !level.getBlockState(pos).equals(state)) {
			level.setBlock(pos, state, FLAGS);
		}
	}
}
