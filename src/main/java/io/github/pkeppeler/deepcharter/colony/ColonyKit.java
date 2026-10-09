package io.github.pkeppeler.deepcharter.colony;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * The colony kit: the Company's building materials, from corrugated steel to sodium lamps, that the colony concepts of #335 are
 * built from (docs/design/colony-concepts.md). Nothing in a player's world places them yet; the colony rebuild (#244) will use the
 * chosen ones. Their models, blockstates and sign tiles are written by tools/colony/build.py and their textures by
 * tools/textures/texgen.py. Like the Conduit they are Company property:
 * unbreakable in survival ({@link ColonyBlocks#register}).
 */
public final class ColonyKit {
	private static final List<Block> ALL = new ArrayList<>();

	public static final Block CORRUGATED_CREAM = cube("corrugated_cream", SoundType.METAL);
	public static final Block CORRUGATED_RED = cube("corrugated_red", SoundType.METAL);
	public static final Block RIVETED_PLATE = cube("riveted_plate", SoundType.METAL);
	public static final Block RIVETED_PLATE_RED = cube("riveted_plate_red", SoundType.METAL);
	public static final Block ENAMEL_PANEL = cube("enamel_panel", SoundType.METAL);
	public static final Block STEEL_FRAME = cube("steel_frame", SoundType.METAL);
	public static final Block HAZARD_BAND = cube("hazard_band", SoundType.METAL);
	public static final Block CONCRETE_FOOTING = cube("concrete_footing", SoundType.STONE);
	public static final Block BRASS_TRIM = cube("brass_trim", SoundType.METAL);
	/** Open steel grating: a full block that lets the light and the view through. */
	public static final Block GRATING = register("grating", SoundType.METAL, properties -> new Block(properties.noOcclusion()));

	public static final Block WINDOW_SMALL_LIT = window("window_small_lit", 10);
	public static final Block WINDOW_SMALL_DARK = window("window_small_dark", 0);
	public static final Block WINDOW_RIBBON_LIT = window("window_ribbon_lit", 10);
	public static final Block WINDOW_RIBBON_DARK = window("window_ribbon_dark", 0);
	public static final Block FURNACE_HATCH = window("furnace_hatch", 9);
	public static final Block GAUGE_PANEL = window("gauge_panel", 3);
	public static final Block WINDER_DOOR = window("winder_door", 0);

	public static final Block WALL_LAMP = facing("wall_lamp", SoundType.LANTERN, 14, Block.box(5, 2, 4, 11, 12, 16));
	public static final Block FLOODLIGHT = facing("floodlight", SoundType.LANTERN, 15, Block.box(3, 0, 3, 13, 13, 13));
	public static final Block RAILING = facing("railing", SoundType.METAL, 0, Block.box(0, 0, 0, 16, 16, 2));
	public static final Block ROOF_SLOPE = facing("roof_slope", SoundType.METAL, 0, Block.box(0, 0, 0, 16, 8, 16));
	public static final Block BRACE = facing("brace", SoundType.METAL, 0, Block.box(4, 4, 4, 12, 12, 12));
	public static final Block BRACE_RED = facing("brace_red", SoundType.METAL, 0, Block.box(4, 4, 4, 12, 12, 12));
	public static final Block CONVEYOR = facing("conveyor", SoundType.METAL, 0, Block.box(0, 0, 0, 16, 8, 16));
	public static final Block ROOF_PEAK = register("roof_peak", SoundType.METAL, properties -> new KitRidgeBlock(properties.noOcclusion()));

	public static final Block STEEL_BEAM = pillar("steel_beam", 12);
	public static final Block STEEL_BEAM_RED = pillar("steel_beam_red", 12);
	public static final Block LATTICE_GIRDER = pillar("lattice_girder", 16);
	public static final Block LATTICE_GIRDER_RED = pillar("lattice_girder_red", 16);
	public static final Block PIPE = pillar("pipe", 8);
	public static final Block PIPE_BRASS = pillar("pipe_brass", 8);
	public static final Block CABLE = pillar("cable", 2);

	public static final Block ENAMEL_SIGN = register("enamel_sign", SoundType.METAL, properties -> new KitSignBlock(properties.noOcclusion()));
	/** Never placed: block displays draw its pieces. It has no item. */
	public static final Block SCULPTURE = registerBlock("colony_sculpture", SoundType.METAL,
			properties -> new KitSculptureBlock(properties.noOcclusion().noCollision()));

	private ColonyKit() {
	}

	/** Loads the class, which registers the blocks. */
	public static void register() {
	}

	/** Every kit block, in the order above. */
	public static List<Block> all() {
		return Collections.unmodifiableList(ALL);
	}

	private static Block cube(String path, SoundType sound) {
		return register(path, sound, Block::new);
	}

	private static Block window(String path, int light) {
		return register(path, SoundType.METAL, properties -> new KitFacingBlock(properties.lightLevel(state -> light), Block.box(0, 0, 0, 16, 16, 16)));
	}

	private static Block facing(String path, SoundType sound, int light, VoxelShape north) {
		return register(path, sound, properties -> new KitFacingBlock(properties.noOcclusion().lightLevel(state -> light), north));
	}

	/** A member along an axis, size units square. None is a solid cube (the girder is open lacing), so none hides its neighbours. */
	private static Block pillar(String path, double size) {
		return register(path, SoundType.METAL, properties -> new KitPillarBlock(properties.noOcclusion(), size));
	}

	private static Block register(String path, SoundType sound, Function<BlockBehaviour.Properties, Block> factory) {
		return kept(ColonyBlocks.register(path, sound, factory));
	}

	private static Block registerBlock(String path, SoundType sound, Function<BlockBehaviour.Properties, Block> factory) {
		return kept(ColonyBlocks.registerBlock(path, sound, factory));
	}

	private static Block kept(Block block) {
		ALL.add(block);
		return block;
	}
}
